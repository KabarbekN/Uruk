package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.semanticmap.platform.graph.internal.RuleEngine;
import io.semanticmap.platform.graph.internal.Semantics;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEngineTest {
    private final RuleEngine engine = new RuleEngine();

    @Test
    void genericNullCheckRemainsTechnical() {
        var rule = engine.classify("CONDITION", Map.of("condition", Map.of("type", "NULL_CHECK")));
        assertThat(rule.category()).isEqualTo("TECHNICAL_GUARD");
        assertThat(rule.score()).isZero();
        assertThat(rule.scoreBreakdown()).containsEntry("technicalGuardPenalty", -.65);
    }

    @Test
    void nullCheckWithExplicitRejectionRemainsBusinessCandidate() {
        var rule = engine.classify(
                "CONDITION",
                Map.of("condition", Map.of("type", "NULL_CHECK"), "trueOutcomes", List.of(Map.of("kind", "REJECT"))));
        assertThat(rule.category()).isEqualTo("BUSINESS_CONSTRAINT");
        assertThat(rule.score()).isGreaterThan(.5);
        assertThat(rule.trueOutcomes()).hasSize(1);
    }

    @Test
    void lowersAllExplicitPlumbingCategories() {
        for (String kind : List.of(
                "LOGGING",
                "RETRY",
                "TIMEOUT",
                "CACHE_PRESENCE",
                "PARSER_DEFENSIVE",
                "INSTRUMENTATION",
                "COLLECTION_BOUNDS")) {
            var rule = engine.classify(
                    "CONDITION", Map.of("technicalGuardKind", kind, "condition", Map.of("type", "COMPARISON")));
            assertThat(rule.category()).isEqualTo("TECHNICAL_GUARD");
        }
    }

    @Test
    void neverInventsExpressionOrOutcomes() {
        var rule = engine.classify("CONDITION", Map.of("condition", "total < configuredLimit"));
        assertThat(rule.condition()).containsEntry("type", "UNKNOWN_EXPRESSION");
        assertThat(rule.trueOutcomes()).isEmpty();
        assertThat(rule.falseOutcomes()).isEmpty();
        assertThat(rule.category()).isEqualTo("UNKNOWN_RULE");
    }

    @Test
    void capsReportedConfidenceByOriginAndResolution() {
        assertThat(Semantics.confidence("STATIC_INFERRED", 1, Map.of(), Map.of()))
                .isEqualTo(.65);
        assertThat(Semantics.confidence("LLM_ENRICHED", 1, Map.of(), Map.of())).isEqualTo(.3);
        assertThat(Semantics.confidence("STATIC_RESOLVED", 1, Map.of("resolved", false), Map.of()))
                .isEqualTo(.45);
    }

    @Test
    void recognizesActualAnalyzerNullCheckWithoutInventingBusinessImpact() {
        var nil = new java.util.LinkedHashMap<String, Object>();
        nil.put("type", "LITERAL");
        nil.put("value", null);
        var rule = engine.classify(
                "CONDITION",
                Map.of(
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
                        List.of(Map.of("kind", "RETURN", "expression", nil))));
        assertThat(rule.condition()).containsEntry("type", "NULL_CHECK");
        assertThat(rule.category()).isEqualTo("TECHNICAL_GUARD");
    }
}
