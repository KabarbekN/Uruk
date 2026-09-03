package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.semanticmap.contract.Protocol;
import io.semanticmap.platform.graph.internal.FactIngestion;
import io.semanticmap.platform.graph.internal.GraphBuilder;
import io.semanticmap.platform.graph.internal.ScenarioBuilder;
import io.semanticmap.platform.graph.internal.SemanticDiffer;
import io.semanticmap.platform.graph.internal.Semantics;
import io.semanticmap.platform.review.ReviewController;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs in verify, after the reactor packages analyzers/java-spring/target/analyzer-java-spring.jar. */
@Testcontainers
class NativeAnalyzerAcceptanceIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ORDER_METHOD =
            "java:method:example.orders.OrderService#createOrder(example.orders.OrderRequest)";
    private static final String MINIMUM_CONSTANT = "java:type:example.orders.OrderService#MINIMUM_AMOUNT";
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID org = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private Db db;
    private TransactionTemplate transactions;
    private GraphPipeline pipeline;
    private GraphQueries queries;
    private ReviewController reviews;
    private Path repository;
    private Path jar;
    private Path artifacts;

    private record Run(UUID id, UUID revision, UUID execution, Path workspace, Path output, JsonNode manifest) {}

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void setup() throws IOException {
        assertThat(Runtime.version().feature())
                .as("Run the reactor and native CLI with Java 21")
                .isEqualTo(21);
        repository = repositoryRoot();
        jar = repository.resolve("analyzers/java-spring/target/analyzer-java-spring.jar");
        assertThat(jar)
                .as("Build the CLI first: mvn -pl analyzers/java-spring,backend/platform -am verify")
                .isRegularFile();
        artifacts = Files.createTempDirectory(repository.resolve("backend/platform/target"), "native-acceptance-");
        System.out.println("Native analyzer acceptance artifacts: " + artifacts);

        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        db = new Db(new JdbcTemplate(source), mapper);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        db.update("INSERT INTO organization(id,name) VALUES (?,?)", org, "Native analyzer acceptance");
        db.update(
                "INSERT INTO user_account(id,subject,display_name) VALUES (?,?,?)", user, user.toString(), "Reviewer");
        db.update("INSERT INTO project(id,organization_id,name) VALUES (?,?,?)", project, org, "Spring orders");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new TenantContext.Principal(org, user),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ORG_ADMIN"))));
        var tenant = new TenantContext();
        var access = new Access(db, tenant);
        var differ = new SemanticDiffer(db);
        queries = new GraphQueries(db, tenant, access, differ);
        pipeline = new GraphPipeline(
                new FactIngestion(db, mapper), new GraphBuilder(db, new ScenarioBuilder(db, 8, 100)), differ);
        reviews = new ReviewController(queries, db, tenant, new Audit(db), access);
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeEndpointAndConfirmed500RuleBecome300ThresholdChangeWithBothEvidenceChains() throws Exception {
        var beforeRun = analyze("spring-order-service", null);
        var endpoint = node(beforeRun, "spring:endpoint:POST:/api/orders");
        assertThat(endpoint).containsEntry("kind", "ENDPOINT");
        assertThat(Semantics.map(endpoint.get("properties")))
                .containsEntry("httpMethod", "POST")
                .containsEntry("path", "/api/orders");
        assertThat(queries.nodeEvidence(Semantics.uuid(endpoint.get("id"))))
                .anySatisfy(e ->
                        assertThat(e).containsEntry("verified", true).containsEntry("origin", "FRAMEWORK_DERIVED"));

        var rules = db.rows(
                """
                SELECT * FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=?
                AND kind='BUSINESS_RULE' AND properties->>'ownerKey'=?
                AND properties #>> '{normalizedCondition,right,constantKey}'=?
                """,
                org,
                project,
                beforeRun.id(),
                ORDER_METHOD,
                MINIMUM_CONSTANT);
        assertThat(rules)
                .as("Real parsed minimum order rule; inspect %s", beforeRun.output())
                .hasSize(1);
        var before = rules.getFirst();
        assertThreshold(before, 500);
        String stableKey = before.get("stableKey").toString();
        UUID beforeId = Semantics.uuid(before.get("id"));
        var beforeEvidence = verifiedEvidence(beforeRun, beforeId, 500);
        var rawBefore = rawRule(beforeRun, stableKey);
        assertThat(rawBefore
                        .path("properties")
                        .path("condition")
                        .path("right")
                        .path("value")
                        .asInt())
                .isEqualTo(500);

        transactions.executeWithoutResult(status -> reviews.review(
                beforeId,
                new ReviewController.ReviewRequest(
                        "CONFIRMED", "Verified minimum amount and rejection branch", null, null, null)));
        assertThat(queries.node(beforeId)).containsEntry("reviewStatus", "CONFIRMED");
        assertThat(rawRule(beforeRun, stableKey))
                .as("Review must not overwrite analyzer facts")
                .isEqualTo(rawBefore);

        var afterRun = analyze("spring-order-service-revision2", beforeRun.id());
        var after = node(afterRun, stableKey);
        assertThreshold(after, 300);
        UUID afterId = Semantics.uuid(after.get("id"));
        var afterEvidence = verifiedEvidence(afterRun, afterId, 300);
        assertThat(rawRule(afterRun, stableKey)
                        .path("properties")
                        .path("condition")
                        .path("right")
                        .path("value")
                        .asInt())
                .isEqualTo(300);
        transactions.executeWithoutResult(status -> pipeline.diff(org, project, beforeRun.id(), afterRun.id()));
        var changes = transactions.execute(status -> queries.diff(project, beforeRun.id(), afterRun.id()));
        writeArtifact(artifacts.resolve("semantic-diff.json"), changes);
        writeArtifact(artifacts.resolve("before-rule-evidence.json"), beforeEvidence);
        writeArtifact(artifacts.resolve("after-rule-evidence.json"), afterEvidence);

        var ruleChanges = changes.stream()
                .filter(change -> stableKey.equals(change.get("subjectStableKey")))
                .toList();
        assertThat(ruleChanges)
                .as("Exact stable-key comparison; inspect %s", artifacts)
                .anySatisfy(change -> assertThat(change)
                        .containsEntry("changeType", "MODIFIED")
                        .containsEntry("impactType", "THRESHOLD_CHANGED"))
                .anySatisfy(change -> assertThat(change)
                        .containsEntry("changeType", "REVIEW_BECAME_STALE")
                        .containsEntry("impactType", "REVIEW_BECAME_STALE"))
                .noneMatch(change -> "RENAMED".equals(change.get("changeType")));
        for (var change : ruleChanges) {
            assertThreshold(Semantics.map(change.get("before")), 500);
            assertThreshold(Semantics.map(change.get("after")), 300);
            assertThat(Semantics.strings(change.get("beforeEvidenceIds")))
                    .containsExactlyInAnyOrderElementsOf(evidenceIds(beforeEvidence));
            assertThat(Semantics.strings(change.get("afterEvidenceIds")))
                    .containsExactlyInAnyOrderElementsOf(evidenceIds(afterEvidence))
                    .doesNotContainAnyElementsOf(evidenceIds(beforeEvidence));
        }
        assertThat(queries.node(beforeId)).containsEntry("reviewStatus", "CONFIRMED");
        assertThat(queries.node(afterId)).containsEntry("reviewStatus", "STALE");
        assertThat(queries.nodeEvidence(beforeId)).isEqualTo(beforeEvidence);
        assertThat(rawRule(beforeRun, stableKey)).isEqualTo(rawBefore);
        System.out.printf(
                "Native acceptance passed: %d + %d CLI facts; endpoint POST /api/orders; %s; 500 -> 300; %s%n",
                beforeRun.manifest().path("factCount").asInt(),
                afterRun.manifest().path("factCount").asInt(),
                stableKey,
                artifacts);
    }

    private Run analyze(String fixture, UUID baseline) throws Exception {
        UUID runId = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        Path workspace = repository.resolve("fixtures").resolve(fixture).toRealPath();
        Path directory = Files.createDirectory(artifacts.resolve(fixture));
        Path output = directory.resolve("output");
        ObjectNode request =
                (ObjectNode) mapper.readTree(workspace.resolve("request.json").toFile());
        request.put("analysisId", runId.toString())
                .put("organizationId", org.toString())
                .put("projectId", project.toString());
        Path requestPath = directory.resolve("request.json");
        writeArtifact(requestPath, request);
        var revisionData = request.path("revision");
        var componentData = request.path("component");
        String commit = revisionData.path("commitSha").asText();
        db.update(
                "INSERT INTO revision(id,organization_id,project_id,commit_sha,branch,fingerprint) VALUES (?,?,?,?,?,?)",
                revision,
                org,
                project,
                commit,
                revisionData.path("branch").asText(),
                Protocol.hash(commit));
        db.update(
                """
                INSERT INTO analysis_run(id,organization_id,project_id,revision_id,baseline_run_id,status,
                repository_url,requested_by,config_hash,workspace_path) VALUES (?,?,?,?,?,'RUNNING',?,?,?,?)
                """,
                runId,
                org,
                project,
                revision,
                baseline,
                workspace.toUri().toString(),
                user,
                Protocol.hash(mapper.writeValueAsString(request)),
                workspace.toString());
        int exit = runCli(
                workspace,
                requestPath,
                output,
                request.path("policy").path("maxDurationSeconds").asLong() + 30);
        JsonNode manifest = mapper.readTree(output.resolve("manifest.json").toFile());
        JsonNode coverage = mapper.readTree(output.resolve("coverage.json").toFile());
        assertThat(manifest.path("status").asText()).isEqualTo(exit == 0 ? "SUCCEEDED" : "PARTIAL");
        assertThat(manifest.path("capabilitiesFailed").isArray()).isTrue();
        assertThat(manifest.path("capabilitiesFailed").size()).isZero();
        assertThat(manifest.path("factCount").asInt()).isPositive();
        assertThat(coverage.path("filesFailed").asInt(-1)).isZero();
        assertThat(coverage.path("filesParsed").asInt())
                .isPositive()
                .isEqualTo(coverage.path("filesDiscovered").asInt());
        assertThat(Files.size(output.resolve("facts.ndjson")))
                .isLessThanOrEqualTo(
                        request.path("policy").path("maxOutputBytes").asLong());
        List<JsonNode> diagnostics;
        try (var lines = Files.lines(output.resolve("diagnostics.ndjson"))) {
            diagnostics = lines.filter(line -> !line.isBlank()).map(db::parse).toList();
        }
        var analyzer = manifest.path("analyzer");
        assertThat(analyzer.path("id").asText()).isEqualTo("java-spring");
        assertThat(analyzer.path("version").asText()).isNotBlank();
        assertThat(analyzer.path("imageDigest").isNull())
                .as("A native CLI must not claim an OCI image digest")
                .isTrue();
        String status = exit == 0 ? "SUCCEEDED" : "PARTIALLY_SUCCEEDED";
        db.update(
                """
                INSERT INTO technology_component(id,organization_id,project_id,revision_id,analysis_run_id,root_path,
                languages,frameworks,build_systems,database_technologies,evidence,fingerprint)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,'[]','[]',?)
                """,
                component,
                org,
                project,
                revision,
                runId,
                componentData.path("rootPath").asText(),
                db.json(componentData.path("languages")),
                db.json(componentData.path("frameworks")),
                db.json(List.of(componentData.path("buildSystem").asText())),
                Protocol.hash(componentData.toString()));
        db.update(
                """
                INSERT INTO analyzer_execution(id,organization_id,project_id,revision_id,analysis_run_id,component_id,
                analyzer_key,version,image_reference,status,reason,requested_capabilities,output_path,coverage,diagnostics,finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb,now())
                """,
                execution,
                org,
                project,
                revision,
                runId,
                component,
                analyzer.path("id").asText(),
                analyzer.path("version").asText(),
                jar.toUri().toString(),
                status,
                "Native Java CLI acceptance",
                db.json(request.path("requestedCapabilities")),
                output.toString(),
                db.json(coverage),
                db.json(diagnostics));
        transactions.executeWithoutResult(
                tx -> pipeline.ingest(org, project, revision, runId, execution, workspace, output));
        var quarantine = db.rows(
                "SELECT line_number,reason,raw_line FROM fact_quarantine WHERE organization_id=? AND project_id=? AND analysis_run_id=? ORDER BY line_number",
                org,
                project,
                runId);
        writeArtifact(directory.resolve("quarantine.json"), quarantine);
        assertThat(quarantine)
                .as("Every real CLI fact must validate; inspect %s", directory)
                .isEmpty();
        var count = db.one(
                "SELECT count(*) AS total FROM raw_fact WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND analyzer_execution_id=?",
                org,
                project,
                runId,
                execution);
        assertThat(((Number) count.get("total")).longValue())
                .isEqualTo(manifest.path("factCount").asLong());
        transactions.executeWithoutResult(tx -> pipeline.build(org, project, revision, runId));
        db.update(
                "UPDATE analysis_run SET status=?,progress=100,finished_at=now() WHERE organization_id=? AND project_id=? AND id=?",
                status,
                org,
                project,
                runId);
        return new Run(runId, revision, execution, workspace, output, manifest);
    }

    private int runCli(Path workspace, Path request, Path output, long timeoutSeconds) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path log = request.resolveSibling("cli.log");
        var builder = new ProcessBuilder(
                java.toString(),
                "-Xmx768m",
                "-jar",
                jar.toString(),
                "analyze",
                "--request",
                request.toString(),
                "--output",
                output.toString());
        builder.environment().put("SEMANTIC_WORKSPACE", workspace.toString());
        builder.environment().remove("SEMANTIC_ANALYZER_IMAGE_DIGEST");
        var process = builder.directory(repository.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertThat(process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
                    .as("Native analyzer exceeded %d seconds; inspect %s", timeoutSeconds, log)
                    .isTrue();
            assertThat(process.exitValue())
                    .as("CLI failed; output %s; log: %s", output, logExcerpt(log))
                    .isIn(0, 10);
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private void assertThreshold(Map<String, Object> node, int expected) {
        assertThat(node).containsEntry("kind", "BUSINESS_RULE");
        var properties = Semantics.map(node.get("properties"));
        var condition = Semantics.map(properties.get("normalizedCondition"));
        assertThat(condition).containsEntry("type", "COMPARISON").containsEntry("operator", "LESS_THAN");
        assertThat(Semantics.map(condition.get("right")))
                .containsEntry("type", "CONSTANT_REFERENCE")
                .containsEntry("constantKey", MINIMUM_CONSTANT)
                .containsEntry("value", expected);
        assertThat(Semantics.list(properties.get("trueOutcomes")))
                .anySatisfy(outcome -> assertThat(Semantics.map(outcome))
                        .containsEntry("kind", "THROW")
                        .containsEntry("exception", "OrderRejectedException"));
    }

    private List<Map<String, Object>> verifiedEvidence(Run run, UUID node, int threshold) throws IOException {
        var evidence = queries.nodeEvidence(node);
        assertThat(evidence).hasSizeGreaterThanOrEqualTo(2);
        for (var item : evidence) {
            assertThat(item)
                    .containsEntry("verified", true)
                    .containsEntry("sourceAvailable", true)
                    .containsEntry("revisionId", run.revision())
                    .containsEntry("analyzerExecutionId", run.execution())
                    .containsEntry(
                            "analyzerId",
                            run.manifest().path("analyzer").path("id").asText())
                    .containsEntry(
                            "analyzerVersion",
                            run.manifest().path("analyzer").path("version").asText())
                    .containsEntry("imageDigest", null)
                    .containsEntry("origin", "STATIC_SYNTAX");
            var scoped = db.one(
                    "SELECT id FROM evidence WHERE id=? AND organization_id=? AND project_id=? AND analysis_run_id=?",
                    item.get("id"),
                    org,
                    project,
                    run.id());
            assertThat(scoped.get("id")).isEqualTo(item.get("id"));
            var source = Files.readAllLines(
                    run.workspace().resolve(item.get("filePath").toString()), StandardCharsets.UTF_8);
            String fullLines = String.join(
                    "\n",
                    source.subList(
                            ((Number) item.get("startLine")).intValue() - 1,
                            ((Number) item.get("endLine")).intValue()));
            assertThat(item).containsEntry("snippet", fullLines).containsEntry("snippetHash", Protocol.hash(fullLines));
        }
        assertThat(evidence)
                .anySatisfy(
                        item -> assertThat(item.get("snippet").toString()).contains("MINIMUM_AMOUNT = " + threshold))
                .anySatisfy(item -> assertThat(item.get("snippet").toString())
                        .contains("total < MINIMUM_AMOUNT", "throw new OrderRejectedException"));
        return evidence;
    }

    private Map<String, Object> node(Run run, String stableKey) {
        return db.one(
                "SELECT * FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND stable_key=?",
                org,
                project,
                run.id(),
                stableKey);
    }

    private JsonNode rawRule(Run run, String stableKey) {
        return mapper.valueToTree(db.one(
                        "SELECT payload FROM raw_fact WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND analyzer_execution_id=? AND stable_key=? AND kind='CONDITION'",
                        org,
                        project,
                        run.id(),
                        run.execution(),
                        stableKey)
                .get("payload"));
    }

    private static List<String> evidenceIds(List<Map<String, Object>> evidence) {
        return evidence.stream().map(item -> item.get("id").toString()).toList();
    }

    private void writeArtifact(Path path, Object value) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private static String logExcerpt(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            char[] text = new char[16_384];
            int length = reader.read(text);
            return length < 0 ? "" : new String(text, 0, length);
        }
    }

    private static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("analyzers/java-spring/pom.xml"))
                    && Files.isDirectory(path.resolve("fixtures/spring-order-service"))) return path;
        }
        throw new IllegalStateException("Run Maven from the Semantic Business Map reactor or one of its modules");
    }
}
