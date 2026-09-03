package io.semanticmap.platform.kubernetes;

import static io.semanticmap.platform.kubernetes.KubernetesFixture.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.semanticmap.platform.runner.AnalyzerExecutionPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KubernetesAdapterTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(ints = {0, 10})
    void realOutputValidatorUsesActualExitAndRecordsBothDigests(int exit) throws Exception {
        var api = new Api();
        api.exitCode = exit;
        var command = command(root, () -> false, 30);
        var result = api.adapter(settings(environment(root))).execute(command);
        assertThat(result.status()).isEqualTo(exit == 10 ? "PARTIALLY_SUCCEEDED" : "SUCCEEDED");
        assertThat(result.imageDigest()).isEqualTo(DIGEST);
        assertThat(result.coverage()).containsEntry("filesParsed", 1);
        var record = JSON.readTree(
                command.directory().resolve("kubernetes-execution.json").toFile());
        assertThat(record.path("exitCode").intValue()).isEqualTo(exit);
        assertThat(record.path("imageDigest").asText()).isEqualTo(DIGEST);
        assertThat(record.path("runtimeImageId").asText()).endsWith("b".repeat(64));
        assertThat(Files.readString(command.directory().resolve("analyzer.log")))
                .isEqualTo("actual log\n");
        assertThat(api.called("delete", "jobs.batch")).isTrue();
        assertThat(api.called("delete", "pods")).isTrue();
        assertThat(api.job).isNull();
        assertThat(api.pod).isNull();
        assertThat(api.calls).allSatisfy(args -> assertThat(args)
                .contains("--namespace=semanticmap", "--request-timeout=15s")
                .doesNotContain("sh", "-c", "exec", "apply"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"exit", "output", "logs", "admission", "jobPolicy", "ambiguous", "duplicates"})
    void allPostSubmissionFailuresAttemptCleanupAndNeverReturnSuccess(String scenario) throws Exception {
        var api = new Api();
        switch (scenario) {
            case "exit" -> api.exitCode = 137;
            case "output" -> api.invalidOutput = true;
            case "logs" -> api.oversizeLogs = true;
            case "admission" -> api.admission = pod -> pod.put("spec", "invalid");
            case "jobPolicy" -> api.jobAdmission = job -> ((ObjectNode) job.path("spec")).put("backoffLimit", 3);
            case "ambiguous" -> api.ambiguousCreate = true;
            case "duplicates" -> api.duplicatePod = true;
        }
        var command = command(root, () -> false, 30);
        assertThatThrownBy(() -> api.adapter(settings(environment(root))).execute(command))
                .isInstanceOf(IOException.class);
        assertThat(api.called("delete", "jobs.batch")).isTrue();
        assertThat(api.called("delete", "pods")).isTrue();
        assertThat(api.job).isNull();
        assertThat(api.pod).isNull();
    }

    @Test
    void cancellationBeforeSubmissionNeverContactsCluster() throws Exception {
        var api = new Api();
        var command = command(root, () -> true, 30);
        assertThatThrownBy(() -> api.adapter(settings(environment(root))).execute(command))
                .hasMessage("PROCESS_CANCELLED");
        assertThat(api.calls).isEmpty();
    }

    @Test
    void cancellationAfterSubmissionStillDeletesResources() throws Exception {
        var cancelled = new AtomicBoolean();
        var api = new Api();
        api.beforePodRead = () -> cancelled.set(true);
        var command = command(root, cancelled::get, 30);
        assertThatThrownBy(() -> api.adapter(settings(environment(root))).execute(command))
                .hasMessage("PROCESS_CANCELLED");
        assertThat(api.job).isNull();
        assertThat(api.pod).isNull();
    }

    @Test
    void boundedPendingTimeoutStillDeletesResources() throws Exception {
        var api = new Api();
        api.pending = true;
        var command = command(root, () -> false, 1);
        assertThatThrownBy(() -> api.adapter(settings(environment(root))).execute(command))
                .hasMessage("ANALYZER_TIMEOUT");
        assertThat(api.job).isNull();
        assertThat(api.pod).isNull();
    }

    @Test
    void policyFailureNeverCreatesJob() throws Exception {
        var api = new Api();
        api.policies = JSON.readTree("{\"items\":[]}");
        var command = command(root, () -> false, 30);
        assertThatThrownBy(() -> api.adapter(settings(environment(root))).execute(command))
                .hasMessage("KUBERNETES_NETWORK_POLICY_UNVERIFIED");
        assertThat(api.calls).noneMatch(args -> args.contains("create"));
    }

    @Test
    void cleanupFailureIsNotSuccessAndOrphanPodRemainsDiscoverableAndCancellable() throws Exception {
        var api = new Api();
        api.failDelete = true;
        var command = command(root, () -> false, 30);
        var adapter = api.adapter(settings(environment(root)));
        assertThatThrownBy(() -> adapter.execute(command)).hasMessage("KUBERNETES_CLEANUP_FAILED");
        var identity = new AnalyzerExecutionPort.RunningExecution(command.executionId(), command.attemptId());
        assertThat(adapter.running()).containsExactly(identity);
        api.job = null;
        api.failDelete = false;
        assertThat(adapter.running()).containsExactly(identity);
        adapter.cancelAttempt(identity);
        assertThat(adapter.running()).isEmpty();
    }

    @Test
    void cannotDeleteResourcesOutsideInstallationScope() throws Exception {
        var api = new Api();
        api.failDelete = true;
        var command = command(root, () -> false, 30);
        var adapter = api.adapter(settings(environment(root)));
        assertThatThrownBy(() -> adapter.execute(command)).hasMessage("KUBERNETES_CLEANUP_FAILED");
        api.failDelete = false;
        api.calls.clear();
        ((ObjectNode) api.job.at("/metadata/labels")).put(KubernetesManifests.INSTALLATION, "someone-else");
        assertThatThrownBy(() -> adapter.cancelAttempt(
                        new AnalyzerExecutionPort.RunningExecution(command.executionId(), command.attemptId())))
                .hasMessage("KUBERNETES_CLEANUP_FAILED");
        assertThat(api.calls).noneMatch(args -> args.contains("delete"));
    }

    @Test
    void cancelExecutionDeletesOnlyDiscoveredAttemptsWithScopedSelector() throws Exception {
        var api = new Api();
        api.failDelete = true;
        var command = command(root, () -> false, 30);
        var adapter = api.adapter(settings(environment(root)));
        assertThatThrownBy(() -> adapter.execute(command)).hasMessage("KUBERNETES_CLEANUP_FAILED");
        api.failDelete = false;
        api.calls.clear();
        adapter.cancel(command.executionId());
        assertThat(api.job).isNull();
        assertThat(api.pod).isNull();
        assertThat(api.calls).anySatisfy(args -> assertThat(args)
                .contains(
                        "--selector=semanticmap.io/managed=true,semanticmap.io/backend=kubernetes,semanticmap.io/installation=semanticmap,semanticmap.io/execution="
                                + command.executionId()));
    }

    @Test
    void outputPollingEnforcesByteAndEntryBounds() throws Exception {
        Path output = Files.createDirectory(root.resolve("output"));
        Files.writeString(output.resolve("large"), "12345");
        assertThatThrownBy(() -> KubernetesAnalyzerExecutionAdapter.checkOutput(output, 4))
                .hasMessage("ANALYZER_OUTPUT_LIMIT");
        for (int i = 0; i < 64; i++) Files.createFile(output.resolve("file" + i));
        assertThatThrownBy(() -> KubernetesAnalyzerExecutionAdapter.checkOutput(output, 1000))
                .hasMessage("ANALYZER_OUTPUT_LIMIT");
    }
}
