package io.semanticmap.platform.repository;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RepositoryPolicy {
    private final Environment environment;

    public RepositoryPolicy(Environment environment) {
        this.environment = environment;
    }

    public Path workspaceRoot() {
        return Path.of(environment.getProperty("semantic.artifact-root", ".runtime/artifacts"))
                .toAbsolutePath()
                .normalize()
                .resolve("workspaces");
    }

    public int maxFiles() {
        return environment.getProperty("semantic.repository.max-files", Integer.class, 20000);
    }

    public long maxFileBytes() {
        return environment.getProperty("semantic.repository.max-file-bytes", Long.class, 4194304L);
    }

    public long maxBytes() {
        return environment.getProperty("semantic.repository.max-bytes", Long.class, 268435456L);
    }

    public int retentionDays() {
        return Math.max(1, environment.getProperty("semantic.repository.retention-days", Integer.class, 7));
    }

    public int timeoutSeconds() {
        return environment.getProperty("semantic.repository.timeout-seconds", Integer.class, 300);
    }

    public String property(String key) {
        return environment.getProperty(key);
    }

    public boolean production() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equals("prod") || p.equals("production"))
                || environment.getProperty("semantic.production", Boolean.class, false);
    }

    public void validateRef(String ref) {
        if (ref == null
                || ref.isBlank()
                || ref.length() > 200
                || ref.startsWith("-")
                || ref.contains("..")
                || ref.contains("@{")
                || !ref.matches("[A-Za-z0-9_./-]+")) throw new IllegalArgumentException("INVALID_REPOSITORY_REF");
    }

    public void validateCredentials(String reference) {
        if (reference != null && !reference.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("INVALID_CREDENTIAL_REFERENCE");
    }

    public boolean remote(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("ssh://"));
    }

    public void validate(String url) throws IOException {
        if (url == null || url.isBlank() || url.length() > 2048 || url.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("INVALID_REPOSITORY_URL");
        if (!remote(url)) {
            if (url.contains("://") || url.matches("^[^/\\\\:]+@.*"))
                throw new IllegalArgumentException("UNSUPPORTED_REPOSITORY_PROTOCOL");
            localRoot(url);
            return;
        }
        URI uri = URI.create(url);
        if (uri.getHost() == null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || (uri.getScheme().equals("https") && uri.getUserInfo() != null)
                || (uri.getScheme().equals("ssh")
                        && uri.getUserInfo() != null
                        && !uri.getUserInfo().matches("[a-zA-Z0-9_-]+")))
            throw new IllegalArgumentException("INVALID_REPOSITORY_URL");
        String allowed = environment.getProperty("semantic.repository-hosts", "");
        if (production() && allowed.isBlank())
            throw new IllegalArgumentException("PRODUCTION_REQUIRES_REPOSITORY_HOST_ALLOWLIST");
        if (!allowed.isBlank()
                && Arrays.stream(allowed.split(","))
                        .map(String::strip)
                        .noneMatch(h -> h.equalsIgnoreCase(uri.getHost())))
            throw new IllegalArgumentException("REPOSITORY_HOST_NOT_ALLOWED");
    }

    public Path localRoot(String value) throws IOException {
        if (production()) throw new IllegalArgumentException("LOCAL_REPOSITORIES_DISABLED");
        Path path = Path.of(value).toAbsolutePath().normalize();
        rejectLinks(path);
        Path real = path.toRealPath();
        List<Path> roots = Arrays.stream(environment
                        .getProperty("semantic.repository-roots", System.getProperty("user.dir"))
                        .split(","))
                .filter(s -> !s.isBlank())
                .map(s -> Path.of(s.strip()).toAbsolutePath().normalize())
                .toList();
        boolean permitted = false;
        for (Path root : roots) if (Files.isDirectory(root) && real.startsWith(root.toRealPath())) permitted = true;
        if (!permitted || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("REPOSITORY_PATH_NOT_ALLOWED");
        return real;
    }

    public static void rejectLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)
                    || (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                            && Files.readAttributes(
                                            current,
                                            java.nio.file.attribute.BasicFileAttributes.class,
                                            LinkOption.NOFOLLOW_LINKS)
                                    .isOther())) throw new IOException("SYMLINK_OR_SPECIAL_PATH_REJECTED");
        }
    }
}
