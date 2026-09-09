package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.graph.internal.ScenarioBuilder;
import io.semanticmap.platform.graph.internal.Semantics;
import io.semanticmap.platform.shared.Db;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScenarioBuilderTest {
    @Test
    void messagePublicationIsAnAsyncBoundaryAndDoesNotAbsorbConsumerEffects() {
        var entry = node("ENDPOINT", "endpoint:create");
        var publication = node("MESSAGE_PUBLICATION", "message:orders");
        var write = node("DATABASE_WRITE", "write:consumer");
        var db = new ScenarioData(
                entry, Map.of(entry.get("id"), List.of(publication), publication.get("id"), List.of(write)));
        new ScenarioBuilder(db, 8, 120)
                .build(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(Semantics.map(db.model.get("membersByKind")))
                .containsKeys("ENDPOINT", "MESSAGE_PUBLICATION")
                .doesNotContainKey("DATABASE_WRITE");
        assertThat(db.model)
                .containsEntry("asyncBoundaries", List.of(publication.get("id").toString()))
                .containsEntry("truncated", false);
        assertThat(db.expanded).containsExactly(entry.get("id"));
    }

    @Test
    void relationshipBudgetExhaustionIsVisibleWhenManyEdgesPointToTheSameNode() {
        var entry = node("ENDPOINT", "endpoint:create");
        var method = node("METHOD", "method:create");
        var db = new ScenarioData(entry, Map.of(entry.get("id"), List.of(method, method, method, method)));
        new ScenarioBuilder(db, 8, 3).build(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(db.model).containsEntry("truncated", true);
        assertThat(Semantics.map(db.model.get("membersByKind"))).hasSize(2);
    }

    private static Map<String, Object> node(String kind, String key) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", UUID.randomUUID());
        result.put("kind", kind);
        result.put("stableKey", key);
        result.put("label", key);
        result.put("confidence", .9);
        result.put("relationshipConfidence", .9);
        result.put("properties", Map.of());
        result.put("evidenceIds", List.of(UUID.randomUUID().toString()));
        result.put("pathEvidence", List.of());
        result.put("sourceFactIds", List.of());
        result.put("fingerprint", "fingerprint:" + key);
        return result;
    }

    private static final class ScenarioData extends Db {
        private final Map<String, Object> entry;
        private final Map<Object, List<Map<String, Object>>> adjacent;
        private final List<Object> expanded = new ArrayList<>();
        private Map<String, Object> model;

        ScenarioData(Map<String, Object> entry, Map<Object, List<Map<String, Object>>> adjacent) {
            super(null, new ObjectMapper());
            this.entry = entry;
            this.adjacent = adjacent;
        }

        @Override
        public List<Map<String, Object>> rows(String sql, Object... args) {
            if (sql.startsWith("SELECT * FROM semantic_node"))
                return new UUID(0, 0).equals(args[3]) ? List.of(entry) : List.of();
            if (sql.startsWith("SELECT n.*,e.evidence_ids")) {
                expanded.add(args[3]);
                return adjacent.getOrDefault(args[3], List.of()).stream()
                        .limit(((Number) args[4]).longValue())
                        .toList();
            }
            if (sql.startsWith("SELECT id FROM semantic_node")) return List.of();
            if (sql.startsWith("SELECT DISTINCT snippet_hash")) return List.of(Map.of("snippetHash", "source"));
            throw new AssertionError("Unexpected read: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO business_scenario("))
                model = Semantics.map(Semantics.canonical(parse(args[6].toString())));
            return 1;
        }
    }
}
