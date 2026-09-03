package io.semanticmap.platform.graph.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.contract.Protocol;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class Semantics {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Set<String> ENTRY_KINDS =
            Set.of("ENDPOINT", "SCHEDULED_JOB", "MESSAGE_CONSUMER", "COMMAND_ENTRY_POINT", "UI_ACTION");
    public static final Set<String> RULE_KINDS = Set.of(
            "CONDITION",
            "DECISION",
            "BUSINESS_RULE",
            "BUSINESS_CONSTRAINT",
            "VALIDATION_RULE",
            "AUTHORIZATION_RULE",
            "SECURITY_RULE",
            "DATA_RULE",
            "OPERATIONAL_RULE",
            "COMPLIANCE_RULE",
            "TECHNICAL_GUARD",
            "UNKNOWN_RULE");
    public static final Set<String> TECHNICAL_KINDS = Set.of(
            "FILE", "PACKAGE", "CLASS", "INTERFACE", "METHOD", "FUNCTION", "PARAMETER", "CONSTANT", "TECHNICAL_GUARD");

    private Semantics() {}

    public static String nodeKind(String factKind, Map<String, Object> properties) {
        return switch (factKind) {
            case "TYPE" -> "Interface".equalsIgnoreCase(text(properties, "typeKind", "")) ? "INTERFACE" : "CLASS";
            case "ORM_ENTITY" -> "ENTITY";
            case "DATA_READ" -> "DATABASE_READ";
            case "DATA_WRITE" -> "DATABASE_WRITE";
            case "TEST" -> "TEST_CASE";
            case "FAILURE_BRANCH" -> "EXCEPTION";
            case "SIDE_EFFECT" -> Set.of(
                                    "EXTERNAL_CALL",
                                    "EVENT_PUBLICATION",
                                    "NOTIFICATION",
                                    "CACHE_OPERATION",
                                    "FILE_OPERATION")
                            .contains(text(properties, "effectKind", ""))
                    ? text(properties, "effectKind", "SIDE_EFFECT")
                    : "SIDE_EFFECT";
            default -> factKind;
        };
    }

    public static UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    public static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    public static String text(Map<String, Object> value, String key, String fallback) {
        Object found = value.get(key);
        return found == null ? fallback : found.toString();
    }

    public static String hash(Object value) {
        try {
            return Protocol.hash(MAPPER.writeValueAsString(canonical(value)));
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Cannot fingerprint semantic data", ex);
        }
    }

    public static Object canonical(Object value) {
        if (value instanceof JsonNode node) return canonical(MAPPER.convertValue(node, Object.class));
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, child) -> sorted.put(key.toString(), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(Semantics::canonical).toList();
        return value;
    }

    public static Map<String, Object> behavioral(Map<String, Object> properties) {
        var result = new LinkedHashMap<>(properties);
        for (String key : List.of(
                "name",
                "label",
                "description",
                "filePath",
                "startLine",
                "endLine",
                "startColumn",
                "endColumn",
                "scoreBreakdown",
                "provenance",
                "analyzers",
                "origins",
                "frameworks",
                "componentPaths")) result.remove(key);
        if (result.containsKey("normalizedCondition")) {
            result.remove("condition");
            result.remove("sourceExpression");
        }
        return result;
    }

    public static String fingerprint(String kind, Map<String, Object> properties) {
        return hash(Map.of("kind", kind, "properties", behavioral(properties)));
    }

    public static String structural(String kind, Map<String, Object> properties) {
        var shape = new LinkedHashMap<>(behavioral(properties));
        shape.remove("ownerKey");
        shape.remove("symbolSignature");
        return hash(Map.of("kind", kind, "properties", shape));
    }

    public static double confidence(
            String origin, double reported, Map<String, Object> properties, Map<String, Object> coverage) {
        double ceiling =
                switch (origin) {
                    case "STATIC_EXACT", "DATABASE_DERIVED", "RUNTIME_OBSERVED" -> 1.0;
                    case "STATIC_TYPED", "STATIC_RESOLVED" -> .98;
                    case "FRAMEWORK_DERIVED" -> .92;
                    case "STATIC_SYNTAX" -> .75;
                    case "STATIC_INFERRED" -> .65;
                    case "HEURISTIC" -> .50;
                    default -> .30;
                };
        if (Boolean.FALSE.equals(properties.get("resolved"))) ceiling = Math.min(ceiling, .45);
        ceiling -= Math.min(.3, list(properties.get("ambiguities")).size() * .1);
        if (Boolean.TRUE.equals(coverage.get("partial"))) ceiling -= .1;
        return Math.max(0, Math.min(ceiling, reported));
    }

    public static List<String> strings(Object value) {
        return list(value).stream().map(Object::toString).toList();
    }

    public static List<Object> union(List<?> first, List<?> second) {
        var result = new ArrayList<Object>(first);
        for (Object value : second) if (!result.contains(value)) result.add(value);
        return result;
    }
}
