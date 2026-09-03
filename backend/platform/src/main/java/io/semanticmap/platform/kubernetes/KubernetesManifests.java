package io.semanticmap.platform.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.contract.Protocol;
import io.semanticmap.platform.runner.AnalyzerExecutionPort;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class KubernetesManifests {
    static final String MANAGED = "semanticmap.io/managed",
            BACKEND = "semanticmap.io/backend",
            INSTALLATION = "semanticmap.io/installation",
            EXECUTION = "semanticmap.io/execution",
            ATTEMPT = "semanticmap.io/attempt";
    private final KubernetesSettings settings;
    private final ObjectMapper mapper;

    KubernetesManifests(KubernetesSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    static String jobName(UUID execution, UUID attempt) {
        return "sma-" + Protocol.hash(execution + ":" + attempt).substring(7, 55);
    }

    Map<String, String> labels(UUID execution, UUID attempt) {
        return Map.of(
                MANAGED,
                "true",
                BACKEND,
                "kubernetes",
                INSTALLATION,
                settings.installation(),
                EXECUTION,
                execution.toString(),
                ATTEMPT,
                attempt.toString(),
                "app.kubernetes.io/name",
                "semanticmap-analyzer");
    }

    String selector() {
        return MANAGED + "=true," + BACKEND + "=kubernetes," + INSTALLATION + "=" + settings.installation();
    }

    String selector(UUID execution, UUID attempt) {
        return selector() + "," + EXECUTION + "=" + execution + (attempt == null ? "" : "," + ATTEMPT + "=" + attempt);
    }

    JsonNode job(AnalyzerExecutionPort.Command command, Path input, Path output) throws IOException {
        var labels = labels(command.executionId(), command.attemptId());
        var pod = new LinkedHashMap<String, Object>();
        pod.put("restartPolicy", "Never");
        pod.put("automountServiceAccountToken", false);
        pod.put("serviceAccountName", settings.serviceAccount());
        pod.put("enableServiceLinks", false);
        pod.put("hostNetwork", false);
        pod.put("hostPID", false);
        pod.put("hostIPC", false);
        pod.put("terminationGracePeriodSeconds", 5);
        pod.put("nodeSelector", Map.of("kubernetes.io/os", "linux"));
        pod.put(
                "securityContext",
                Map.of(
                        "runAsUser",
                        65532,
                        "runAsGroup",
                        65532,
                        "runAsNonRoot",
                        true,
                        "seccompProfile",
                        Map.of("type", "RuntimeDefault")));
        if (!settings.runtimeClass().isEmpty()) pod.put("runtimeClassName", settings.runtimeClass());
        pod.put(
                "volumes",
                List.of(
                        Map.of("name", "artifacts", "persistentVolumeClaim", Map.of("claimName", settings.pvc())),
                        Map.of("name", "temporary", "emptyDir", Map.of("medium", "Memory", "sizeLimit", "64Mi"))));
        Map<String, Object> resources = Map.of(
                "cpu", settings.cpuMillis() + "m", "memory", settings.memoryMiB() + "Mi", "ephemeral-storage", "128Mi");
        pod.put(
                "containers",
                List.of(Map.of(
                        "name",
                        "analyzer",
                        "image",
                        command.imageReference(),
                        "imagePullPolicy",
                        "IfNotPresent",
                        "args",
                        List.of("analyze", "--request", "/input/request.json", "--output", "/output"),
                        "env",
                        List.of(
                                Map.of(
                                        "name",
                                        "SEMANTIC_IMAGE_DIGEST",
                                        "value",
                                        KubernetesSettings.digest(command.imageReference())),
                                Map.of(
                                        "name",
                                        "SEMANTIC_ANALYZER_IMAGE_DIGEST",
                                        "value",
                                        KubernetesSettings.digest(command.imageReference()))),
                        "resources",
                        Map.of("requests", resources, "limits", resources),
                        "securityContext",
                        Map.of(
                                "allowPrivilegeEscalation",
                                false,
                                "privileged",
                                false,
                                "readOnlyRootFilesystem",
                                true,
                                "capabilities",
                                Map.of("drop", List.of("ALL"))),
                        "volumeMounts",
                        List.of(
                                mount("artifacts", "/workspace", settings.subPath(command.workspace()), true),
                                mount("artifacts", "/input", settings.subPath(input), true),
                                mount("artifacts", "/output", settings.subPath(output), false),
                                Map.of("name", "temporary", "mountPath", "/tmp")),
                        "terminationMessagePolicy",
                        "File",
                        "terminationMessagePath",
                        "/tmp/termination-log")));
        return mapper.valueToTree(Map.of(
                "apiVersion",
                "batch/v1",
                "kind",
                "Job",
                "metadata",
                Map.of(
                        "name",
                        jobName(command.executionId(), command.attemptId()),
                        "namespace",
                        settings.namespace(),
                        "labels",
                        labels),
                "spec",
                Map.of(
                        "backoffLimit",
                        0,
                        "completions",
                        1,
                        "parallelism",
                        1,
                        "activeDeadlineSeconds",
                        settings.timeout(command),
                        "ttlSecondsAfterFinished",
                        settings.ttlSeconds(),
                        "template",
                        Map.of("metadata", Map.of("labels", labels), "spec", pod))));
    }

    private static Map<String, Object> mount(String name, String target, String subPath, boolean readOnly) {
        return Map.of("name", name, "mountPath", target, "subPath", subPath, "readOnly", readOnly);
    }
}
