package io.semanticmap.platform.runner;

import static org.assertj.core.api.Assertions.*;

import io.semanticmap.contract.Protocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class RunnerPolicyTest {
    @TempDir
    Path root;

    @Test
    void commandUsesImmutableImageAndSandboxWithTranslatedSiblingMounts() throws Exception {
        var policy = new RunnerPolicy(new MockEnvironment()
                .withProperty("semantic.artifact-root", root.toString())
                .withProperty("semantic.artifact-host-path", "/srv/semantic artifacts"));
        Path source = Files.createDirectory(root.resolve("source")),
                input = Files.createDirectory(root.resolve("input")),
                output = Files.createDirectory(root.resolve("output"));
        var command = new AnalyzerExecutionPort.Command(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "java-spring",
                "0.1.0",
                "semanticmap/java-spring:0.1.0",
                source,
                root.resolve("execution"),
                request(),
                () -> false);
        String digest = "sha256:" + "a".repeat(64);
        var args = policy.createCommand(command, "semanticmap-test", digest, input, output);
        assertThat(args)
                .containsSubsequence("--user", "65532:65532")
                .containsSubsequence("--network", "none")
                .containsSubsequence("--cap-drop", "ALL")
                .containsSubsequence("--memory", "1024m")
                .containsSubsequence("--security-opt", "no-new-privileges")
                .contains("--read-only", "--pids-limit", "--cpus")
                .containsSubsequence(
                        "--log-driver",
                        "local",
                        "--log-opt",
                        "max-size=5m",
                        "--log-opt",
                        "max-file=1",
                        "--log-opt",
                        "compress=false")
                .contains(
                        "type=bind,source=/srv/semantic artifacts/source,target=/workspace,readonly",
                        "type=bind,source=/srv/semantic artifacts/input,target=/input,readonly")
                .containsSubsequence(digest, "analyze", "--request", "/input/request.json", "--output", "/output")
                .doesNotContain("--privileged", "/var/run/docker.sock", "sh", "-c");
        assertThatThrownBy(() -> policy.hostPath(root.getParent().resolve("other")))
                .hasMessage("ANALYZER_ARTIFACT_PATH_NOT_ALLOWED");
    }

    @Test
    void productionRequiresDigestAndDevelopmentRejectsLatest() {
        var policy = new RunnerPolicy(new MockEnvironment().withProperty("semantic.production", "true"));
        assertThatThrownBy(() -> policy.validateImage("semanticmap/java-spring:0.1.0"))
                .hasMessage("PRODUCTION_REQUIRES_IMAGE_DIGEST");
        assertThatCode(() -> policy.validateImage("registry.example.org/java-spring@sha256:" + "b".repeat(64)))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () -> new RunnerPolicy(new MockEnvironment()).validateImage("semanticmap/java-spring:latest"))
                .hasMessage("INVALID_ANALYZER_IMAGE");
    }

    static Protocol.Request request() {
        return new Protocol.Request(
                "1.0",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new Protocol.Revision("abc", "main"),
                new Protocol.Component(".", List.of("JAVA"), List.of("SPRING_BOOT"), "MAVEN"),
                List.of("SYMBOLS"),
                List.of(),
                new Protocol.Policy("DENY", false, false, true, false, 600, 134217728));
    }
}
