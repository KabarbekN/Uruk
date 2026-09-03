package io.semanticmap.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SecretRedactor {
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|api.?key|authorization|credential|connection.?string|private.?key|cookie).*");
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|\\z)"),
            Pattern.compile(
                    "(?i)(?:jdbc:)?(?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis|https?)://[^\\s\"'<>]*:[^\\s\"'<>]*@[^\\s\"'<>]*"),
            Pattern.compile("(?i)(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+"),
            Pattern.compile(
                    "(?i)(?:password|passwd|secret|token|api[_-]?key|authorization|connection[_-]?string)[\"']?\\s*[=:]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)"),
            Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{10,}|AKIA[A-Z0-9]{16})\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"));

    public String redact(String text) {
        String result = text;
        for (Pattern pattern : PATTERNS) result = pattern.matcher(result).replaceAll("[REDACTED]");
        return result;
    }

    public JsonNode redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            node.fields()
                    .forEachRemaining(e -> result.set(
                            e.getKey(),
                            SECRET_KEY.matcher(e.getKey()).matches()
                                    ? JsonNodeFactory.instance.textNode("[REDACTED]")
                                    : redact(e.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(n -> result.add(redact(n)));
            return result;
        }
        return node.isTextual() ? JsonNodeFactory.instance.textNode(redact(node.textValue())) : node;
    }
}
