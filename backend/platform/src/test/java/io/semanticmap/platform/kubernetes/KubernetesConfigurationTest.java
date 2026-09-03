package io.semanticmap.platform.kubernetes;

import static io.semanticmap.platform.kubernetes.KubernetesFixture.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.runner.AnalyzerExecutionPort;
import io.semanticmap.platform.runner.AnalyzerOutputValidator;
import io.semanticmap.platform.runner.RunnerPolicy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class KubernetesConfigurationTest {
    @TempDir
    Path root;

    @Test
    void kubernetesIsAbsentUnlessExplicitlySelectedAndInitializationNeverRunsKubectl() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(
                        KubernetesSettings.class, KubectlProcess.class, KubernetesAnalyzerExecutionAdapter.class)
                .withBean(RunnerPolicy.class, () -> new RunnerPolicy(environment(root)))
                .withBean(ObjectMapper.class, () -> JSON)
                .withBean(AnalyzerOutputValidator.class, () -> new AnalyzerOutputValidator(JSON));
        context.run(ctx -> {
            assertThat(ctx)
                    .hasNotFailed()
                    .doesNotHaveBean(KubernetesSettings.class)
                    .doesNotHaveBean(KubectlProcess.class)
                    .doesNotHaveBean(KubernetesAnalyzerExecutionAdapter.class);
        });
        context.withPropertyValues("semantic.runner.mode=docker")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(KubernetesAnalyzerExecutionAdapter.class));
        context.withPropertyValues(
                        "semantic.runner.mode=kubernetes",
                        "semantic.runner.kubernetes.namespace=semanticmap",
                        "semantic.runner.kubernetes.pvc=artifacts",
                        "semantic.runner.kubernetes.network-policy=analyzer-deny-all",
                        "semantic.runner.kubernetes.allowed-images=" + IMAGE,
                        "semantic.runner.kubernetes.kubectl=nonexistent-no-cluster-command")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(KubernetesAnalyzerExecutionAdapter.class));
    }

    @Test
    void namespacePvcPolicyAndImmutableAllowlistAreMandatoryEvenOutsideProduction() {
        assertThatThrownBy(() -> settings(new MockEnvironment())).hasMessageContaining("MISSING_KUBERNETES_NAMESPACE");
        for (String key : new String[] {"namespace", "pvc", "network-policy", "allowed-images"})
            assertThatThrownBy(() -> settings(environment(root).withProperty("semantic.runner.kubernetes." + key, "")))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(
                        environment(root).withProperty("semantic.runner.kubernetes.allowed-images", "analyzer:1.0")))
                .hasMessage("KUBERNETES_REQUIRES_IMMUTABLE_IMAGE");
        assertThatThrownBy(() -> settings(
                        environment(root).withProperty("semantic.runner.kubernetes.namespace", "bad --context=other")))
                .hasMessage("INVALID_KUBERNETES_RESOURCE_NAME");
        assertThatThrownBy(() ->
                        settings(environment(root).withProperty("semantic.runner.kubernetes.cpu-millis", "17000")))
                .hasMessage("INVALID_KUBERNETES_RESOURCE_LIMIT");
    }

    @Test
    void pathsStayUnderPvcAndCredentialsCannotResideThere() throws Exception {
        var settings = settings(environment(root));
        assertThatThrownBy(() -> settings.subPath(root)).hasMessage("ANALYZER_ARTIFACT_PATH_NOT_ALLOWED");
        assertThatThrownBy(() -> settings.subPath(root.resolve("../outside")))
                .hasMessage("ANALYZER_ARTIFACT_PATH_NOT_ALLOWED");
        assertThatThrownBy(() -> settings(
                        environment(root).withProperty("semantic.runner.kubernetes.pvc-sub-path", "../secret")))
                .hasMessage("INVALID_KUBERNETES_PVC_SUB_PATH");
        assertThatThrownBy(() -> settings(environment(root)
                        .withProperty(
                                "semantic.runner.kubernetes.kubeconfig",
                                root.resolve("credentials").toString())))
                .hasMessage("KUBECONFIG_MUST_NOT_BE_ON_ARTIFACT_VOLUME");
        var command = command(root, () -> false, 30);
        var overlapping = new AnalyzerExecutionPort.Command(
                command.executionId(),
                command.attemptId(),
                command.analyzerKey(),
                command.version(),
                command.imageReference(),
                command.workspace(),
                command.workspace().resolve("attempt"),
                command.request(),
                command.cancelled());
        assertThatThrownBy(() -> settings.validate(overlapping)).hasMessage("ANALYZER_MOUNTS_OVERLAP");
    }

    @Test
    void pinnedButUnlistedImageIsRejectedBeforeAnyProcess() throws Exception {
        var command = command(root, () -> false, 30);
        var unlisted = new AnalyzerExecutionPort.Command(
                command.executionId(),
                command.attemptId(),
                command.analyzerKey(),
                command.version(),
                "registry.example.com/other@" + DIGEST,
                command.workspace(),
                command.directory(),
                command.request(),
                command.cancelled());
        var api = new Api();
        assertThatThrownBy(() -> api.adapter(settings(environment(root))).execute(unlisted))
                .hasMessage("KUBERNETES_ANALYZER_IMAGE_NOT_ALLOWED");
        assertThat(api.calls).isEmpty();
    }
}
