package io.semanticmap.platform.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "semantic.runner.mode", havingValue = "docker", matchIfMissing = true)
public class DockerAnalyzerExecutionAdapter implements AnalyzerExecutionPort {
    private final RunnerPolicy policy;
    private final ObjectMapper mapper;
    private final AnalyzerOutputValidator validator;

    public DockerAnalyzerExecutionAdapter(RunnerPolicy policy, ObjectMapper mapper, AnalyzerOutputValidator validator) {
        this.policy = policy;
        this.mapper = mapper;
        this.validator = validator;
    }

    @Override
    public Result execute(Command command) throws IOException {
        policy.validateImage(command.imageReference());
        if (command.request().policy().executeBuildScripts()
                || command.request().policy().resolveDependencies()
                || !command.request().policy().networkAccess().equals("DENY"))
            throw new IOException("UNSUPPORTED_ANALYZER_POLICY");
        policy.hostPath(command.directory());
        Files.createDirectories(command.directory());
        Path input = Files.createDirectory(command.directory().resolve("input"));
        Path output = Files.createDirectory(command.directory().resolve("output"));
        if (Files.getFileStore(output).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwxrwxrwx"));
        Files.write(
                input.resolve("request.json"),
                mapper.writeValueAsBytes(command.request()),
                StandardOpenOption.CREATE_NEW);
        String image = resolveImage(command.imageReference(), command.cancelled());
        if (command.cancelled().getAsBoolean()) throw new IOException("PROCESS_CANCELLED");
        String name = "semanticmap-" + command.executionId() + "-" + command.attemptId();
        AtomicBoolean oversized = new AtomicBoolean();
        BooleanSupplier cancelled = () -> {
            if (command.cancelled().getAsBoolean()) return true;
            try {
                checkOutput(output);
                return false;
            } catch (IOException e) {
                oversized.set(true);
                return true;
            }
        };
        try {
            var create = capture(
                    policy.createCommand(command, name, image, input, output),
                    Duration.ofSeconds(30),
                    65536,
                    command.cancelled());
            Files.write(
                    command.directory().resolve("docker-create.stderr.log"),
                    create.stderr(),
                    StandardOpenOption.CREATE_NEW);
            if (create.exitCode() != 0) throw new IOException("CONTAINER_CREATE_FAILED");
            var started = capture(
                    List.of(policy.docker(), "start", "--attach", name),
                    Duration.ofSeconds(policy.timeoutSeconds()),
                    4194304,
                    cancelled);
            if (oversized.get()) throw new IOException("ANALYZER_OUTPUT_LIMIT");
            Files.write(command.directory().resolve("analyzer.log"), started.stdout(), StandardOpenOption.CREATE_NEW);
            Files.write(
                    command.directory().resolve("analyzer.stderr.log"),
                    started.stderr(),
                    StandardOpenOption.CREATE_NEW);
            var state = recordState(name, command.directory(), started.exitCode());
            if (state.path("Status").asText().equals("created")
                    || !state.path("Error").asText().isEmpty()) throw new IOException("CONTAINER_START_FAILED");
            if (state.path("Running").asBoolean()) throw new IOException("CONTAINER_ATTACH_FAILED");
            if (state.path("OOMKilled").asBoolean()) throw new IOException("ANALYZER_OUT_OF_MEMORY");
            int exitCode = state.path("ExitCode").asInt(-1);
            if ((started.exitCode() != 0 && started.exitCode() != 10) || (exitCode != 0 && exitCode != 10))
                throw new IOException("ANALYZER_EXECUTION_FAILED");
            checkOutput(output);
            return validator.validate(command, output, image, exitCode == 10 || started.exitCode() == 10 ? 10 : 0);
        } catch (IOException e) {
            if (!Files.exists(command.directory().resolve("container-state.json"))) {
                try {
                    recordState(name, command.directory(), null);
                } catch (IOException diagnosticFailure) {
                    e.addSuppressed(diagnosticFailure);
                }
            }
            if (oversized.get()) throw new IOException("ANALYZER_OUTPUT_LIMIT", e);
            throw e;
        } finally {
            var removed =
                    run(List.of(policy.docker(), "rm", "--force", name), Duration.ofSeconds(20), 65536, () -> false);
            if (removed.exitCode() != 0) {
                var inspect = run(
                        List.of(policy.docker(), "container", "inspect", "--format", "{{.Id}}", name),
                        Duration.ofSeconds(10),
                        65536,
                        () -> false);
                if (inspect.exitCode() == 0) throw new IOException("CONTAINER_CLEANUP_FAILED");
            }
        }
    }

    private com.fasterxml.jackson.databind.JsonNode recordState(String name, Path directory, Integer dockerExitCode)
            throws IOException {
        var result = run(
                List.of(policy.docker(), "container", "inspect", "--format", "{{json .State}}", name),
                Duration.ofSeconds(10),
                65536,
                () -> false);
        if (result.exitCode() != 0) throw new IOException("CONTAINER_INSPECT_FAILED");
        var state = mapper.readTree(result.stdout());
        if (state == null || !state.isObject()) throw new IOException("INVALID_CONTAINER_STATE");
        if (dockerExitCode != null)
            ((com.fasterxml.jackson.databind.node.ObjectNode) state).put("dockerExitCode", dockerExitCode);
        Files.write(
                directory.resolve("container-state.json"),
                mapper.writeValueAsBytes(state),
                StandardOpenOption.CREATE_NEW);
        return state;
    }

    public String resolveImage(String reference, BooleanSupplier cancelled) throws IOException {
        var inspected = run(
                List.of(policy.docker(), "image", "inspect", "--format", "{{.Id}}", reference),
                Duration.ofSeconds(30),
                65536,
                cancelled);
        if (inspected.exitCode() != 0) {
            var pull = run(List.of(policy.docker(), "pull", reference), Duration.ofSeconds(300), 4194304, cancelled);
            if (pull.exitCode() != 0) throw new IOException("ANALYZER_IMAGE_PULL_FAILED");
            inspected = run(
                    List.of(policy.docker(), "image", "inspect", "--format", "{{.Id}}", reference),
                    Duration.ofSeconds(30),
                    65536,
                    cancelled);
        }
        String id = inspected.text();
        if (inspected.exitCode() != 0 || !id.matches("sha256:[0-9a-f]{64}"))
            throw new IOException("ANALYZER_IMAGE_UNRESOLVED");
        if (reference.contains("@sha256:")) {
            var repoDigests = run(
                    List.of(policy.docker(), "image", "inspect", "--format", "{{json .RepoDigests}}", id),
                    Duration.ofSeconds(30),
                    65536,
                    cancelled);
            var node = mapper.readTree(repoDigests.stdout());
            boolean found = false;
            if (node != null && node.isArray())
                for (var digest : node) if (digest.asText().equals(reference)) found = true;
            if (!found) throw new IOException("ANALYZER_IMAGE_DIGEST_UNVERIFIED");
        }
        return id;
    }

    @Override
    public void cancel(UUID executionId) throws IOException {
        var containers = run(
                List.of(
                        policy.docker(),
                        "ps",
                        "--all",
                        "--filter",
                        "label=semanticmap.execution=" + executionId,
                        "--format",
                        "{{.ID}}"),
                Duration.ofSeconds(20),
                65536,
                () -> false);
        if (containers.exitCode() != 0) throw new IOException("CONTAINER_LIST_FAILED");
        for (String id : containers.text().split("\\R")) {
            if (id.isBlank()) continue;
            if (!id.matches("[a-f0-9]{12,64}")) throw new IOException("INVALID_CONTAINER_ID");
            var removed =
                    run(List.of(policy.docker(), "rm", "--force", id), Duration.ofSeconds(20), 65536, () -> false);
            if (removed.exitCode() != 0) throw new IOException("CONTAINER_CLEANUP_FAILED");
        }
    }

    @Override
    public List<RunningExecution> running() throws IOException {
        var result = run(
                List.of(
                        policy.docker(),
                        "ps",
                        "--all",
                        "--filter",
                        "label=semanticmap.managed=true",
                        "--filter",
                        "label=semanticmap.scope=" + policy.namespace(),
                        "--format",
                        "{{.Names}}"),
                Duration.ofSeconds(20),
                1048576,
                () -> false);
        if (result.exitCode() != 0) throw new IOException("CONTAINER_LIST_FAILED");
        var names = java.util.regex.Pattern.compile("semanticmap-([a-f0-9-]{36})-([a-f0-9-]{36})");
        var executions = new java.util.ArrayList<RunningExecution>();
        for (String line : result.text().split("\\R")) {
            var match = names.matcher(line);
            if (match.matches())
                executions.add(new RunningExecution(UUID.fromString(match.group(1)), UUID.fromString(match.group(2))));
        }
        return List.copyOf(executions);
    }

    @Override
    public void cancelAttempt(RunningExecution execution) throws IOException {
        String name = "semanticmap-" + execution.executionId() + "-" + execution.attemptId();
        var result = run(List.of(policy.docker(), "rm", "--force", name), Duration.ofSeconds(20), 65536, () -> false);
        if (result.exitCode() != 0) {
            var inspect = run(
                    List.of(policy.docker(), "container", "inspect", name), Duration.ofSeconds(10), 65536, () -> false);
            if (inspect.exitCode() == 0) throw new IOException("CONTAINER_CLEANUP_FAILED");
        }
    }

    private void checkOutput(Path output) throws IOException {
        AnalyzerOutputBudget.check(output, policy.maxOutputBytes());
    }

    private static BoundedProcess.Result run(
            List<String> command, Duration timeout, long max, BooleanSupplier cancelled) throws IOException {
        return BoundedProcess.run(command, null, Map.of(), timeout, max, cancelled);
    }

    private static BoundedProcess.CapturedResult capture(
            List<String> command, Duration timeout, long max, BooleanSupplier cancelled) throws IOException {
        return BoundedProcess.capture(command, null, Map.of(), timeout, max, cancelled);
    }
}
