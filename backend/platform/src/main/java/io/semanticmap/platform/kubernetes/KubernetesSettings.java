package io.semanticmap.platform.kubernetes;

import io.semanticmap.platform.runner.AnalyzerExecutionPort;
import io.semanticmap.platform.runner.RunnerPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "semantic.runner.mode", havingValue = "kubernetes")
public class KubernetesSettings {
    private final Environment environment;
    private final RunnerPolicy policy;
    private final String namespace, pvc, networkPolicy, installation, serviceAccount, runtimeClass, subPath;
    private final Set<String> allowedImages;

    public KubernetesSettings(Environment environment, RunnerPolicy policy) {
        this.environment = environment;
        this.policy = policy;
        namespace = dns(required("namespace"));
        pvc = dns(required("pvc"));
        networkPolicy = dns(required("network-policy"));
        installation = dns(property("installation", "semanticmap"));
        serviceAccount = dns(property("analyzer-service-account", "semanticmap-analyzer"));
        runtimeClass = property("runtime-class", "");
        if (!runtimeClass.isEmpty()) dns(runtimeClass);
        subPath = property("pvc-sub-path", "");
        if (!subPath.isEmpty() && !relative(subPath))
            throw new IllegalArgumentException("INVALID_KUBERNETES_PVC_SUB_PATH");
        allowedImages = Arrays.stream(required("allowed-images").split(","))
                .map(String::strip)
                .collect(Collectors.toUnmodifiableSet());
        for (String image : allowedImages) digest(image);
        cpuMillis();
        memoryMiB();
        ttlSeconds();
        commandTimeoutSeconds();
        pollMillis();
        if (policy.timeoutSeconds() < 1
                || policy.timeoutSeconds() > 3600
                || policy.maxOutputBytes() < 1
                || policy.maxOutputBytes() > 1073741824L)
            throw new IllegalArgumentException("INVALID_KUBERNETES_RUNNER_LIMITS");
        String kubeconfig = property("kubeconfig", "");
        if (!kubeconfig.isEmpty()
                && Path.of(kubeconfig).toAbsolutePath().normalize().startsWith(policy.artifactRoot()))
            throw new IllegalArgumentException("KUBECONFIG_MUST_NOT_BE_ON_ARTIFACT_VOLUME");
    }

    public void validate(AnalyzerExecutionPort.Command command) throws IOException {
        if (command == null
                || command.executionId() == null
                || command.attemptId() == null
                || command.cancelled() == null
                || command.request() == null
                || command.request().policy() == null) throw new IOException("INVALID_ANALYZER_COMMAND");
        policy.validateImage(command.imageReference());
        digest(command.imageReference());
        if (!allowedImages.contains(command.imageReference()))
            throw new IOException("KUBERNETES_ANALYZER_IMAGE_NOT_ALLOWED");
        var requestPolicy = command.request().policy();
        if (!"DENY".equals(requestPolicy.networkAccess())
                || requestPolicy.executeBuildScripts()
                || requestPolicy.resolveDependencies()
                || requestPolicy.maxDurationSeconds() < 1
                || requestPolicy.maxOutputBytes() < 1) throw new IOException("UNSUPPORTED_ANALYZER_POLICY");
        subPath(command.workspace());
        subPath(command.directory());
        if (!Files.isDirectory(command.workspace(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("ANALYZER_WORKSPACE_MISSING");
        Path workspace = command.workspace().toAbsolutePath().normalize(),
                directory = command.directory().toAbsolutePath().normalize();
        if (workspace.startsWith(directory) || directory.startsWith(workspace))
            throw new IOException("ANALYZER_MOUNTS_OVERLAP");
    }

    public String subPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), root = policy.artifactRoot();
        if (!absolute.startsWith(root) || absolute.equals(root))
            throw new IOException("ANALYZER_ARTIFACT_PATH_NOT_ALLOWED");
        RunnerPolicy.rejectLinks(root);
        RunnerPolicy.rejectLinks(absolute);
        String child = root.relativize(absolute).toString().replace('\\', '/');
        if (!relative(child)) throw new IOException("INVALID_KUBERNETES_PVC_SUB_PATH");
        return subPath.isEmpty() ? child : subPath + "/" + child;
    }

    public static String digest(String reference) {
        if (reference == null
                || reference.length() > 500
                || !reference.matches("[a-z0-9][a-z0-9._:/-]*@sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("KUBERNETES_REQUIRES_IMMUTABLE_IMAGE");
        return reference.substring(reference.lastIndexOf('@') + 1);
    }

    private static boolean relative(String path) {
        return path.length() <= 2048
                && !path.startsWith("/")
                && !path.endsWith("/")
                && !path.contains("\\")
                && !path.contains(":")
                && path.chars().noneMatch(c -> c < 32)
                && Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."));
    }

    private static String dns(String value) {
        if (value.length() > 63 || !value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
            throw new IllegalArgumentException("INVALID_KUBERNETES_RESOURCE_NAME");
        return value;
    }

    private String required(String key) {
        String value = property(key, "");
        if (value.isBlank())
            throw new IllegalArgumentException("MISSING_KUBERNETES_" + key.toUpperCase(java.util.Locale.ROOT));
        return value;
    }

    private String property(String key, String fallback) {
        return environment
                .getProperty("semantic.runner.kubernetes." + key, fallback)
                .strip();
    }

    private int number(String key, int fallback, int min, int max) {
        int value = environment.getProperty("semantic.runner.kubernetes." + key, Integer.class, fallback);
        if (value < min || value > max) throw new IllegalArgumentException("INVALID_KUBERNETES_RESOURCE_LIMIT");
        return value;
    }

    public List<String> commandPrefix() {
        var args = new java.util.ArrayList<>(List.of(
                property("kubectl", "kubectl"),
                "--namespace=" + namespace,
                "--request-timeout=" + commandTimeoutSeconds() + "s"));
        for (String key : List.of("context", "kubeconfig")) {
            String value = property(key, "");
            if (!value.isEmpty()) args.add("--" + key + "=" + value);
        }
        return args;
    }

    public String namespace() {
        return namespace;
    }

    public String pvc() {
        return pvc;
    }

    public String networkPolicy() {
        return networkPolicy;
    }

    public String installation() {
        return installation;
    }

    public String serviceAccount() {
        return serviceAccount;
    }

    public String runtimeClass() {
        return runtimeClass;
    }

    public int cpuMillis() {
        return number("cpu-millis", 2000, 100, 16000);
    }

    public int memoryMiB() {
        return number("memory-mib", 1024, 64, 16384);
    }

    public int ttlSeconds() {
        return number("ttl-seconds", 600, 120, 86400);
    }

    public int commandTimeoutSeconds() {
        return number("command-timeout-seconds", 15, 1, 60);
    }

    public int pollMillis() {
        return number("poll-millis", 1000, 100, 5000);
    }

    public int timeout(AnalyzerExecutionPort.Command command) {
        return Math.min(policy.timeoutSeconds(), command.request().policy().maxDurationSeconds());
    }

    public long outputLimit(AnalyzerExecutionPort.Command command) {
        return Math.min(policy.maxOutputBytes(), command.request().policy().maxOutputBytes());
    }
}
