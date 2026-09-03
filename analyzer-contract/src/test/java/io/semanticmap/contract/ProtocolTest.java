package io.semanticmap.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void serializesTheStrictFactEnvelopeAndRoundTripsExtensibleProperties() throws Exception {
        var evidence = new Protocol.Evidence("src/Example.java", 1, 1, 1, 12, Protocol.hash("class X {}"));
        var fact = new Protocol.Fact(
                "1.0",
                Protocol.factId("file:src/Example.java"),
                "FILE",
                "file:src/Example.java",
                new Protocol.Subject("FILE", "file:src/Example.java"),
                Map.of("language", "JAVA"),
                "STATIC_SYNTAX",
                1.0,
                List.of(evidence));
        JsonNode tree = json.valueToTree(fact);
        Set<String> names = new TreeSet<>();
        tree.fieldNames().forEachRemaining(names::add);
        assertEquals(
                Set.of(
                        "contractVersion",
                        "factId",
                        "kind",
                        "stableKey",
                        "subject",
                        "properties",
                        "origin",
                        "confidence",
                        "evidence"),
                names);
        assertEquals(fact, json.readValue(json.writeValueAsBytes(fact), Protocol.Fact.class));
        assertEquals(2, tree.path("subject").size());
        assertEquals(6, tree.path("evidence").get(0).size());
    }

    @Test
    void hashesUtf8WithoutAnImplicitTrailingNewline() {
        assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Protocol.hash(""));
        assertNotEquals(Protocol.hash("first\nsecond"), Protocol.hash("first\nsecond\n"));
        assertEquals(64, Protocol.factId("stable").length());
    }

    @Test
    void packagedSchemaIncludesCrossAnalyzerOriginsAndExtensibleProperties() throws Exception {
        try (var source = getClass().getResourceAsStream("/schemas/v1/fact.schema.json")) {
            assertNotNull(source);
            JsonNode schema = json.readTree(source);
            List<String> origins = new ArrayList<>();
            schema.path("properties").path("origin").path("enum").forEach(n -> origins.add(n.asText()));
            assertTrue(origins.containsAll(
                    List.of("STATIC_SYNTAX", "STATIC_TYPED", "FRAMEWORK_DERIVED", "DATABASE_DERIVED")));
            assertFalse(origins.contains("STATIC_INFERRED"));
            assertFalse(schema.path("additionalProperties").asBoolean());
            assertTrue(schema.path("properties")
                    .path("properties")
                    .path("additionalProperties")
                    .asBoolean());
            assertFalse(schema.path("properties").path("properties").has("required"));
        }
    }
}
