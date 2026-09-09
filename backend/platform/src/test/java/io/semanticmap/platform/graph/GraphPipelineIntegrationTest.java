package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class GraphPipelineIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private UUID org, project, user;
    private Db db;
    private GraphPipeline pipeline;
    private GraphQueries queries;
    private Access access;
    private TenantContext tenant;
    private TransactionTemplate transactions;

    private record Run(UUID id, UUID revision, UUID component, Path workspace, int threshold) {}

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        db = new Db(new JdbcTemplate(source), mapper);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        org = UUID.randomUUID();
        project = UUID.randomUUID();
        user = UUID.randomUUID();
        db.update("INSERT INTO organization(id,name) VALUES (?,?)", org, "Graph tests");
        db.update(
                "INSERT INTO user_account(id,subject,display_name) VALUES (?,?,?)", user, user.toString(), "Reviewer");
        db.update("INSERT INTO project(id,organization_id,name) VALUES (?,?,?)", project, org, "Orders");
        tenant = mock(TenantContext.class);
        when(tenant.orgId()).thenReturn(org);
        when(tenant.userId()).thenReturn(user);
        when(tenant.hasRole("ORG_ADMIN")).thenReturn(true);
        access = new Access(db, tenant);
        queries = new GraphQueries(db, tenant, access, new SemanticDiffer(db));
        pipeline = new GraphPipeline(
                new FactIngestion(db, mapper),
                new GraphBuilder(db, new ScenarioBuilder(db, 3, 5)),
                new SemanticDiffer(db));
    }

    @Test
    void preservesValidPartialFactsAndReturnsEvidenceBackedBoundedViews() throws Exception {
        var run = run(500, null);
        var facts = facts(run);
        UUID execution = execution(run, "java-spring");
        var lines = new ArrayList<>(facts.stream().map(db::json).toList());
        lines.add("{bad json}");
        lines.add("x".repeat(1024 * 1024 + 1));
        lines.add(db.json(new Protocol.Fact(
                "1.0",
                "wrong",
                "METHOD",
                "wrong",
                new Protocol.Subject("METHOD", "wrong"),
                Map.of("name", "wrong", "ownerKey", ""),
                "STATIC_EXACT",
                1,
                List.of(new Protocol.Evidence("Source.java", 2, 1, 2, 2, Protocol.hash("wrong"))))));
        lines.add(db.json(facts.getFirst()));
        ingest(run, execution, String.join("\n", lines));
        assertThat(count("raw_fact", run.id())).isEqualTo(facts.size());
        assertThat(count("fact_quarantine", run.id())).isEqualTo(3);
        build(run);
        int nodes = count("semantic_node", run.id());
        int edges = count("semantic_edge", run.id());
        int assertions = count("assertion", run.id());
        int scenarios = count("business_scenario", run.id());
        UUID scenarioNode = (UUID) db.one(
                        "SELECT node_id FROM business_scenario WHERE organization_id=? AND project_id=? AND analysis_run_id=?",
                        org,
                        project,
                        run.id())
                .get("nodeId");
        build(run);
        assertThat(count("semantic_node", run.id())).isEqualTo(nodes);
        assertThat(count("semantic_edge", run.id())).isEqualTo(edges);
        assertThat(count("assertion", run.id())).isEqualTo(assertions);
        assertThat(count("business_scenario", run.id())).isEqualTo(scenarios);
        assertThat(db.one(
                                "SELECT node_id FROM business_scenario WHERE organization_id=? AND project_id=? AND analysis_run_id=?",
                                org,
                                project,
                                run.id())
                        .get("nodeId"))
                .isEqualTo(scenarioNode);
        var canvas = queries.canvas(run.id(), GraphQueries.View.BUSINESS, 2, null, 0, "", false, 2);
        assertThat(Semantics.list(canvas.get("nodes")))
                .noneMatch(n -> "TECHNICAL_GUARD".equals(Semantics.map(n).get("kind")));
        assertThat(queries.unresolved(run.id(), 50)).hasSize(1);
        for (GraphQueries.View view : GraphQueries.View.values())
            queries.canvas(run.id(), view, 2, null, 0, "", false, 3);
        var evidence = queries.nodeEvidence(node(run, "rule:minimum"));
        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst())
                .containsEntry("verified", true)
                .containsEntry("sourceAvailable", true)
                .containsEntry("snippetHash", Protocol.hash(line(run.threshold())));
        var scenario = db.one(
                "SELECT model FROM business_scenario WHERE organization_id=? AND project_id=? AND analysis_run_id=?",
                org,
                project,
                run.id());
        assertThat(Semantics.map(scenario.get("model"))).containsEntry("pathOrderKnown", false);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> pipeline.ingest(
                        org,
                        project,
                        run.revision(),
                        run.id(),
                        execution,
                        run.workspace(),
                        run.workspace().resolve("facts.ndjson"))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void thresholdDiffKeepsBothEvidenceChainsAndStalesReviewWithoutChangingFacts() throws Exception {
        var before = analyzed(500, null);
        UUID oldNode = node(before, "rule:minimum");
        var reviews = new ReviewController(queries, db, tenant, new Audit(db), access);
        transactions.executeWithoutResult(status -> reviews.review(
                oldNode,
                new ReviewController.ReviewRequest(
                        "EDITED", "Checked source", "Minimum order", "Reviewed wording", null)));
        assertThat(queries.node(oldNode)).containsEntry("label", "Minimum order");
        var identical = analyzed(500, before.id());
        diff(before, identical);
        assertThat(queries.node(node(identical, "rule:minimum")))
                .containsEntry("reviewStatus", "EDITED")
                .containsEntry("label", "Minimum order");
        var after = analyzed(300, identical.id());
        diff(identical, after);
        assertThat(queries.node(node(after, "rule:minimum"))).containsEntry("reviewStatus", "STALE");
        var threshold = queries.diff(project, identical.id(), after.id()).stream()
                .filter(c -> c.get("impactType").equals("THRESHOLD_CHANGED"))
                .findFirst()
                .orElseThrow();
        assertThat(Semantics.list(threshold.get("beforeEvidenceIds"))).isNotEmpty();
        assertThat(Semantics.strings(threshold.get("afterEvidenceIds")))
                .isNotEmpty()
                .doesNotContainAnyElementsOf(Semantics.strings(threshold.get("beforeEvidenceIds")));
        assertThat(db.json(threshold.get("before"))).contains("500");
        assertThat(db.json(threshold.get("after"))).contains("300");
        assertThat(queries.nodeEvidence(oldNode).getFirst().get("snippet")).isEqualTo(line(500));
        int changes = db.rows(
                        "SELECT id FROM semantic_change WHERE organization_id=? AND project_id=? AND from_analysis_run_id=? AND to_analysis_run_id=?",
                        org,
                        project,
                        identical.id(),
                        after.id())
                .size();
        diff(identical, after);
        assertThat(queries.diff(project, identical.id(), after.id())).hasSize(changes);
    }

    @Test
    void layoutCarriesStablePositionsAndIsIsolatedPerUser() throws Exception {
        var before = analyzed(500, null);
        var after = analyzed(500, before.id());
        var layouts = new CanvasLayoutController(db, tenant, access);
        var layout = new CanvasLayoutController.Layout(
                Map.of("rule:minimum", new CanvasLayoutController.Position(17, 23)),
                new CanvasLayoutController.Viewport(1, 2, 1.5),
                Set.of("rule:minimum"));
        transactions.executeWithoutResult(status -> layouts.put(before.id(), GraphQueries.View.BUSINESS, layout));
        assertThat(Semantics.map(
                        layouts.get(after.id(), GraphQueries.View.BUSINESS).get("positions")))
                .containsKey("rule:minimum");
        assertThat(layouts.get(after.id(), GraphQueries.View.BUSINESS))
                .containsEntry("pinnedStableKeys", List.of("rule:minimum"));
        transactions.executeWithoutResult(status -> layouts.put(
                after.id(),
                GraphQueries.View.BUSINESS,
                new CanvasLayoutController.Layout(layout.positions(), layout.viewport())));
        assertThat(layouts.get(after.id(), GraphQueries.View.BUSINESS)).containsEntry("pinnedStableKeys", List.of());
        assertThat(layouts.get(before.id(), GraphQueries.View.BUSINESS))
                .containsEntry("pinnedStableKeys", List.of("rule:minimum"));
        when(tenant.userId()).thenReturn(UUID.randomUUID());
        assertThat(Semantics.map(
                        layouts.get(after.id(), GraphQueries.View.BUSINESS).get("positions")))
                .isEmpty();
        assertThat(layouts.get(after.id(), GraphQueries.View.BUSINESS)).containsEntry("pinnedStableKeys", List.of());
    }

    @Test
    void collisionsAreDeterministicAcrossExecutionAndFactOrder() throws Exception {
        var first = run(500, null);
        var second = run(500, null);
        for (var run : List.of(first, second)) {
            var preferred = facts(run);
            var syntax = new Protocol.Fact(
                    "1.0",
                    "syntax-min",
                    "CONDITION",
                    "rule:minimum",
                    new Protocol.Subject("METHOD", "method:create"),
                    Map.of(
                            "name",
                            "syntax condition",
                            "ownerKey",
                            "method:create",
                            "condition",
                            Map.of("type", "UNKNOWN_EXPRESSION", "source", "total < minimum")),
                    "STATIC_SYNTAX",
                    .8,
                    List.of(evidence(run, 2)));
            if (run == first) {
                ingest(run, execution(run, "tree-sitter"), db.json(syntax));
                ingest(run, execution(run, "java-spring"), ndjson(preferred));
            } else {
                var reversed = new ArrayList<>(preferred);
                java.util.Collections.reverse(reversed);
                ingest(run, execution(run, "java-spring"), ndjson(reversed));
                ingest(run, execution(run, "tree-sitter"), db.json(syntax));
            }
            assertThat(count("raw_fact", run.id())).isEqualTo(preferred.size() + 1);
            assertThat(count("fact_quarantine", run.id())).isZero();
            build(run);
        }
        var a = queries.scopedNode(node(first, "rule:minimum"));
        var b = queries.scopedNode(node(second, "rule:minimum"));
        assertThat(a.get("fingerprint")).isEqualTo(b.get("fingerprint"));
        assertThat(a.get("properties")).isEqualTo(b.get("properties"));
        assertThat(Semantics.map(a.get("properties"))).containsEntry("factConflict", true);
        assertThat(a).containsEntry("kind", "BUSINESS_RULE");
    }

    @Test
    void realQueriesDenyOtherTenantAndMissingProjectMembership() throws Exception {
        var run = analyzed(500, null);
        UUID node = node(run, "rule:minimum");
        when(tenant.orgId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> queries.nodeEvidence(node)).isInstanceOf(ResponseStatusException.class);
        when(tenant.orgId()).thenReturn(org);
        when(tenant.hasRole("ORG_ADMIN")).thenReturn(false);
        assertThatThrownBy(() -> queries.nodeEvidence(node)).isInstanceOf(ResponseStatusException.class);
        db.update(
                "INSERT INTO project_member(organization_id,project_id,user_id,role) VALUES (?,?,?,'VIEWER')",
                org,
                project,
                user);
        assertThat(queries.nodeEvidence(node)).hasSize(1);
        assertThatThrownBy(() -> new ReviewController(queries, db, tenant, new Audit(db), access)
                        .review(node, new ReviewController.ReviewRequest("CONFIRMED", null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void businessProjectionExcludesSyntaxFactsButDeveloperRetainsThem() throws Exception {
        var run = run(500, null);
        var facts = new ArrayList<>(facts(run));
        var technicalKinds = List.of("IMPORT", "ANNOTATION", "LITERAL", "ASSIGNMENT", "CALL", "TEST_CASE");
        for (String kind : technicalKinds) facts.add(fact(run, kind, "syntax:" + kind, "method:create", Map.of()));
        ingest(run, execution(run, "tree-sitter"), ndjson(facts));
        build(run);
        var business = queries.canvas(run.id(), GraphQueries.View.BUSINESS, 2, null, 0, "", false, 2);
        assertThat(Semantics.list(business.get("nodes")))
                .extracting(node -> Semantics.map(node).get("kind"))
                .contains("BUSINESS_RULE")
                .doesNotContainAnyElementsOf(technicalKinds);
        var developer = queries.canvas(run.id(), GraphQueries.View.DEVELOPER, 2, null, 0, "", false, 3);
        assertThat(Semantics.list(developer.get("nodes")))
                .extracting(node -> Semantics.map(node).get("kind"))
                .containsAll(technicalKinds);
    }

    @Test
    void projectsTenThousandNodesWithoutDumpingFullGraphAndDistinguishesExpiredSource() throws Exception {
        var run = analyzed(500, null);
        UUID source = node(run, "rule:minimum");
        db.update(
                "INSERT INTO semantic_node(id,organization_id,project_id,revision_id,analysis_run_id,stable_key,kind,name,label,subtitle,confidence,support_level,properties,source_fact_ids,evidence_ids,fingerprint,structural_fingerprint,evidence_fingerprint) SELECT md5(n.id::text || g.i::text)::uuid,n.organization_id,n.project_id,n.revision_id,n.analysis_run_id,'bulk:' || g.i,n.kind,'bulk:' || g.i,'bulk:' || g.i,n.subtitle,n.confidence,n.support_level,n.properties,n.source_fact_ids,n.evidence_ids,n.fingerprint,n.structural_fingerprint,n.evidence_fingerprint FROM semantic_node n CROSS JOIN generate_series(1,10000) g(i) WHERE n.id=? AND n.organization_id=? AND n.project_id=? AND n.analysis_run_id=?",
                source,
                org,
                project,
                run.id());
        var graph = queries.canvas(run.id(), GraphQueries.View.BUSINESS, 2, null, 0, "", false, 2);
        assertThat(Semantics.list(graph.get("nodes"))).hasSize(250);
        assertThat(graph).containsEntry("truncated", true);
        assertThat(((Number) graph.get("totalNodes")).intValue()).isGreaterThan(10000);
        assertThat(queries.search(project, run.id(), "bulk:9999", 100)).hasSize(1);
        var local = queries.neighbors(node(run, "endpoint:create"), 1);
        assertThat(Semantics.list(local.get("nodes"))).hasSizeLessThan(10);
        var evidence = queries.nodeEvidence(source).getFirst();
        db.update(
                "UPDATE evidence SET snippet='',source_available=false,expired_at=now() WHERE id=? AND organization_id=? AND project_id=? AND analysis_run_id=?",
                evidence.get("id"),
                org,
                project,
                run.id());
        assertThat(queries.nodeEvidence(source).getFirst())
                .containsEntry("sourceAvailable", false)
                .containsEntry("snippetHash", evidence.get("snippetHash"));
    }

    @Test
    void arbitraryRunComparisonComputesDiffWithoutCarryingReview() throws Exception {
        var before = analyzed(500, null);
        var after = analyzed(300, null);
        var reviews = new ReviewController(queries, db, tenant, new Audit(db), access);
        transactions.executeWithoutResult(status -> reviews.review(
                node(before, "rule:minimum"), new ReviewController.ReviewRequest("CONFIRMED", null, null, null, null)));
        var changes = transactions.execute(status -> queries.diff(project, before.id(), after.id()));
        assertThat(changes).anyMatch(change -> change.get("impactType").equals("THRESHOLD_CHANGED"));
        assertThat(queries.node(node(after, "rule:minimum"))).containsEntry("reviewStatus", "UNREVIEWED");
    }

    @Test
    void detectsChangedRelationshipPropertiesConfidenceAndVerifiedEvidence() throws Exception {
        var before = run(500, null);
        var after = run(500, before.id());
        for (var run : List.of(before, after)) {
            boolean changed = run == after;
            var relation = new Protocol.Fact(
                    "1.0",
                    "relationship",
                    "RELATION",
                    "relationship",
                    new Protocol.Subject("RELATION", "relationship"),
                    Map.of(
                            "sourceKey", "endpoint:create",
                            "targetKey", "method:create",
                            "edgeKind", "CALLS",
                            "roles", List.of(changed ? "ADMIN" : "USER")),
                    "STATIC_EXACT",
                    changed ? .7 : .9,
                    List.of(evidence(run, changed ? 3 : 2)));
            ingest(
                    run,
                    execution(run, "java-spring"),
                    ndjson(List.of(
                            fact(run, "ENDPOINT", "endpoint:create", "", Map.of()),
                            fact(run, "METHOD", "method:create", "", Map.of()),
                            relation)));
            build(run);
        }
        var changes = transactions.execute(status -> queries.diff(project, before.id(), after.id()));
        var relationships = changes.stream()
                .filter(change -> change.get("subjectStableKey").toString().startsWith("edge:"))
                .filter(change ->
                        "CALLS".equals(Semantics.map(change.get("after")).get("kind")))
                .toList();
        assertThat(relationships)
                .extracting(change -> change.get("impactType"))
                .containsExactlyInAnyOrder("ROLE_CHANGED", "CONFIDENCE_CHANGED", "SOURCE_EVIDENCE_CHANGED");
        for (var change : relationships) {
            assertThat(Semantics.map(Semantics.map(change.get("before")).get("properties")))
                    .containsEntry("roles", List.of("USER"));
            assertThat(Semantics.map(Semantics.map(change.get("after")).get("properties")))
                    .containsEntry("roles", List.of("ADMIN"));
            assertThat(change).containsEntry("confidence", .7);
            assertThat(Semantics.list(change.get("beforeEvidenceIds"))).isNotEmpty();
            assertThat(Semantics.list(change.get("afterEvidenceIds"))).isNotEmpty();
        }
        var repeated = transactions.execute(status -> queries.diff(project, before.id(), after.id()));
        assertThat(repeated).hasSameSizeAs(changes);
    }

    @Test
    void identicalRelationshipEvidenceIgnoresRevisionLocalIds() throws Exception {
        var before = analyzed(500, null);
        var after = analyzed(500, before.id());
        var changes = transactions.execute(status -> queries.diff(project, before.id(), after.id()));
        assertThat(changes)
                .noneMatch(change -> change.get("subjectStableKey").toString().startsWith("edge:"));
    }

    @Test
    void messagePublicationEndsTheSynchronousBusinessScenario() throws Exception {
        var run = run(500, null);
        ingest(
                run,
                execution(run, "java-spring"),
                ndjson(List.of(
                        fact(run, "ENDPOINT", "endpoint:create", "", Map.of()),
                        fact(run, "MESSAGE_PUBLICATION", "message:orders", "endpoint:create", Map.of()),
                        fact(run, "DATA_WRITE", "write:consumer", "message:orders", Map.of()))));
        build(run);
        var scenario = db.one("SELECT model FROM business_scenario WHERE analysis_run_id=?", run.id());
        var model = Semantics.map(scenario.get("model"));
        assertThat(Semantics.map(model.get("membersByKind")))
                .containsKeys("ENDPOINT", "MESSAGE_PUBLICATION")
                .doesNotContainKey("DATABASE_WRITE");
        assertThat(Semantics.strings(model.get("asyncBoundaries")))
                .containsExactly(node(run, "message:orders").toString());
        assertThat(model).containsEntry("truncated", false);
    }

    @Test
    void exhaustedRelationshipBudgetReportsTruncationEvenWithDuplicateTargets() throws Exception {
        var run = run(500, null);
        var facts = new ArrayList<>(List.of(
                fact(run, "ENDPOINT", "endpoint:create", "", Map.of()),
                fact(run, "METHOD", "a:method", "endpoint:create", Map.of()),
                fact(run, "DATA_WRITE", "z:write", "endpoint:create", Map.of())));
        for (String kind : List.of("CALLS", "TRIGGERS", "PRECEDES", "ENTRY_TO", "VALIDATES"))
            facts.add(fact(
                    run,
                    "RELATION",
                    "relationship:" + kind,
                    "",
                    Map.of("sourceKey", "endpoint:create", "targetKey", "a:method", "edgeKind", kind)));
        ingest(run, execution(run, "java-spring"), ndjson(facts));
        build(run);
        var scenario = db.one("SELECT model,truncated FROM business_scenario WHERE analysis_run_id=?", run.id());
        assertThat(scenario).containsEntry("truncated", true);
        assertThat(Semantics.map(scenario.get("model"))).containsEntry("truncated", true);
    }

    @Test
    void filtersUseActualAnalyzerComponentFrameworkAndSourceLayer() throws Exception {
        var run = run(500, null);
        db.update(
                "UPDATE technology_component SET frameworks='[\"SPRING_BOOT\"]'::jsonb WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=?",
                org,
                project,
                run.id(),
                run.component());
        ingest(run, execution(run, "java-spring"), ndjson(facts(run)));
        build(run);
        var filtered = queries.canvas(
                run.id(),
                GraphQueries.View.DEVELOPER,
                2,
                null,
                0,
                "",
                false,
                3,
                new GraphQueries.Filters("java-spring", run.component().toString(), "SPRING_BOOT", "BACKEND"));
        assertThat(Semantics.list(filtered.get("nodes"))).isNotEmpty();
        var properties = Semantics.map(queries.node(node(run, "rule:minimum")).get("properties"));
        assertThat(properties)
                .containsEntry("analyzers", List.of("java-spring"))
                .containsEntry("origins", List.of("STATIC_EXACT"))
                .containsEntry("componentPaths", List.of("."))
                .containsEntry("sourceLayer", "BACKEND");
        var absent = queries.canvas(
                run.id(),
                GraphQueries.View.DEVELOPER,
                2,
                null,
                0,
                "",
                false,
                3,
                new GraphQueries.Filters("postgresql", null, null, null));
        assertThat(Semantics.list(absent.get("nodes"))).isEmpty();
    }

    private Run analyzed(int threshold, UUID baseline) throws Exception {
        var run = run(threshold, baseline);
        ingest(run, execution(run, "java-spring"), ndjson(facts(run)));
        build(run);
        return run;
    }

    private Run run(int threshold, UUID baseline) throws Exception {
        UUID revision = UUID.randomUUID(), id = UUID.randomUUID(), component = UUID.randomUUID();
        Path workspace = Files.createDirectory(temp.resolve(id.toString()));
        Files.writeString(
                workspace.resolve("Source.java"),
                "class Order {\n" + line(threshold)
                        + "\n  void guard(Object value) { if (value == null) return; }\n}\n");
        db.update(
                "INSERT INTO revision(id,organization_id,project_id,commit_sha,branch,fingerprint) VALUES (?,?,?,?,?,?)",
                revision,
                org,
                project,
                revision.toString(),
                "main",
                revision.toString());
        db.update(
                "INSERT INTO analysis_run(id,organization_id,project_id,revision_id,baseline_run_id,status,repository_url,requested_by,config_hash) VALUES (?,?,?,?,?,'SUCCEEDED','fixture',?,'test')",
                id,
                org,
                project,
                revision,
                baseline,
                user);
        db.update(
                "INSERT INTO technology_component(id,organization_id,project_id,revision_id,analysis_run_id,root_path,languages,frameworks,build_systems,database_technologies,evidence,fingerprint) VALUES (?,?,?,?,?,'.','[\"JAVA\"]','[]','[]','[]','[]','fixture')",
                component,
                org,
                project,
                revision,
                id);
        return new Run(id, revision, component, workspace, threshold);
    }

    private UUID execution(Run run, String analyzer) {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO analyzer_execution(id,organization_id,project_id,revision_id,analysis_run_id,component_id,analyzer_key,version,image_reference,image_digest,status,reason) VALUES (?,?,?,?,?,?,?,'0.1.0','fixture','sha256:fixture','SUCCEEDED','test')",
                id,
                org,
                project,
                run.revision(),
                run.id(),
                run.component(),
                analyzer);
        return id;
    }

    private List<Protocol.Fact> facts(Run run) {
        var nil = new java.util.LinkedHashMap<String, Object>();
        nil.put("type", "LITERAL");
        nil.put("value", null);
        return List.of(
                fact(run, "ENDPOINT", "endpoint:create", "", Map.of()),
                fact(run, "METHOD", "method:create", "endpoint:create", Map.of()),
                fact(
                        run,
                        "CONDITION",
                        "rule:minimum",
                        "method:create",
                        Map.of(
                                "name",
                                "if total < " + run.threshold(),
                                "condition",
                                Map.of(
                                        "type",
                                        "BINARY",
                                        "operator",
                                        "LESS_THAN",
                                        "left",
                                        Map.of("type", "REFERENCE", "name", "total"),
                                        "right",
                                        Map.of("type", "LITERAL", "value", run.threshold())),
                                "trueOutcomes",
                                List.of(Map.of("kind", "THROW", "exception", "DomainException")))),
                new Protocol.Fact(
                        "1.0",
                        "guard",
                        "CONDITION",
                        "rule:guard",
                        new Protocol.Subject("METHOD", "method:create"),
                        Map.of(
                                "name",
                                "null guard",
                                "ownerKey",
                                "method:create",
                                "condition",
                                Map.of(
                                        "type",
                                        "BINARY",
                                        "operator",
                                        "EQUAL",
                                        "left",
                                        Map.of("type", "REFERENCE", "name", "value"),
                                        "right",
                                        nil),
                                "trueOutcomes",
                                List.of(Map.of("kind", "RETURN", "expression", Map.of("type", "EMPTY")))),
                        "STATIC_EXACT",
                        1,
                        List.of(evidence(run, 3))),
                fact(
                        run,
                        "RELATION",
                        "relation:missing",
                        "",
                        Map.of("sourceKey", "method:create", "targetKey", "external:missing", "edgeKind", "CALLS")),
                fact(
                        run,
                        "RELATION",
                        "relation:cycle",
                        "",
                        Map.of("sourceKey", "method:create", "targetKey", "endpoint:create", "edgeKind", "CALLS")));
    }

    private Protocol.Fact fact(Run run, String kind, String key, String owner, Map<String, Object> extra) {
        var props = new java.util.LinkedHashMap<String, Object>();
        props.put("name", key);
        props.put("ownerKey", owner);
        props.putAll(extra);
        return new Protocol.Fact(
                "1.0",
                key,
                kind,
                key,
                new Protocol.Subject(kind, key),
                props,
                "STATIC_EXACT",
                1,
                List.of(evidence(run, 2)));
    }

    private Protocol.Evidence evidence(Run run, int line) {
        String source = line == 2 ? line(run.threshold()) : "  void guard(Object value) { if (value == null) return; }";
        return new Protocol.Evidence("Source.java", line, 1, line, source.length() + 1, Protocol.hash(source));
    }

    private static String line(int threshold) {
        return "  void create(int total) { if (total < " + threshold + ") throw new DomainException(); }";
    }

    private String ndjson(List<Protocol.Fact> facts) {
        return String.join("\n", facts.stream().map(db::json).toList());
    }

    private void ingest(Run run, UUID execution, String contents) throws Exception {
        Path output = run.workspace().resolve(execution + ".ndjson");
        Files.writeString(output, contents);
        transactions.executeWithoutResult(
                status -> pipeline.ingest(org, project, run.revision(), run.id(), execution, run.workspace(), output));
    }

    private void build(Run run) {
        transactions.executeWithoutResult(status -> pipeline.build(org, project, run.revision(), run.id()));
    }

    private void diff(Run before, Run after) {
        transactions.executeWithoutResult(status -> pipeline.diff(org, project, before.id(), after.id()));
    }

    private UUID node(Run run, String key) {
        return Semantics.uuid(db.one(
                        "SELECT id FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND stable_key=?",
                        org,
                        project,
                        run.id(),
                        key)
                .get("id"));
    }

    private int count(String table, UUID run) {
        return ((Number) db.one(
                                "SELECT count(*) AS total FROM " + table
                                        + " WHERE organization_id=? AND project_id=? AND analysis_run_id=?",
                                org,
                                project,
                                run)
                        .get("total"))
                .intValue();
    }
}
