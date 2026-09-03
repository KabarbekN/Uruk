package io.semanticmap.platform.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

final class PodVerifier {
    private PodVerifier() {}

    static void verify(JsonNode actual, JsonNode expected) throws IOException {
        for (String key : List.of("automountServiceAccountToken", "enableServiceLinks")) requireFalse(actual.path(key));
        for (String key : List.of("hostNetwork", "hostPID", "hostIPC", "shareProcessNamespace"))
            if (actual.path(key).asBoolean()) fail();
        for (String key : List.of("serviceAccountName", "restartPolicy", "runtimeClassName")) {
            if (!actual.path(key).asText().equals(expected.path(key).asText())) fail();
        }
        if (!actual.path("nodeSelector").path("kubernetes.io/os").asText().equals("linux")) fail();
        for (String key : List.of("initContainers", "ephemeralContainers"))
            if (!actual.path(key).isEmpty()) fail();
        JsonNode security = actual.path("securityContext");
        if (security.path("runAsUser").asLong(-1) != 65532
                || security.path("runAsGroup").asLong(-1) != 65532
                || !security.path("runAsNonRoot").asBoolean()
                || !security.path("seccompProfile").path("type").asText().equals("RuntimeDefault")) fail();
        if (security.hasNonNull("fsGroup")) fail();
        for (String key : List.of("sysctls", "supplementalGroups"))
            if (!security.path(key).isEmpty()) fail();
        JsonNode containers = actual.path("containers");
        if (!containers.isArray() || containers.size() != 1) fail();
        JsonNode container = containers.get(0),
                wanted = expected.path("containers").get(0);
        for (String key : List.of("name", "image", "args", "env"))
            if (!container.path(key).equals(wanted.path(key))) fail();
        for (String key : List.of("command", "envFrom", "ports", "volumeDevices", "lifecycle"))
            if (!container.path(key).isEmpty()) fail();
        JsonNode context = container.path("securityContext");
        requireFalse(context.path("allowPrivilegeEscalation"));
        requireFalse(context.path("privileged"));
        if (!context.path("readOnlyRootFilesystem").asBoolean()
                || !context.path("capabilities")
                        .path("drop")
                        .equals(wanted.path("securityContext")
                                .path("capabilities")
                                .path("drop"))) fail();
        if (!context.path("capabilities").path("add").isEmpty()) fail();
        if (context.has("runAsUser") && context.path("runAsUser").asLong() != 65532) fail();
        if (context.has("runAsGroup") && context.path("runAsGroup").asLong() != 65532) fail();
        if (context.has("runAsNonRoot") && !context.path("runAsNonRoot").asBoolean()) fail();
        if (context.has("seccompProfile")
                && !context.path("seccompProfile").path("type").asText().equals("RuntimeDefault")) fail();
        JsonNode mounts = container.path("volumeMounts"), requiredMounts = wanted.path("volumeMounts");
        if (!mounts.isArray() || mounts.size() != requiredMounts.size()) fail();
        for (JsonNode mount : requiredMounts) {
            JsonNode found = null;
            for (JsonNode candidate : mounts)
                if (candidate.path("mountPath").equals(mount.path("mountPath"))) {
                    if (found != null) fail();
                    found = candidate;
                }
            if (found == null) fail();
            for (String key : List.of("name", "mountPath", "subPath"))
                if (!found.path(key).asText().equals(mount.path(key).asText())) fail();
            if (found.path("readOnly").asBoolean() != mount.path("readOnly").asBoolean()
                    || found.hasNonNull("subPathExpr")) fail();
            if (found.has("mountPropagation")
                    && !found.path("mountPropagation").asText().equals("None")) fail();
        }
        JsonNode volumes = actual.path("volumes");
        if (!volumes.isArray() || volumes.size() != 2) fail();
        for (JsonNode wantedVolume : expected.path("volumes")) {
            JsonNode found = null;
            for (JsonNode candidate : volumes)
                if (candidate.path("name").equals(wantedVolume.path("name"))) {
                    if (found != null) fail();
                    found = candidate;
                }
            if (found == null || found.size() != 2) fail();
            if (wantedVolume.has("persistentVolumeClaim")) {
                if (!found.path("persistentVolumeClaim")
                                .path("claimName")
                                .equals(wantedVolume
                                        .path("persistentVolumeClaim")
                                        .path("claimName"))
                        || found.path("persistentVolumeClaim").path("readOnly").asBoolean()) fail();
            } else if (!found.path("emptyDir").path("medium").asText().equals("Memory")
                    || quantity(found.path("emptyDir").path("sizeLimit"))
                                    .compareTo(quantity(
                                            wantedVolume.path("emptyDir").path("sizeLimit")))
                            != 0) fail();
        }
        for (String kind : List.of("requests", "limits"))
            for (String resource : List.of("cpu", "memory", "ephemeral-storage"))
                if (quantity(container.path("resources").path(kind).path(resource))
                                .compareTo(quantity(
                                        wanted.path("resources").path(kind).path(resource)))
                        != 0) fail();
    }

    private static BigDecimal quantity(JsonNode node) throws IOException {
        var matcher = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([A-Za-z]*)")
                .matcher(node.asText());
        if (!matcher.matches()) throw new IOException("KUBERNETES_POD_SPEC_CHANGED");
        BigDecimal factor =
                switch (matcher.group(2)) {
                    case "" -> BigDecimal.ONE;
                    case "m" -> new BigDecimal("0.001");
                    case "u" -> new BigDecimal("0.000001");
                    case "n" -> new BigDecimal("0.000000001");
                    case "Ki" -> BigDecimal.valueOf(1024);
                    case "Mi" -> BigDecimal.valueOf(1048576);
                    case "Gi" -> BigDecimal.valueOf(1073741824);
                    default -> throw new IOException("KUBERNETES_POD_SPEC_CHANGED");
                };
        return new BigDecimal(matcher.group(1)).multiply(factor);
    }

    private static void requireFalse(JsonNode value) throws IOException {
        if (!value.isBoolean() || value.booleanValue()) fail();
    }

    private static void fail() throws IOException {
        throw new IOException("KUBERNETES_POD_SPEC_CHANGED");
    }
}
