package io.semanticmap.analyzer.java;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import io.semanticmap.contract.Protocol;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SpringAnalyzerTest {
    @TempDir
    static Path temporary;

    static Path fixture;
    static Path baselineOutput;
    static Path revisionOutput;
    static List<Protocol.Fact> baseline;
    static List<Protocol.Fact> revision;
    static final String CREATE = "java:method:example.orders.OrderService#createOrder(example.orders.OrderRequest)";

    @BeforeAll
    static void analyzeGoldenRevisions() throws Exception {
        Path repo = Path.of("").toAbsolutePath();
        while (repo != null && !Files.isDirectory(repo.resolve("fixtures"))) repo = repo.getParent();
        assertNotNull(repo, "Fixture repository root");
        fixture = repo.resolve("fixtures/spring-order-service");
        baselineOutput = temporary.resolve("baseline");
        revisionOutput = temporary.resolve("revision");
        assertEquals(10, run(fixture.resolve("request.json"), baselineOutput, fixture));
        Path second = repo.resolve("fixtures/spring-order-service-revision2");
        assertEquals(10, run(second.resolve("request.json"), revisionOutput, second));
        baseline = facts(baselineOutput);
        revision = facts(revisionOutput);
    }

    @Test
    void composesControllerPathsAndCarriesEndpointDetails() {
        Protocol.Fact endpoint = fact(baseline, "spring:endpoint:POST:/api/orders");
        assertEquals("POST", endpoint.properties().get("httpMethod"));
        assertEquals("example.orders.OrderRequest", endpoint.properties().get("requestBodyType"));
        assertEquals("example.orders.Order", endpoint.properties().get("responseType"));
        assertTrue(endpoint.properties().get("declaredStatus").toString().contains("CREATED"));
        assertTrue(edge(baseline, endpoint.stableKey(), "ENTRY_TO"));
        assertFalse(baseline.stream()
                .anyMatch(f -> f.kind().equals("ENDPOINT") && f.stableKey().contains("/charge")));
    }

    @Test
    void keepsThresholdRulesStableAndResolvesSourceConstants() {
        String key = "rule:" + CREATE + ":if:0";
        Protocol.Fact before = fact(baseline, key), after = fact(revision, key);
        JsonNode a = SpringAnalyzerCli.JSON.valueToTree(before.properties().get("condition"));
        JsonNode b = SpringAnalyzerCli.JSON.valueToTree(after.properties().get("condition"));
        assertEquals("LESS_THAN", a.path("operator").asText());
        assertEquals(500, a.path("right").path("value").asLong());
        assertEquals(300, b.path("right").path("value").asLong());
        assertEquals(before.factId(), after.factId());
        assertEquals(2, before.evidence().size(), "Resolved constants require declaration evidence");
        assertNotEquals(
                before.evidence().get(1).snippetHash(), after.evidence().get(1).snippetHash());
        JsonNode outcomes =
                SpringAnalyzerCli.JSON.valueToTree(before.properties().get("trueOutcomes"));
        assertEquals("THROW", outcomes.get(0).path("kind").asText());
        assertEquals("OrderRejectedException", outcomes.get(0).path("exception").asText());
        assertEquals(
                "CONTINUE",
                SpringAnalyzerCli.JSON
                        .valueToTree(before.properties().get("falseOutcomes"))
                        .get(0)
                        .path("kind")
                        .asText());
    }

    @Test
    void extractsRecordValidationAndSpelRoles() {
        Protocol.Fact validation = fact(
                baseline, "validation:java:type:example.orders.OrderRequest#amount:jakarta.validation.constraints.Min");
        assertEquals(500, ((Number) validation.properties().get("value")).intValue());
        assertEquals(
                validation.stableKey(), fact(revision, validation.stableKey()).stableKey());
        Protocol.Fact authorization = baseline.stream()
                .filter(f -> f.kind().equals("AUTHORIZATION_RULE"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("PREMIUM"), authorization.properties().get("roles"));
        JsonNode condition =
                SpringAnalyzerCli.JSON.valueToTree(authorization.properties().get("condition"));
        assertEquals("OpOr", condition.path("type").asText());
        assertEquals(2, condition.path("children").size());
        assertTrue(condition.toString().contains("canCreate"));
    }

    @Test
    void extractsOrmMappingsWritesReadsAndSideEffects() {
        assertNotNull(fact(baseline, "db:table:public.orders"));
        assertEquals(
                false,
                fact(baseline, "db:column:public.orders.status").properties().get("insertable"));
        assertTrue(fact(baseline, "db:column:public.orders.created_at")
                .properties()
                .get("columnDefinition")
                .toString()
                .contains("DEFAULT now()"));
        assertTrue(baseline.stream()
                .anyMatch(f -> f.kind().equals("DATA_WRITE")
                        && f.properties().get("operation").equals("save")
                        && Boolean.FALSE.equals(f.properties().get("exactSql"))));
        assertEquals(
                1,
                baseline.stream().filter(f -> f.kind().equals("DATA_WRITE")).count(),
                "Mock setup must not become a database write");
        assertTrue(baseline.stream()
                .filter(f -> Set.of("DATA_WRITE", "DATA_READ", "SIDE_EFFECT").contains(f.kind()))
                .noneMatch(f -> f.evidence().stream().anyMatch(e -> e.filePath().contains("src/test/"))));
        assertTrue(baseline.stream()
                .anyMatch(f -> f.kind().equals("DATA_READ")
                        && f.properties().get("operation").equals("findByStatus")));
        assertTrue(baseline.stream()
                .anyMatch(f -> f.kind().equals("SIDE_EFFECT")
                        && f.properties().get("effectKind").equals("EXTERNAL_CALL")));
        assertTrue(baseline.stream()
                .anyMatch(f -> f.kind().equals("SIDE_EFFECT")
                        && f.properties().get("effectKind").equals("EVENT_PUBLICATION")));
        assertTrue(edge(baseline, CREATE, "VERIFIED_BY"));
        assertEquals(2, baseline.stream().filter(f -> f.kind().equals("TEST")).count());
    }

    @Test
    void allEvidenceMatchesOriginalFullLinesAndSchema() throws Exception {
        JsonSchema factSchema = schema("fact");
        for (Protocol.Fact fact : baseline) {
            assertTrue(
                    factSchema
                            .validate(SpringAnalyzerCli.JSON.valueToTree(fact))
                            .isEmpty(),
                    fact.stableKey());
            assertTrue(fact.properties().containsKey("name"), fact.stableKey());
            assertTrue(fact.properties().containsKey("ownerKey"), fact.stableKey());
            for (var evidence : fact.evidence()) {
                Path source = fixture.resolve(evidence.filePath()).normalize();
                assertTrue(source.startsWith(fixture));
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                assertTrue(evidence.startLine() > 0 && evidence.endLine() <= lines.size(), evidence.toString());
                assertTrue(evidence.startLine() <= evidence.endLine());
                assertTrue(evidence.startColumn()
                        <= lines.get(evidence.startLine() - 1).length() + 1);
                assertTrue(evidence.endColumn()
                        <= lines.get(evidence.endLine() - 1).length() + 1);
                assertEquals(
                        Protocol.hash(String.join("\n", lines.subList(evidence.startLine() - 1, evidence.endLine()))),
                        evidence.snippetHash(),
                        fact.stableKey());
            }
        }
        for (String name : List.of("manifest", "coverage", "statistics")) {
            assertTrue(
                    schema(name)
                            .validate(SpringAnalyzerCli.JSON.readTree(
                                    baselineOutput.resolve(name + ".json").toFile()))
                            .isEmpty(),
                    name);
        }
        for (String line : Files.readAllLines(baselineOutput.resolve("diagnostics.ndjson")))
            assertTrue(
                    schema("diagnostic")
                            .validate(SpringAnalyzerCli.JSON.readTree(line))
                            .isEmpty(),
                    line);
    }

    @Test
    void reportsUnresolvedCallsWithoutDanglingCallEdges() throws Exception {
        String diagnostics = Files.readString(baselineOutput.resolve("diagnostics.ndjson"));
        assertTrue(diagnostics.contains("UNRESOLVED_METHOD_TARGET"));
        Set<String> keys = new HashSet<>(baseline.stream()
                .filter(f -> !f.kind().equals("RELATION"))
                .map(Protocol.Fact::stableKey)
                .toList());
        for (Protocol.Fact fact : baseline)
            if ("CALLS".equals(fact.properties().get("edgeKind"))) {
                assertTrue(keys.contains(fact.properties().get("targetKey")), fact.stableKey());
                assertFalse(fact.properties().get("targetKey").toString().contains("#save("));
            }
    }

    @Test
    void emitsDeterministicFactsAndAdmitsPartialCoverage() throws Exception {
        Path again = temporary.resolve("repeat");
        assertEquals(10, run(fixture.resolve("request.json"), again, fixture));
        assertEquals(
                Files.readString(baselineOutput.resolve("facts.ndjson")),
                Files.readString(again.resolve("facts.ndjson")));
        assertEquals(
                Files.readString(baselineOutput.resolve("diagnostics.ndjson")),
                Files.readString(again.resolve("diagnostics.ndjson")));
        JsonNode manifest = SpringAnalyzerCli.JSON.readTree(
                baselineOutput.resolve("manifest.json").toFile());
        assertEquals("PARTIAL", manifest.path("status").asText());
        assertTrue(manifest.path("analyzer").path("imageDigest").isNull());
        assertEquals(baseline.size(), manifest.path("factCount").asInt());
    }

    @Test
    void acceptsAllAnalyzerOriginsAndExtensibleFactProperties() throws Exception {
        JsonSchema schema = schema("fact");
        ObjectNode fact = SpringAnalyzerCli.JSON.valueToTree(baseline.getFirst());
        fact.set("properties", SpringAnalyzerCli.JSON.createObjectNode().put("languageSpecific", "allowed"));
        for (String origin : List.of(
                "STATIC_EXACT",
                "STATIC_TYPED",
                "STATIC_SYNTAX",
                "FRAMEWORK_DERIVED",
                "DATABASE_DERIVED",
                "RUNTIME_OBSERVED",
                "HEURISTIC")) {
            fact.put("origin", origin);
            assertTrue(schema.validate(fact).isEmpty(), origin);
        }
        for (String origin : List.of("STATIC_RESOLVED", "STATIC_INFERRED", "LLM_ENRICHED", "HUMAN_CONFIRMED")) {
            fact.put("origin", origin);
            assertFalse(schema.validate(fact).isEmpty(), origin);
        }
        fact.put("origin", "STATIC_SYNTAX");
        fact.put("unknownOuterProperty", true);
        assertFalse(schema.validate(fact).isEmpty());
        fact.remove("unknownOuterProperty");
        ((ObjectNode) fact.path("subject")).put("unknown", true);
        assertFalse(schema.validate(fact).isEmpty());
        ((ObjectNode) fact.path("subject")).remove("unknown");
        ((ObjectNode) fact.path("evidence").get(0)).put("unknown", true);
        assertFalse(schema.validate(fact).isEmpty());
    }

    @Test
    void requiresRelationEndpointsAndKind() throws Exception {
        ObjectNode fact = SpringAnalyzerCli.JSON.valueToTree(baseline.stream()
                .filter(f -> f.kind().equals("RELATION"))
                .findFirst()
                .orElseThrow());
        assertTrue(schema("fact").validate(fact).isEmpty());
        ((ObjectNode) fact.path("properties")).remove("targetKey");
        assertFalse(schema("fact").validate(fact).isEmpty());
    }

    @Test
    void rejectsMalformedUnknownAndMissingRequestProperties() throws Exception {
        assertEquals(20, rawRequest("{broken", "broken"));
        assertEquals(20, rawRequest(Files.readString(fixture.resolve("request.json")) + " {}", "trailing"));
        ObjectNode request = request();
        request.put("unexpected", true);
        assertEquals(20, request(request, "unknown"));
        request.remove("unexpected");
        request.remove("policy");
        assertEquals(20, request(request, "missing"));
        request = request();
        ((ObjectNode) request.path("policy")).put("includeTests", "yes");
        assertEquals(20, request(request, "wrong-type"));
        request = request();
        ((ObjectNode) request.path("revision")).put("unexpected", "no");
        assertEquals(20, request(request, "nested-unknown"));
    }

    @Test
    void rejectsTraversalUnsafePoliciesAndOutputLimit() throws Exception {
        ObjectNode request = request();
        ((ObjectNode) request.path("component")).put("rootPath", "../escape");
        assertEquals(50, request(request, "escape"));
        request = request();
        ((ObjectNode) request.path("policy")).put("executeBuildScripts", true);
        assertEquals(50, request(request, "build"));
        request = request();
        ((ObjectNode) request.path("policy")).put("maxOutputBytes", 1);
        assertEquals(50, request(request, "limit"));
    }

    @Test
    void handlesCrLfDuplicateConditionsAndMalformedJava() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("mixed-source"));
        Files.writeString(
                workspace.resolve("Example.java"),
                "class Example {\r\n    int m(int n) {\r\n        // repeated conditions must have distinct structural slots\r\n        if (n < 500) return 1;\r\n        if (n < 500) return 2;\r\n        return n > 9 ? 3 : 4;\r\n    }\r\n}\r\n");
        Files.writeString(workspace.resolve("Broken.java"), "class Broken { void fail( }");
        ObjectNode request = request();
        Path input = temporary.resolve("mixed.json");
        SpringAnalyzerCli.JSON.writeValue(input.toFile(), request);
        Path output = temporary.resolve("mixed-output");
        assertEquals(10, run(input, output, workspace));
        List<Protocol.Fact> conditions =
                facts(output).stream().filter(f -> f.kind().equals("CONDITION")).toList();
        assertEquals(3, conditions.size());
        assertEquals(
                3, conditions.stream().map(Protocol.Fact::stableKey).distinct().count());
        for (var condition : conditions)
            for (var evidence : condition.evidence()) {
                var lines = Files.readAllLines(workspace.resolve(evidence.filePath()));
                assertEquals(
                        Protocol.hash(String.join("\n", lines.subList(evidence.startLine() - 1, evidence.endLine()))),
                        evidence.snippetHash());
            }
        assertTrue(Files.readString(output.resolve("diagnostics.ndjson")).contains("JAVA_PARSE_ERROR"));
    }

    @Test
    void respectsTestExclusionAndDescribesCapabilities() throws Exception {
        ObjectNode request = request();
        ((ObjectNode) request.path("policy")).put("includeTests", false);
        assertEquals(10, request(request, "no-tests"));
        assertTrue(facts(temporary.resolve("no-tests-output")).stream()
                .noneMatch(f -> f.kind().equals("TEST")
                        || f.evidence().stream().anyMatch(e -> e.filePath().contains("src/test/"))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, SpringAnalyzerCli.run(new String[] {"describe"}, fixture, new PrintStream(out), System.err));
        JsonNode description = SpringAnalyzerCli.JSON.readTree(out.toByteArray());
        assertTrue(schema("describe").validate(description).isEmpty());
        assertEquals("SAFE_STATIC", description.path("executionMode").asText());
    }

    @Test
    void doesNotInventOverloadTargetsForInvalidOrAmbiguousCalls() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("ambiguous-source"));
        Files.writeString(
                workspace.resolve("Calls.java"),
                """
                class Calls {
                    void pick(String text) {}
                    void pick(Integer number) {}
                    void onlyText(String text) {}
                    void run() { pick(null); onlyText(1); missing.doWork(); }
                }
                """);
        Path input = temporary.resolve("ambiguous.json");
        SpringAnalyzerCli.JSON.writeValue(input.toFile(), request());
        Path output = temporary.resolve("ambiguous-output");
        assertEquals(10, run(input, output, workspace));
        assertFalse(facts(output).stream()
                .anyMatch(f -> "CALLS".equals(f.properties().get("edgeKind"))
                        && f.properties().get("sourceKey").toString().contains("#run(")));
        assertTrue(Files.readString(output.resolve("diagnostics.ndjson")).contains("UNRESOLVED_METHOD_TARGET"));
    }

    @Test
    void preservesDistinctTrailingSlashMappings() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("route-source"));
        Files.writeString(
                workspace.resolve("Routes.java"),
                """
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                @RestController @RequestMapping({"/v1", "/v2"})
                class Routes {
                    @GetMapping({"/items", "/items/"})
                    String items() { return ""; }
                }
                """);
        Path input = temporary.resolve("routes.json");
        SpringAnalyzerCli.JSON.writeValue(input.toFile(), request());
        Path output = temporary.resolve("routes-output");
        assertEquals(10, run(input, output, workspace));
        Set<String> paths = new TreeSet<>();
        facts(output).stream()
                .filter(f -> f.kind().equals("ENDPOINT"))
                .forEach(f -> paths.add(f.properties().get("path").toString()));
        assertEquals(Set.of("/v1/items", "/v1/items/", "/v2/items", "/v2/items/"), paths);
    }

    @Test
    void repeatedCallEdgesRespectIngestionEvidenceLimitsWithExplicitCoverage() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("repeated-calls"));
        Files.writeString(
                workspace.resolve("Calls.java"),
                "class Calls { void target() {} void caller() {\n" + "target();\n".repeat(40) + "}}\n");
        Path input = temporary.resolve("repeated-calls.json");
        SpringAnalyzerCli.JSON.writeValue(input.toFile(), request());
        Path output = temporary.resolve("repeated-calls-output");
        assertEquals(10, run(input, output, workspace));
        List<Protocol.Fact> edges = facts(output).stream()
                .filter(fact -> "CALLS".equals(fact.properties().get("edgeKind")))
                .toList();
        assertEquals(1, edges.size());
        assertEquals(32, edges.getFirst().evidence().size());
        assertTrue(Files.readString(output.resolve("diagnostics.ndjson")).contains("RELATION_EVIDENCE_LIMIT"));
    }

    @Test
    void invalidSourceEncodingRetainsDiagnosticsEvenWithoutAValidJavaFile() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("invalid-encoding"));
        Files.write(workspace.resolve("Broken.java"), new byte[] {(byte) 0xff});
        Path input = temporary.resolve("invalid-encoding.json");
        SpringAnalyzerCli.JSON.writeValue(input.toFile(), request());
        Path output = temporary.resolve("invalid-encoding-output");
        assertEquals(10, run(input, output, workspace));
        assertTrue(facts(output).isEmpty());
        assertTrue(Files.readString(output.resolve("diagnostics.ndjson")).contains("INVALID_ENCODING"));
        JsonNode manifest =
                SpringAnalyzerCli.JSON.readTree(output.resolve("manifest.json").toFile());
        assertEquals("PARTIAL", manifest.path("status").asText());
    }

    static int run(Path input, Path output, Path workspace) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = SpringAnalyzerCli.run(
                new String[] {"analyze", "--request", input.toString(), "--output", output.toString()},
                workspace,
                System.out,
                new PrintStream(errors));
        if (code != 0 && code != 10) System.err.print(errors.toString(StandardCharsets.UTF_8));
        return code;
    }

    static List<Protocol.Fact> facts(Path output) throws IOException {
        List<Protocol.Fact> result = new ArrayList<>();
        for (String line : Files.readAllLines(output.resolve("facts.ndjson")))
            result.add(SpringAnalyzerCli.JSON.readValue(line, Protocol.Fact.class));
        return result;
    }

    static Protocol.Fact fact(List<Protocol.Fact> facts, String key) {
        return facts.stream()
                .filter(f -> f.stableKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing fact " + key));
    }

    static boolean edge(List<Protocol.Fact> facts, String source, String kind) {
        return facts.stream()
                .anyMatch(f -> source.equals(f.properties().get("sourceKey"))
                        && kind.equals(f.properties().get("edgeKind")));
    }

    static JsonSchema schema(String name) throws IOException {
        try (InputStream source =
                SpringAnalyzerTest.class.getResourceAsStream("/schemas/v1/" + name + ".schema.json")) {
            assertNotNull(source);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(source);
        }
    }

    static ObjectNode request() throws IOException {
        return (ObjectNode)
                SpringAnalyzerCli.JSON.readTree(fixture.resolve("request.json").toFile());
    }

    static int request(ObjectNode request, String name) throws IOException {
        return rawRequest(SpringAnalyzerCli.JSON.writeValueAsString(request), name);
    }

    static int rawRequest(String content, String name) throws IOException {
        Path input = temporary.resolve(name + ".json");
        Files.writeString(input, content);
        return run(input, temporary.resolve(name + "-output"), fixture);
    }
}
