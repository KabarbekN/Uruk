package io.semanticmap.platform.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class NetworkPolicyVerifier {
    private final KubernetesSettings settings;

    NetworkPolicyVerifier(KubernetesSettings settings) {
        this.settings = settings;
    }

    void verify(JsonNode policies, Map<String, String> labels) throws IOException {
        JsonNode items = policies.path("items");
        if (!items.isArray()
                || items.size() > 1000
                || !policies.path("metadata").path("continue").asText().isEmpty()) fail();
        boolean baseline = false;
        for (JsonNode policy : items) {
            if (!settings.namespace()
                    .equals(policy.path("metadata").path("namespace").asText())) fail();
            JsonNode spec = policy.path("spec"), selector = spec.path("podSelector");
            if (!selector.isObject()) fail();
            boolean selected = selects(selector, labels);
            if (settings.networkPolicy()
                    .equals(policy.path("metadata").path("name").asText())) {
                Set<String> types = strings(spec.path("policyTypes"));
                if (!selector.isEmpty()
                        || !types.equals(Set.of("Ingress", "Egress"))
                        || !emptyRules(spec, "ingress")
                        || !emptyRules(spec, "egress")) fail();
                baseline = true;
            }
            // NetworkPolicy allows are additive; one matching allow overrides a default-deny baseline.
            if (selected && (!emptyRules(spec, "ingress") || !emptyRules(spec, "egress"))) fail();
        }
        if (!baseline) fail();
    }

    private static boolean emptyRules(JsonNode spec, String key) throws IOException {
        JsonNode rules = spec.path(key);
        if (rules.isMissingNode()) return true;
        if (!rules.isArray()) {
            fail();
            return false;
        }
        return rules.isEmpty();
    }

    private static boolean selects(JsonNode selector, Map<String, String> labels) throws IOException {
        JsonNode match = selector.path("matchLabels");
        if (!match.isMissingNode() && !match.isObject()) fail();
        var fields = match.fields();
        boolean selected = true;
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getValue().isTextual()) fail();
            selected &= entry.getValue().textValue().equals(labels.get(entry.getKey()));
        }
        JsonNode expressions = selector.path("matchExpressions");
        if (!expressions.isMissingNode() && !expressions.isArray()) fail();
        for (JsonNode expression : expressions) {
            String key = expression.path("key").asText(),
                    operator = expression.path("operator").asText();
            if (key.isBlank()) fail();
            Set<String> values = expression.has("values") ? strings(expression.path("values")) : Set.of();
            selected &= switch (operator) {
                case "In" -> labels.containsKey(key) && values.contains(labels.get(key));
                case "NotIn" -> !labels.containsKey(key) || !values.contains(labels.get(key));
                case "Exists" -> labels.containsKey(key);
                case "DoesNotExist" -> !labels.containsKey(key);
                default -> throw new IOException("KUBERNETES_NETWORK_POLICY_UNVERIFIED");
            };
        }
        return selected;
    }

    private static Set<String> strings(JsonNode node) throws IOException {
        if (!node.isArray()) fail();
        var result = new HashSet<String>();
        for (JsonNode item : node) {
            if (!item.isTextual()) fail();
            result.add(item.textValue());
        }
        return result;
    }

    private static void fail() throws IOException {
        throw new IOException("KUBERNETES_NETWORK_POLICY_UNVERIFIED");
    }
}
