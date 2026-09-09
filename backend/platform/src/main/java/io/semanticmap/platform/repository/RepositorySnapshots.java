package io.semanticmap.platform.repository;

import io.semanticmap.contract.Protocol;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class RepositorySnapshots {
    private final RepositoryPolicy policy;
    private final GitClient git;

    public RepositorySnapshots(RepositoryPolicy policy, GitClient git) {
        this.policy = policy;
        this.git = git;
    }

    public record Snapshot(
            Path workspace, String commitSha, String fingerprint, List<String> parents, Map<String, String> files) {}

    public record ChangedFile(String path, String changeType) {}

    public Snapshot create(
            UUID org,
            UUID project,
            UUID run,
            UUID attempt,
            String url,
            String ref,
            String credentials,
            BooleanSupplier cancelled)
            throws IOException {
        policy.validate(url);
        policy.validateRef(ref);
        Path directory = policy.workspaceRoot()
                .resolve(run.toString() + "_" + attempt.toString().substring(0, 8));
        RepositoryPolicy.rejectLinks(directory);
        Files.createDirectories(directory);
        Path workspace = directory.resolve("source");
        Files.createDirectory(workspace);
        var hashes = new TreeMap<String, String>();
        long[] totals = {0, 0};
        String commit = null;
        List<String> parents = List.of();
        try {
            if (policy.remote(url) || (!ref.equals("HEAD") && !ref.equals("WORKTREE"))) {
                Path bare = directory.resolve("git");
                var tree = git.fetch(url, ref, credentials, bare, cancelled);
                commit = tree.commit();
                parents = tree.parents();
                StringBuilder patterns = new StringBuilder();
                for (String name : List.of(".gitignore", ".semanticmapignore")) {
                    var entry = tree.entries().stream()
                            .filter(e -> e.path().equals(name))
                            .findFirst();
                    if (entry.isPresent()) {
                        byte[] content = git.blob(tree, entry.get(), cancelled);
                        if (content.length > 65536) throw new IOException("IGNORE_FILE_TOO_LARGE");
                        patterns.append(new String(content, java.nio.charset.StandardCharsets.UTF_8))
                                .append('\n');
                    }
                }
                IgnoreRules ignore = new IgnoreRules(patterns.toString());
                for (var entry : tree.entries()) {
                    ensureActive(cancelled);
                    Path target = safeChild(workspace, entry.path());
                    if (ignore.ignored(entry.path())) continue;
                    write(workspace, target, git.blob(tree, entry, cancelled), totals, hashes);
                }
                remove(bare);
            } else {
                Path source = policy.localRoot(url);
                if (source.startsWith(policy.workspaceRoot())) throw new IOException("WORKSPACE_CANNOT_BE_REPOSITORY");
                IgnoreRules ignore = IgnoreRules.load(source);
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    private long inspected;

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        ensureActive(cancelled);
                        if (++inspected > policy.maxFiles() * 4L) throw new IOException("REPOSITORY_ENTRY_LIMIT");
                        if (dir.startsWith(policy.workspaceRoot())
                                || (!dir.equals(source) && ignore.alwaysExcluded(relative(source, dir))))
                            return FileVisitResult.SKIP_SUBTREE;
                        RepositoryPolicy.rejectLinks(dir);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        ensureActive(cancelled);
                        if (++inspected > policy.maxFiles() * 4L) throw new IOException("REPOSITORY_ENTRY_LIMIT");
                        String name = relative(source, file);
                        if (ignore.ignored(name)) return FileVisitResult.CONTINUE;
                        if (attrs.isSymbolicLink() || attrs.isOther())
                            throw new IOException("SYMLINK_OR_SPECIAL_FILE_REJECTED");
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        if (attrs.size() > policy.maxFileBytes()) throw new IOException("REPOSITORY_FILE_SIZE_LIMIT");
                        RepositoryPolicy.rejectLinks(file);
                        byte[] bytes;
                        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                            bytes = input.readNBytes(Math.toIntExact(policy.maxFileBytes() + 1));
                        }
                        write(workspace, safeChild(workspace, name), bytes, totals, hashes);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            String fingerprint = fingerprint(hashes);
            if (commit == null) commit = "local-" + fingerprint.substring(7);
            return new Snapshot(workspace, commit, fingerprint, parents, Map.copyOf(hashes));
        } catch (IOException | RuntimeException e) {
            try {
                remove(directory);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private void write(Path root, Path target, byte[] bytes, long[] totals, Map<String, String> hashes)
            throws IOException {
        if (bytes.length > policy.maxFileBytes()) throw new IOException("REPOSITORY_FILE_SIZE_LIMIT");
        for (byte b : bytes) if (b == 0) return;
        if (++totals[0] > policy.maxFiles() || (totals[1] += bytes.length) > policy.maxBytes())
            throw new IOException("REPOSITORY_SIZE_LIMIT");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        hashes.put(relative(root, target), hashBytes(bytes));
    }

    public static String hashBytes(byte[] bytes) {
        try {
            return "sha256:"
                    + java.util.HexFormat.of()
                            .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String fingerprint(Map<String, String> hashes) {
        var value = new StringBuilder();
        new TreeMap<>(hashes)
                .forEach((path, hash) ->
                        value.append(path).append('\0').append(hash).append('\n'));
        return Protocol.hash(value.toString());
    }

    public List<ChangedFile> changedFiles(Snapshot snapshot, Path baseline) throws IOException {
        var before = new TreeMap<String, String>();
        if (baseline != null) {
            RepositoryPolicy.rejectLinks(baseline);
            if (!baseline.toAbsolutePath().normalize().startsWith(policy.workspaceRoot()))
                throw new IOException("BASELINE_PATH_NOT_ALLOWED");
            if (!Files.isDirectory(baseline)) throw new IOException("BASELINE_SOURCE_EXPIRED");
            try (var paths = Files.walk(baseline)) {
                for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .toList()) {
                    RepositoryPolicy.rejectLinks(path);
                    if (Files.size(path) > policy.maxFileBytes()) throw new IOException("BASELINE_FILE_SIZE_LIMIT");
                    before.put(relative(baseline, path), hashBytes(Files.readAllBytes(path)));
                }
            }
        }
        var changed = new ArrayList<ChangedFile>();
        snapshot.files().forEach((path, hash) -> {
            String previous = before.remove(path);
            if (previous == null) changed.add(new ChangedFile(path, "ADDED"));
            else if (!previous.equals(hash)) changed.add(new ChangedFile(path, "MODIFIED"));
        });
        before.keySet().forEach(path -> changed.add(new ChangedFile(path, "DELETED")));
        changed.sort(java.util.Comparator.comparing(ChangedFile::path));
        return List.copyOf(changed);
    }

    public void remove(Path directory) throws IOException {
        Path root = policy.workspaceRoot();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("CLEANUP_PATH_NOT_ALLOWED");
        RepositoryPolicy.rejectLinks(target);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static Path safeChild(Path root, String relative) throws IOException {
        if (relative.isBlank()
                || relative.contains("\\")
                || relative.contains(":")
                || relative.startsWith("/")
                || java.util.Arrays.asList(relative.split("/")).contains(".."))
            throw new IOException("REPOSITORY_PATH_TRAVERSAL");
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("REPOSITORY_PATH_TRAVERSAL");
        return path;
    }

    private static String relative(Path root, Path child) {
        return root.relativize(child).toString().replace('\\', '/');
    }

    private static void ensureActive(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("PROCESS_CANCELLED");
    }
}
