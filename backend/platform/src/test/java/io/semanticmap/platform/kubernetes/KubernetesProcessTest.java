package io.semanticmap.platform.kubernetes;

import static io.semanticmap.platform.kubernetes.KubernetesFixture.*;
import static org.assertj.core.api.Assertions.*;

import io.semanticmap.platform.runner.BoundedProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesProcessTest {
    @TempDir
    Path root;

    @Test
    void commandUsesArgumentListAndBoundedProcessContract() throws Exception {
        var settings = settings(environment(root)
                .withProperty("semantic.runner.kubernetes.kubectl", "C:/trusted tools/kubectl.exe")
                .withProperty("semantic.runner.kubernetes.context", "selected-context"));
        var invocation = new AtomicReference<List<String>>();
        var process = new KubectlProcess(settings, (args, timeout, limit, cancelled) -> {
            invocation.set(args);
            assertThat(timeout).isEqualTo(Duration.ofSeconds(3));
            assertThat(limit).isEqualTo(1048576);
            return new BoundedProcess.Result(0, "{\"items\":[]}".getBytes(StandardCharsets.UTF_8));
        });
        process.json(List.of("get", "pods", "--output=json"), Duration.ofSeconds(3), () -> false);
        assertThat(invocation.get())
                .containsExactly(
                        "C:/trusted tools/kubectl.exe",
                        "--namespace=semanticmap",
                        "--request-timeout=15s",
                        "--context=selected-context",
                        "get",
                        "pods",
                        "--output=json");
    }

    @Test
    void failedProcessNeverLeaksReturnedOutputAndStrictJsonRejectsAmbiguity() {
        var settings = settings(environment(root));
        var process = new KubectlProcess(
                settings,
                (a, t, l, c) -> new BoundedProcess.Result(1, "secret from provider".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> process.json(List.of("get", "pods"), Duration.ofSeconds(1), () -> false))
                .hasMessage("KUBERNETES_COMMAND_FAILED");
        for (String input : List.of("{\"items\":[],\"items\":[{}]}", "{} {}", "[]", "not-json")) {
            var invalid = new KubectlProcess(
                    settings, (a, t, l, c) -> new BoundedProcess.Result(0, input.getBytes(StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> invalid.json(List.of("get", "pods"), Duration.ofSeconds(1), () -> false))
                    .hasMessage("KUBERNETES_INVALID_RESPONSE");
        }
        var oversized = new KubectlProcess(settings, (a, t, l, c) -> new BoundedProcess.Result(0, new byte[1048577]));
        assertThatThrownBy(() -> oversized.json(List.of("get", "pods"), Duration.ofSeconds(1), () -> false))
                .hasMessage("KUBERNETES_RESPONSE_LIMIT");
    }
}
