package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.semanticmap.platform.graph.internal.GraphBuilder;
import io.semanticmap.platform.graph.internal.Semantics;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphMergeTest {
    @Test
    void preservesPreferredTypedPropertiesAndSortsConflictingAlternatives() {
        var typed = Map.<String, Object>of("name", "typed", "normalizedCondition", Map.of("right", 500));
        var syntax = Map.<String, Object>of("name", "syntax", "normalizedCondition", Map.of("right", 300));
        var heuristic = Map.<String, Object>of(
                "name", "heuristic", "normalizedCondition", Map.of("type", "UNKNOWN_EXPRESSION"));
        var first = GraphBuilder.mergeProperties(GraphBuilder.mergeProperties(typed, syntax), heuristic);
        var reversed = GraphBuilder.mergeProperties(GraphBuilder.mergeProperties(typed, heuristic), syntax);
        assertThat(Semantics.hash(first)).isEqualTo(Semantics.hash(reversed));
        assertThat(first).containsEntry("name", "typed").containsEntry("factConflict", true);
        assertThat((List<?>) first.get("factAlternatives")).hasSize(2);
        assertThat(Semantics.hash(GraphBuilder.mergeProperties(first, syntax))).isEqualTo(Semantics.hash(first));
    }

    @Test
    void identicalBehaviorFromOtherAnalyzerDoesNotFabricateConflict() {
        var first = Map.<String, Object>of("name", "Order", "ownerKey", "module", "type", "entity");
        var second = Map.<String, Object>of("name", "Order source spelling", "ownerKey", "module", "type", "entity");
        assertThat(GraphBuilder.mergeProperties(first, second)).isEqualTo(first);
    }
}
