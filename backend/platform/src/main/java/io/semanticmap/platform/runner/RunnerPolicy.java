package io.semanticmap.platform.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RunnerPolicy {
    private final Environment environment;

    public RunnerPolicy(Environment environment) {
        this.environment = environment;
    }

    public int timeoutSeconds() {
        return environment.getProperty("semantic.runner.timeout-seconds", Integer.class, 600);
    }

    public long maxOutputBytes() {
        return environment.getProperty("semantic.runner.max-output-bytes", Long.class, 134217728L);
    }

    public String docker() {
        return environment.getProperty("semantic.runner.docker", "docker");
    }

    public String namespace() {
        String root = environment.getProperty(
                "semantic.artifact-host-path", artifactRoot().toString());
        if (root.isBlank()) root = artifactRoot().toString();
        return io.semanticmap.contract.Protocol.hash(root.replace('\\', '/').replaceAll("/+$", ""))
                .substring(7);
    }

    public Path artifactRoot() {
        return Path.of(environment.getProperty("semantic.artifact-root", ".runtime/artifacts"))
                .toAbsolutePath()
                .normalize();
    }

    public boolean production() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equals("prod") || p.equals("production"))
                || environment.getProperty("semantic.production", Boolean.class, false);
    }

    public void validateImage(String image) {
        if (image == null
                || image.length() > 500
                || !image.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]+")
                || image.endsWith(":latest")) throw new IllegalArgumentException("INVALID_ANALYZER_IMAGE");
        if (production() && !image.matches("[a-zA-Z0-9_./:-]+@sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("PRODUCTION_REQUIRES_IMAGE_DIGEST");
        if (!image.contains("@") && image.lastIndexOf(':') <= image.lastIndexOf('/'))
            throw new IllegalArgumentException("ANALYZER_IMAGE_VERSION_REQUIRED");
    }

    public String hostPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = artifactRoot();
        if (!absolute.startsWith(root) || absolute.equals(root))
            throw new IOException("ANALYZER_ARTIFACT_PATH_NOT_ALLOWED");
        rejectLinks(absolute);
        String configured = environment.getProperty("semantic.artifact-host-path", "");
        String host = configured.isBlank()
                ? absolute.toString()
                : configured.replace('\\', '/').replaceAll("/+$", "") + "/"
                        + root.relativize(absolute).toString().replace('\\', '/');
        if (host.contains(",")
                || host.chars().anyMatch(c -> c < 32)
                || (!host.startsWith("/") && !host.matches("^[A-Za-z]:[/\\\\].*")))
            throw new IOException("INVALID_ARTIFACT_HOST_PATH");
        return host.replace('\\', '/');
    }

    public List<String> createCommand(
            AnalyzerExecutionPort.Command command, String name, String digest, Path input, Path output)
            throws IOException {
        double cpus = environment.getProperty("semantic.runner.cpus", Double.class, 2.0);
        int memory = environment.getProperty("semantic.runner.memory-mb", Integer.class, 1024);
        int pids = environment.getProperty("semantic.runner.pids", Integer.class, 128);
        if (cpus <= 0 || cpus > 16 || memory < 64 || memory > 16384 || pids < 8 || pids > 1024)
            throw new IllegalArgumentException("INVALID_RUNNER_RESOURCE_LIMITS");
        if (!digest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("INVALID_RESOLVED_IMAGE_ID");
        return new ArrayList<>(List.of(
                docker(),
                "create",
                "--name",
                name,
                "--label",
                "semanticmap.execution=" + command.executionId(),
                "--label",
                "semanticmap.managed=true",
                "--label",
                "semanticmap.scope=" + namespace(),
                "--read-only",
                "--user",
                "65532:65532",
                "--network",
                "none",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--pids-limit",
                Integer.toString(pids),
                "--cpus",
                Double.toString(cpus),
                "--memory",
                memory + "m",
                "--memory-swap",
                memory + "m",
                "--ulimit",
                "nofile=256:256",
                "--ulimit",
                "fsize=" + maxOutputBytes() + ":" + maxOutputBytes(),
                "--tmpfs",
                "/tmp:rw,noexec,nosuid,size=64m",
                "--log-driver",
                "local",
                "--log-opt",
                "max-size=5m",
                "--log-opt",
                "max-file=1",
                "--log-opt",
                "compress=false",
                "--mount",
                "type=bind,source=" + hostPath(command.workspace()) + ",target=/workspace,readonly",
                "--mount",
                "type=bind,source=" + hostPath(input) + ",target=/input,readonly",
                "--mount",
                "type=bind,source=" + hostPath(output) + ",target=/output",
                "--env",
                "SEMANTIC_IMAGE_DIGEST=" + digest,
                "--env",
                "SEMANTIC_ANALYZER_IMAGE_DIGEST=" + digest,
                digest,
                "analyze",
                "--request",
                "/input/request.json",
                "--output",
                "/output"));
    }

    public static void rejectLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)
                    || (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                            && Files.readAttributes(
                                            cursor,
                                            java.nio.file.attribute.BasicFileAttributes.class,
                                            LinkOption.NOFOLLOW_LINKS)
                                    .isOther())) throw new IOException("ANALYZER_SYMLINK_REJECTED");
        }
    }
}
