package io.semanticmap.platform.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OtlpParser {
    public static final int MAX_BODY_BYTES = 1048576;
    public static final int MAX_SPANS = 500;
    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(32)
                            .maxStringLength(16384)
                            .maxNumberLength(30)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Span(
            String traceId,
            String spanId,
            String parentSpanId,
            String name,
            int kind,
            BigInteger startNanos,
            BigInteger endNanos,
            Map<String, Object> attributes,
            Map<String, Object> resourceAttributes,
            Map<String, Object> scope,
            Map<String, Object> status,
            List<Map<String, Object>> events,
            List<Map<String, Object>> links) {}

    public List<Span> parse(InputStream body) {
        try {
            byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "OTLP body exceeds 1 MiB");
            JsonNode root = JSON.readTree(bytes);
            object(root);
            var result = new ArrayList<Span>();
            for (JsonNode resource : array(root, "resourceSpans", 100, true)) {
                object(resource);
                JsonNode resourceData = resource.path("resource");
                if (!resourceData.isMissingNode()) object(resourceData);
                var resourceAttributes = attributes(resourceData.path("attributes"), 0);
                for (JsonNode scope : array(resource, "scopeSpans", 100, true)) {
                    object(scope);
                    JsonNode scopeData = scope.path("scope");
                    if (!scopeData.isMissingNode()) object(scopeData);
                    Map<String, Object> instrumentation = new LinkedHashMap<>();
                    instrumentation.put("name", optionalString(scopeData, "name", 512));
                    instrumentation.put("version", optionalString(scopeData, "version", 128));
                    instrumentation.put("attributes", attributes(scopeData.path("attributes"), 0));
                    instrumentation.put("schemaUrl", optionalString(scope, "schemaUrl", 2048));
                    for (JsonNode span : array(scope, "spans", MAX_SPANS, true)) {
                        object(span);
                        if (result.size() == MAX_SPANS)
                            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "OTLP span limit exceeded");
                        String traceId = id(span.path("traceId"), 32), spanId = id(span.path("spanId"), 16);
                        String parent = optionalString(span, "parentSpanId", 16);
                        parent = parent.isEmpty() || parent.equals("0000000000000000")
                                ? null
                                : id(span.path("parentSpanId"), 16);
                        if (spanId.equals(parent)) throw invalid();
                        BigInteger start = nanos(span.path("startTimeUnixNano")),
                                end = nanos(span.path("endTimeUnixNano"));
                        if (end.compareTo(start) < 0) throw invalid();
                        String name = string(span.path("name"), 512);
                        if (name.isBlank()) throw invalid();
                        int kind = enumeration(
                                span.path("kind"),
                                List.of(
                                        "SPAN_KIND_UNSPECIFIED",
                                        "SPAN_KIND_INTERNAL",
                                        "SPAN_KIND_SERVER",
                                        "SPAN_KIND_CLIENT",
                                        "SPAN_KIND_PRODUCER",
                                        "SPAN_KIND_CONSUMER"));
                        JsonNode status = span.path("status");
                        if (!status.isMissingNode()) object(status);
                        Map<String, Object> statusMap = Map.of(
                                "code",
                                enumeration(
                                        status.path("code"),
                                        List.of("STATUS_CODE_UNSET", "STATUS_CODE_OK", "STATUS_CODE_ERROR")),
                                "message",
                                optionalString(status, "message", 2048));
                        var events = new ArrayList<Map<String, Object>>();
                        for (JsonNode event : array(span, "events", 128, false)) {
                            object(event);
                            events.add(Map.of(
                                    "name",
                                    string(event.path("name"), 512),
                                    "timeUnixNano",
                                    nanos(event.path("timeUnixNano")).toString(),
                                    "attributes",
                                    attributes(event.path("attributes"), 0)));
                        }
                        var links = new ArrayList<Map<String, Object>>();
                        for (JsonNode link : array(span, "links", 128, false)) {
                            object(link);
                            links.add(Map.of(
                                    "traceId",
                                    id(link.path("traceId"), 32),
                                    "spanId",
                                    id(link.path("spanId"), 16),
                                    "attributes",
                                    attributes(link.path("attributes"), 0)));
                        }
                        result.add(new Span(
                                traceId,
                                spanId,
                                parent,
                                name,
                                kind,
                                start,
                                end,
                                attributes(span.path("attributes"), 0),
                                resourceAttributes,
                                instrumentation,
                                statusMap,
                                events,
                                links));
                    }
                }
            }
            return List.copyOf(result);
        } catch (IOException | ArithmeticException | NumberFormatException ex) {
            throw invalid();
        }
    }

    private static Map<String, Object> attributes(JsonNode node, int depth) {
        if (node.isMissingNode()) return Map.of();
        if (!node.isArray() || node.size() > 128 || depth > 8) throw invalid();
        var result = new LinkedHashMap<String, Object>();
        for (JsonNode item : node) {
            object(item);
            String key = string(item.path("key"), 256);
            if (key.isBlank() || result.containsKey(key)) throw invalid();
            result.put(key, value(item.path("value"), depth + 1));
        }
        return result;
    }

    private static Object value(JsonNode node, int depth) {
        object(node);
        if (node.size() != 1 || depth > 8) throw invalid();
        String type = node.fieldNames().next();
        JsonNode v = node.get(type);
        return switch (type) {
            case "stringValue" -> string(v, 8192);
            case "boolValue" -> {
                if (!v.isBoolean()) throw invalid();
                yield v.booleanValue();
            }
            case "intValue" -> {
                if (!(v.isIntegralNumber() || v.isTextual()) || !v.asText().matches("-?(0|[1-9][0-9]*)"))
                    throw invalid();
                BigInteger number = new BigInteger(v.asText());
                number.longValueExact();
                yield number;
            }
            case "doubleValue" -> {
                if (!v.isNumber() || !Double.isFinite(v.doubleValue())) throw invalid();
                yield v.doubleValue();
            }
            case "bytesValue" -> {
                String encoded = string(v, 8192);
                try {
                    Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException ex) {
                    throw invalid();
                }
                yield Map.of("bytesValue", encoded);
            }
            case "arrayValue" -> {
                object(v);
                var values = new ArrayList<>();
                for (JsonNode child : array(v, "values", 128, false)) values.add(value(child, depth + 1));
                yield values;
            }
            case "kvlistValue" -> {
                object(v);
                yield attributes(v.path("values"), depth + 1);
            }
            default -> throw invalid();
        };
    }

    private static int enumeration(JsonNode value, List<String> names) {
        if (value.isMissingNode()) return 0;
        int index = value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue()
                : value.isTextual() ? names.indexOf(value.textValue()) : -1;
        if (index < 0 || index >= names.size()) throw invalid();
        return index;
    }

    private static BigInteger nanos(JsonNode value) {
        if (!(value.isTextual() || value.isIntegralNumber()) || !value.asText().matches("[1-9][0-9]{0,19}"))
            throw invalid();
        BigInteger n = new BigInteger(value.asText());
        if (n.compareTo(UINT64_MAX) > 0) throw invalid();
        return n;
    }

    private static String id(JsonNode node, int length) {
        String id = string(node, length).toLowerCase(Locale.ROOT);
        if (id.length() != length || !id.matches("[a-f0-9]+") || id.equals("0".repeat(length))) throw invalid();
        return id;
    }

    private static String string(JsonNode node, int max) {
        if (!node.isTextual()
                || node.textValue().length() > max
                || node.textValue().indexOf(0) >= 0) throw invalid();
        return node.textValue();
    }

    private static String optionalString(JsonNode node, String field, int max) {
        return node.path(field).isMissingNode() ? "" : string(node.path(field), max);
    }

    private static Iterable<JsonNode> array(JsonNode object, String field, int max, boolean required) {
        JsonNode node = object.path(field);
        if (!required && node.isMissingNode()) return List.of();
        if (!node.isArray() || node.size() > max) throw invalid();
        return node;
    }

    private static void object(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or oversized OTLP JSON payload");
    }
}
