package io.semanticmap.platform.graph.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuleEngine {
    public record Rule(
            String category,
            Map<String, Object> condition,
            List<?> trueOutcomes,
            List<?> falseOutcomes,
            Map<String, Double> scoreBreakdown,
            double score,
            Map<String, Object> model) {}

    private static final Set<String> IMPACTS = Set.of(
            "THROW",
            "THROWS",
            "DOMAIN_EXCEPTION",
            "BUSINESS_EXCEPTION",
            "REJECT",
            "DENY",
            "ALLOW",
            "RETURN",
            "RETURN_STATUS",
            "STATUS_TRANSITION",
            "DATABASE_WRITE",
            "EXTERNAL_CALL",
            "EVENT_PUBLICATION",
            "NOTIFICATION",
            "RESPONSE",
            "FILTER",
            "CHANGE_PRICE",
            "CHANGE_MONEY",
            "WRITE");
    private static final Set<String> PLUMBING = Set.of(
            "LOGGING",
            "RETRY",
            "TIMEOUT",
            "CACHE_PRESENCE",
            "PARSER_DEFENSIVE",
            "FRAMEWORK_BOILERPLATE",
            "INSTRUMENTATION",
            "COLLECTION_BOUNDS",
            "NULL_CHECK");

    public Rule classify(String kind, Map<String, Object> properties) {
        Object raw = properties.getOrDefault(
                "normalizedCondition", properties.getOrDefault("condition", properties.get("expression")));
        Map<String, Object> condition = Semantics.map(normalizeExpression(raw));
        if (condition.isEmpty()) condition = Map.of("type", "UNKNOWN_EXPRESSION", "source", raw == null ? "" : raw);
        List<?> yes = Semantics.list(properties.get("trueOutcomes"));
        List<?> no = Semantics.list(properties.get("falseOutcomes"));
        if (yes.isEmpty() && properties.get("outcome") != null) yes = List.of(properties.get("outcome"));
        var outcomes = Semantics.union(yes, no);
        boolean impactful = outcomes.stream().anyMatch(this::impactful);
        String conditionType = Semantics.text(condition, "type", "UNKNOWN_EXPRESSION");
        String purpose = Semantics.text(properties, "technicalGuardKind", "");
        boolean plumbing = Boolean.TRUE.equals(properties.get("technicalGuard"))
                || PLUMBING.contains(purpose)
                || Set.of("isTraceEnabled", "isDebugEnabled", "isInfoEnabled", "isWarnEnabled", "isErrorEnabled")
                        .contains(condition.getOrDefault("method", ""));
        boolean technical =
                !impactful && (plumbing || conditionType.equals("NULL_CHECK") || kind.equals("TECHNICAL_GUARD"));
        boolean auth = kind.equals("AUTHORIZATION_RULE")
                || conditionType.equals("ROLE_CHECK")
                || Boolean.TRUE.equals(properties.get("authorization"));
        var breakdown = new LinkedHashMap<String, Double>();
        breakdown.put("entryPointProximity", properties.containsKey("entryPointKey") ? .12 : 0.0);
        breakdown.put("businessVocabularyScore", Boolean.TRUE.equals(properties.get("businessVocabulary")) ? .08 : 0.0);
        breakdown.put("domainTypeScore", properties.containsKey("domainType") ? .10 : 0.0);
        breakdown.put("outcomeImpactScore", impactful ? .35 : 0.0);
        breakdown.put(
                "dataSideEffectScore",
                Semantics.list(properties.get("dataWrites")).isEmpty() ? 0.0 : .12);
        breakdown.put("authorizationScore", auth ? .30 : 0.0);
        breakdown.put("testEvidenceScore", Boolean.TRUE.equals(properties.get("verifiedByTest")) ? .10 : 0.0);
        breakdown.put("frameworkSignalScore", properties.containsKey("annotation") ? .10 : 0.0);
        breakdown.put("technicalGuardPenalty", technical ? -.65 : plumbing ? -.15 : 0.0);
        breakdown.put(
                "ambiguityPenalty",
                conditionType.equals("UNKNOWN_EXPRESSION")
                        ? -.25
                        : -Math.min(
                                .3,
                                Semantics.list(properties.get("ambiguities")).size() * .1));
        double score = Math.max(
                0,
                Math.min(
                        1,
                        .20
                                + breakdown.values().stream()
                                        .mapToDouble(Double::doubleValue)
                                        .sum()));
        String category = technical
                ? "TECHNICAL_GUARD"
                : auth
                        ? "AUTHORIZATION_RULE"
                        : kind.endsWith("_RULE") && !kind.equals("UNKNOWN_RULE")
                                ? kind
                                : impactful ? "BUSINESS_CONSTRAINT" : "UNKNOWN_RULE";
        var model = new LinkedHashMap<String, Object>();
        model.put("normalizedCondition", condition);
        model.put("category", category);
        model.put("trueOutcomes", yes);
        model.put("falseOutcomes", no);
        for (String key : List.of(
                "scope",
                "actors",
                "preconditions",
                "dataReads",
                "dataWrites",
                "externalEffects",
                "exceptions",
                "ambiguities")) model.put(key, properties.getOrDefault(key, List.of()));
        model.put("scoreBreakdown", breakdown);
        model.put("score", score);
        return new Rule(category, condition, yes, no, breakdown, score, model);
    }

    private boolean impactful(Object outcome) {
        if (outcome instanceof String text) return IMPACTS.contains(text);
        Map<String, Object> map = Semantics.map(outcome);
        if ("THROW".equals(map.get("kind"))
                && Set.of(
                                "NullPointerException",
                                "IllegalArgumentException",
                                "IndexOutOfBoundsException",
                                "ArrayIndexOutOfBoundsException",
                                "UnsupportedOperationException")
                        .contains(Semantics.text(map, "exception", ""))) return false;
        if ("RETURN".equals(map.get("kind"))) {
            var expression = Semantics.map(map.get("expression"));
            if ("EMPTY".equals(expression.get("type"))
                    || ("LITERAL".equals(expression.get("type")) && expression.get("value") == null)) return false;
        }
        if ("CONDITIONAL".equals(map.get("kind")))
            return Semantics.union(Semantics.list(map.get("trueOutcomes")), Semantics.list(map.get("falseOutcomes")))
                    .stream()
                    .anyMatch(this::impactful);
        return IMPACTS.contains(Semantics.text(map, "kind", Semantics.text(map, "type", "")));
    }

    public static Object normalizeExpression(Object raw) {
        if (raw instanceof List<?> list)
            return list.stream().map(RuleEngine::normalizeExpression).toList();
        if (!(raw instanceof Map<?, ?>)) return raw;
        var result = new LinkedHashMap<String, Object>();
        Semantics.map(raw).forEach((key, value) -> result.put(key, normalizeExpression(value)));
        String type = Semantics.text(result, "type", "");
        if (type.equals("BINARY")) {
            String operator = Semantics.text(result, "operator", "");
            boolean nullCheck =
                    Set.of("EQUAL", "EQUALS", "NOT_EQUAL", "NOT_EQUALS").contains(operator)
                            && (nullLiteral(result.get("left")) || nullLiteral(result.get("right")));
            result.put(
                    "type",
                    nullCheck
                            ? "NULL_CHECK"
                            : Set.of(
                                                    "LESS_THAN",
                                                    "LESS_THAN_OR_EQUAL",
                                                    "GREATER_THAN",
                                                    "GREATER_THAN_OR_EQUAL",
                                                    "EQUAL",
                                                    "NOT_EQUAL")
                                            .contains(operator)
                                    ? "COMPARISON"
                                    : Set.of("AND", "OR").contains(operator)
                                            ? "LOGICAL_OPERATION"
                                            : "BINARY_OPERATION");
        } else if (type.equals("REFERENCE")) result.put("type", "SYMBOL_REFERENCE");
        else if (type.equals("MEMBER")) result.put("type", "PROPERTY_ACCESS");
        else if (type.equals("CALL")) result.put("type", "FUNCTION_CALL");
        else if (type.equals("UNARY")) result.put("type", "UNARY_OPERATION");
        else if (type.equals("CONSTANT")) result.put("type", "CONSTANT_REFERENCE");
        return result;
    }

    private static boolean nullLiteral(Object value) {
        var literal = Semantics.map(value);
        return "LITERAL".equals(literal.get("type")) && literal.containsKey("value") && literal.get("value") == null;
    }
}
