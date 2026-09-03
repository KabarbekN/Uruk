package io.semanticmap.platform.runner;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DockerRunnerIntegrationTest {
    @TempDir
    Path root;

    @Test
    void timeoutRemovesContainerAndDoesNotReportSuccess() throws Exception {
        var command =
                command("import pathlib, time; pathlib.Path('/output/started').write_text('ready'); time.sleep(120)");
        var runner = runner(new RunnerPolicy(environment().withProperty("semantic.runner.timeout-seconds", "5")));
        assertThatThrownBy(() -> runner.execute(command))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("PROCESS_TIMEOUT");
        assertThat(root.resolve("attempt/output/started")).hasContent("ready");
        assertRemoved(command.executionId());
    }

    @Test
    void startupFailureRecordsDaemonErrorAndExitStateBeforeCleanup() throws Exception {
        var command = command("import time; time.sleep(120)");
        var policy = new RunnerPolicy(environment()) {
            @Override
            public List<String> createCommand(
                    AnalyzerExecutionPort.Command command, String name, String digest, Path input, Path output)
                    throws IOException {
                var args = super.createCommand(command, name, digest, input, output);
                args.set(args.indexOf("compress=false"), "compress=true");
                return args;
            }
        };
        assertThatThrownBy(() -> runner(policy).execute(command))
                .isInstanceOf(IOException.class)
                .hasMessage("CONTAINER_START_FAILED");
        var state = new ObjectMapper()
                .readTree(root.resolve("attempt/container-state.json").toFile());
        assertThat(state.path("Status").asText()).isEqualTo("created");
        assertThat(state.path("Running").asBoolean()).isFalse();
        assertThat(state.path("ExitCode").asInt()).isEqualTo(128);
        assertThat(state.path("dockerExitCode").asInt()).isNotZero();
        assertThat(state.path("Error").asText()).contains("compression cannot be enabled when max file count is 1");
        assertThat(Files.readString(root.resolve("attempt/analyzer.stderr.log")))
                .contains("compression cannot be enabled when max file count is 1");
        assertRemoved(command.executionId());
    }

    @Test
    void nonzeroAnalyzerExitIsNotMaskedByMissingOutput() throws Exception {
        var command = command("import sys; sys.stderr.write('fixture failure\\n'); sys.exit(7)");
        assertThatThrownBy(() -> runner(new RunnerPolicy(environment())).execute(command))
                .isInstanceOf(IOException.class)
                .hasMessage("ANALYZER_EXECUTION_FAILED");
        var state = new ObjectMapper()
                .readTree(root.resolve("attempt/container-state.json").toFile());
        assertThat(state.path("ExitCode").asInt()).isEqualTo(7);
        assertThat(state.path("dockerExitCode").asInt()).isEqualTo(7);
        assertThat(state.path("Status").asText()).isEqualTo("exited");
        assertThat(root.resolve("attempt/analyzer.stderr.log")).hasContent("fixture failure\n");
        assertRemoved(command.executionId());
    }

    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("semantic.artifact-root", root.toString())
                .withProperty("semantic.runner.timeout-seconds", "30");
    }

    private DockerAnalyzerExecutionAdapter runner(RunnerPolicy policy) {
        var mapper = new ObjectMapper();
        return new DockerAnalyzerExecutionAdapter(policy, mapper, new AnalyzerOutputValidator(mapper));
    }

    private AnalyzerExecutionPort.Command command(String script) throws Exception {
        String tag = "semanticmap/runner-test:" + UUID.randomUUID();
        var image = new ImageFromDockerfile(tag, true)
                .withFileFromString(
                        "Dockerfile",
                        "FROM python:3.12.10\nENTRYPOINT "
                                + new ObjectMapper().writeValueAsString(List.of("python", "-c", script)) + "\n");
        Path source = Files.createDirectory(root.resolve("source"));
        return new AnalyzerExecutionPort.Command(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "java-spring",
                "0.1.0",
                image.get(),
                source,
                root.resolve("attempt"),
                RunnerPolicyTest.request(),
                () -> false);
    }

    private void assertRemoved(UUID id) throws Exception {
        var remaining = BoundedProcess.run(
                List.of("docker", "ps", "-a", "--filter", "label=semanticmap.execution=" + id, "--format", "{{.ID}}"),
                null,
                Map.of(),
                Duration.ofSeconds(10),
                65536,
                () -> false);
        assertThat(remaining.exitCode()).isZero();
        assertThat(remaining.text()).isEmpty();
    }
}
