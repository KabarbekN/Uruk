package io.semanticmap.platform.kubernetes;

import static io.semanticmap.platform.kubernetes.KubernetesFixture.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KubernetesManifestTest {
    @TempDir
    Path root;

    @Test
    void exactPvcSubpathsAndSandboxSurviveApiQuantityNormalization() throws Exception {
        var settings = settings(environment(root)
                .withProperty("semantic.runner.kubernetes.pvc-sub-path", "tenant-artifacts")
                .withProperty("semantic.runner.kubernetes.runtime-class", "gvisor"));
        var command = command(root, () -> false, 30);
        var manifest = new KubernetesManifests(settings, JSON)
                .job(
                        command,
                        command.directory().resolve("input"),
                        command.directory().resolve("output"));
        assertThat(manifest.at("/spec/backoffLimit").intValue()).isZero();
        assertThat(manifest.at("/spec/parallelism").intValue()).isEqualTo(1);
        assertThat(manifest.at("/spec/activeDeadlineSeconds").intValue()).isEqualTo(30);
        assertThat(manifest.at("/spec/ttlSecondsAfterFinished").intValue()).isEqualTo(600);
        assertThat(manifest.at("/metadata/name").asText()).hasSizeLessThan(64);
        var pod = manifest.at("/spec/template/spec");
        assertThat(pod.at("/runtimeClassName").asText()).isEqualTo("gvisor");
        assertThat(pod.at("/automountServiceAccountToken").booleanValue()).isFalse();
        assertThat(pod.at("/securityContext/runAsUser").intValue()).isEqualTo(65532);
        assertThat(pod.at("/containers/0/securityContext/readOnlyRootFilesystem")
                        .booleanValue())
                .isTrue();
        assertThat(pod.at("/containers/0/securityContext/capabilities/drop/0").asText())
                .isEqualTo("ALL");
        var mounts = pod.at("/containers/0/volumeMounts");
        assertThat(mounts.get(0).path("subPath").asText()).isEqualTo("tenant-artifacts/run/source");
        assertThat(mounts.get(0).path("readOnly").booleanValue()).isTrue();
        assertThat(mounts.get(1).path("readOnly").booleanValue()).isTrue();
        assertThat(mounts.get(2).path("subPath").asText()).endsWith("/output");
        assertThat(mounts.get(2).path("readOnly").booleanValue()).isFalse();
        assertThat(pod.toString()).doesNotContain("hostPath", "pidsLimit", "secret", "docker.sock");
        assertThat(pod.at("/containers/0/env/0/name").asText()).isEqualTo("SEMANTIC_IMAGE_DIGEST");
        assertThat(pod.at("/containers/0/env/1/name").asText()).isEqualTo("SEMANTIC_ANALYZER_IMAGE_DIGEST");
        var admitted = pod.deepCopy();
        ((ObjectNode) admitted.at("/containers/0/resources/limits"))
                .put("cpu", "2")
                .put("memory", "1Gi");
        assertThatCode(() -> PodVerifier.verify(admitted, pod)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "token",
                "root",
                "escalation",
                "fsGroup",
                "network",
                "sidecar",
                "writeSource",
                "secretVolume",
                "unconfined",
                "image",
                "memory"
            })
    void rejectsUnsafeAdmissionMutation(String mutation) throws Exception {
        var command = command(root, () -> false, 30);
        var manifest = new KubernetesManifests(settings(environment(root)), JSON)
                .job(
                        command,
                        command.directory().resolve("input"),
                        command.directory().resolve("output"));
        var expected = manifest.at("/spec/template/spec");
        ObjectNode pod = expected.deepCopy();
        switch (mutation) {
            case "token" -> pod.put("automountServiceAccountToken", true);
            case "root" -> ((ObjectNode) pod.path("securityContext")).put("runAsUser", 0);
            case "escalation" -> ((ObjectNode) pod.at("/containers/0/securityContext"))
                    .put("allowPrivilegeEscalation", true);
            case "fsGroup" -> ((ObjectNode) pod.path("securityContext")).put("fsGroup", 0);
            case "network" -> pod.put("hostNetwork", true);
            case "sidecar" -> ((ArrayNode) pod.path("containers"))
                    .add(pod.path("containers").get(0).deepCopy());
            case "writeSource" -> ((ObjectNode) pod.at("/containers/0/volumeMounts/0")).put("readOnly", false);
            case "secretVolume" -> ((ArrayNode) pod.path("volumes"))
                    .add(JSON.valueToTree(Map.of("name", "token", "secret", Map.of("secretName", "credentials"))));
            case "unconfined" -> ((ObjectNode) pod.at("/securityContext/seccompProfile")).put("type", "Unconfined");
            case "image" -> ((ObjectNode) pod.at("/containers/0")).put("image", "changed:latest");
            case "memory" -> ((ObjectNode) pod.at("/containers/0/resources/limits")).put("memory", "2Gi");
            default -> throw new AssertionError(mutation);
        }
        assertThatThrownBy(() -> PodVerifier.verify(pod, expected)).hasMessage("KUBERNETES_POD_SPEC_CHANGED");
    }

    @Test
    void denyBaselineAndAdditivePoliciesAreCheckedIncludingExpressions() throws Exception {
        var verifier = new NetworkPolicyVerifier(settings(environment(root)));
        var labels = Map.of("app.kubernetes.io/name", "semanticmap-analyzer");
        var policies = policyList();
        assertThatCode(() -> verifier.verify(policies, labels)).doesNotThrowAnyException();
        ((ArrayNode) policies.path("items"))
                .add(JSON.valueToTree(Map.of(
                        "metadata",
                        Map.of("namespace", "semanticmap", "name", "worker"),
                        "spec",
                        Map.of(
                                "podSelector",
                                Map.of("matchLabels", Map.of("app.kubernetes.io/name", "semanticmap-worker")),
                                "egress",
                                List.of(Map.of())))));
        assertThatCode(() -> verifier.verify(policies, labels)).doesNotThrowAnyException();
        ((ObjectNode) policies.at("/items/1/spec"))
                .set(
                        "podSelector",
                        JSON.valueToTree(Map.of(
                                "matchExpressions",
                                List.of(Map.of(
                                        "key",
                                        "app.kubernetes.io/name",
                                        "operator",
                                        "In",
                                        "values",
                                        List.of("semanticmap-analyzer"))))));
        assertThatThrownBy(() -> verifier.verify(policies, labels)).hasMessage("KUBERNETES_NETWORK_POLICY_UNVERIFIED");
    }

    @Test
    void absentOrIncompleteOrForeignPolicyCannotAuthorizeLaunch() {
        var verifier = new NetworkPolicyVerifier(settings(environment(root)));
        for (String mutation : List.of("missing", "ingressOnly", "allow", "foreign", "pagination")) {
            var policies = policyList();
            switch (mutation) {
                case "missing" -> ((ArrayNode) policies.path("items")).removeAll();
                case "ingressOnly" -> ((ObjectNode) policies.at("/items/0/spec"))
                        .set("policyTypes", JSON.valueToTree(List.of("Ingress")));
                case "allow" -> ((ObjectNode) policies.at("/items/0/spec"))
                        .set("egress", JSON.valueToTree(List.of(Map.of())));
                case "foreign" -> ((ObjectNode) policies.at("/items/0/metadata")).put("namespace", "other");
                case "pagination" -> policies.set("metadata", JSON.valueToTree(Map.of("continue", "next")));
            }
            assertThatThrownBy(() -> verifier.verify(policies, Map.of()))
                    .as(mutation)
                    .hasMessage("KUBERNETES_NETWORK_POLICY_UNVERIFIED");
        }
    }
}
