package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.semanticmap.platform.graph.internal.SemanticDiffer;
import io.semanticmap.platform.graph.internal.Semantics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticDifferTest {
    @Test
    void detects500To300AndMakesReviewStale() {
        var old = node(500);
        var changed = node(300);
        assertThat(SemanticDiffer.compare(old, changed))
                .contains(new SemanticDiffer.Change("MODIFIED", "THRESHOLD_CHANGED"));
        assertThat(SemanticDiffer.reviewUnchanged(old, changed)).isFalse();
    }

    @Test
    void ignoresObjectKeyOrderAndKeepsIdenticalReview() {
        var before = node(500);
        assertThat(SemanticDiffer.compare(before, new LinkedHashMap<>(before))).isEmpty();
        assertThat(SemanticDiffer.reviewUnchanged(before, before)).isTrue();
        assertThat(Semantics.hash(Map.of("a", 1, "b", 2))).isEqualTo(Semantics.hash(Map.of("b", 2, "a", 1)));
    }

    @Test
    void distinguishesOperatorsFromThresholds() {
        var after = node(500);
        after.put(
                "properties",
                Map.of(
                        "normalizedCondition",
                        Map.of(
                                "type",
                                "COMPARISON",
                                "operator",
                                "GREATER_THAN",
                                "right",
                                Map.of("resolvedValue", 500))));
        assertThat(SemanticDiffer.compare(node(500), after))
                .contains(new SemanticDiffer.Change("MODIFIED", "OPERATOR_CHANGED"));
    }

    @Test
    void sourceMoveDoesNotBecomeBehaviorChange() {
        var before = node(500);
        var after = node(500);
        var props = new LinkedHashMap<>(Semantics.map(after.get("properties")));
        props.put("filePath", "moved/Order.java");
        after.put("properties", props);
        assertThat(SemanticDiffer.compare(before, after))
                .contains(new SemanticDiffer.Change("MOVED", "SOURCE_MOVED"))
                .noneMatch(c -> c.changeType().equals("MODIFIED"));
    }

    @Test
    void thresholdInGeneratedLabelIsNotASymbolRename() {
        var before = node(500);
        var after = node(300);
        before.put("label", "if total < 500");
        after.put("label", "if total < 300");
        assertThat(SemanticDiffer.compare(before, after))
                .noneMatch(c -> c.changeType().equals("RENAMED"));
    }

    @Test
    void impactPriorityIsExplicitWhenSeveralPropertiesChange() {
        var before = node(500);
        var after = node(500);
        before.put(
                "properties",
                Map.of("actors", List.of("customer"), "roles", List.of("USER"), "dataWrites", List.of("orders")));
        after.put(
                "properties",
                Map.of("actors", List.of("staff"), "roles", List.of("ADMIN"), "dataWrites", List.of("audit")));
        assertThat(SemanticDiffer.compare(before, after))
                .contains(new SemanticDiffer.Change("MODIFIED", "ACTOR_CHANGED"));
    }

    private static Map<String, Object> node(int threshold) {
        var properties = Map.<String, Object>of(
                "normalizedCondition",
                Map.of("type", "COMPARISON", "operator", "LESS_THAN", "right", Map.of("resolvedValue", threshold)),
                "trueOutcomes",
                List.of(Map.of("kind", "THROW")));
        return new LinkedHashMap<>(Map.of(
                "stableKey",
                "rule:minimum",
                "kind",
                "BUSINESS_RULE",
                "label",
                "Minimum total",
                "properties",
                properties,
                "confidence",
                .99,
                "fingerprint",
                Semantics.hash(properties),
                "evidenceFingerprint",
                Semantics.hash("line " + threshold)));
    }
}
