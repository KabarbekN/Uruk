package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.graph.internal.SemanticDiffer;
import io.semanticmap.platform.graph.internal.Semantics;
import io.semanticmap.platform.shared.Db;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SemanticEdgeDiffTest {
    private static final UUID ORG = UUID.randomUUID(), PROJECT = UUID.randomUUID();
    private static final UUID BEFORE = UUID.randomUUID(), AFTER = UUID.randomUUID();

    @Test
    void capturesRelationshipBehaviorConfidenceAndSourceProofChanges() {
        var db = new EdgeData(edge("USER", .9), edge("ADMIN", .7), true);
        new SemanticDiffer(db).diff(ORG, PROJECT, BEFORE, AFTER);
        assertThat(db.changes)
                .extracting(change -> change.get("impactType"))
                .containsExactlyInAnyOrder("ROLE_CHANGED", "CONFIDENCE_CHANGED", "SOURCE_EVIDENCE_CHANGED");
        for (var change : db.changes) {
            assertThat(change).containsEntry("confidence", .7);
            var before = Semantics.map(change.get("before"));
            var after = Semantics.map(change.get("after"));
            assertThat(Semantics.map(before.get("properties"))).containsEntry("roles", List.of("USER"));
            assertThat(Semantics.map(after.get("properties"))).containsEntry("roles", List.of("ADMIN"));
            assertThat(Semantics.list(change.get("beforeEvidenceIds")))
                    .isNotEmpty()
                    .isNotEqualTo(Semantics.list(change.get("afterEvidenceIds")));
        }
    }

    @Test
    void identicalRelationshipUsesProofContentsInsteadOfRevisionLocalIds() {
        var before = edge("USER", .9);
        var after = edge("USER", .9);
        assertThat(before.get("id")).isNotEqualTo(after.get("id"));
        assertThat(before.get("evidenceIds")).isNotEqualTo(after.get("evidenceIds"));
        var db = new EdgeData(before, after, false);
        new SemanticDiffer(db).diff(ORG, PROJECT, BEFORE, AFTER);
        assertThat(db.changes).isEmpty();
    }

    @Test
    void addedRelationshipKeepsItsBehaviorInTheReviewableSnapshot() {
        var db = new EdgeData(null, edge("ADMIN", .8), false);
        new SemanticDiffer(db).diff(ORG, PROJECT, BEFORE, AFTER);
        assertThat(db.changes).hasSize(1);
        assertThat(db.changes.getFirst()).containsEntry("changeType", "ADDED").containsEntry("before", null);
        var snapshot = Semantics.map(db.changes.getFirst().get("after"));
        assertThat(Semantics.map(snapshot.get("properties")))
                .containsEntry("roles", List.of("ADMIN"))
                .containsEntry("sourceKey", "endpoint:create")
                .containsEntry("targetKey", "method:create");
    }

    private static Map<String, Object> edge(String role, double confidence) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", UUID.randomUUID());
        result.put("sourceKey", "endpoint:create");
        result.put("targetKey", "method:create");
        result.put("kind", "CALLS");
        result.put("label", "CALLS");
        result.put("confidence", confidence);
        result.put("properties", Map.of("roles", List.of(role)));
        result.put("evidenceIds", List.of(UUID.randomUUID().toString()));
        return result;
    }

    // Persistence boundary fixture: production matching, comparison and snapshots execute unchanged.
    private static final class EdgeData extends Db {
        private final Map<String, Object> before, after;
        private final boolean sourceChanged;
        private final List<Map<String, Object>> changes = new ArrayList<>();

        EdgeData(Map<String, Object> before, Map<String, Object> after, boolean sourceChanged) {
            super(null, new ObjectMapper());
            this.before = before;
            this.after = after;
            this.sourceChanged = sourceChanged;
        }

        @Override
        public Map<String, Object> one(String sql, Object... args) {
            return Map.of("id", args[0], "baselineRunId", BEFORE);
        }

        @Override
        public List<Map<String, Object>> rows(String sql, Object... args) {
            if (sql.startsWith("SELECT * FROM semantic_node")) return List.of();
            if (sql.startsWith("SELECT e.*")) {
                var edge = BEFORE.equals(args[2]) ? before : after;
                if (edge == null || args.length == 4 && !new UUID(0, 0).equals(args[3])) return List.of();
                return List.of(edge);
            }
            if (sql.startsWith("SELECT DISTINCT snippet_hash"))
                return List.of(Map.of("snippetHash", sourceChanged && AFTER.equals(args[2]) ? "changed" : "same"));
            throw new AssertionError("Unexpected read: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            if (!sql.startsWith("INSERT INTO semantic_change")) throw new AssertionError("Unexpected write: " + sql);
            var change = new LinkedHashMap<String, Object>();
            change.put("changeType", args[5]);
            change.put("impactType", args[7]);
            change.put(
                    "before",
                    parse(args[8].toString()).isNull() ? null : Semantics.canonical(parse(args[8].toString())));
            change.put("after", Semantics.canonical(parse(args[9].toString())));
            change.put("beforeEvidenceIds", Semantics.canonical(parse(args[10].toString())));
            change.put("afterEvidenceIds", Semantics.canonical(parse(args[11].toString())));
            change.put("confidence", args[12]);
            changes.add(change);
            return 1;
        }
    }
}
