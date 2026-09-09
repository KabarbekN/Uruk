package io.semanticmap.analyzer.java;

import io.semanticmap.contract.Protocol;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded discovery happens before parser allocation and never descends into excluded trees. */
final class SourceDiscovery {
    private static final int MAX_FILE_BYTES = 2_097_152;
    private static final long MAX_SOURCE_BYTES = 67_108_864;
    private static final Set<String> EXCLUDED =
            Set.of(".git", ".gradle", ".idea", "node_modules", ".mvn", ".deps", "__pycache__");
    private static final Set<String> GENERATED = Set.of("target", "build", "dist", "generated", "generated-sources");

    private record Directory(Path path, int depth) {}

    private record IgnoreRule(Pattern pattern, boolean include, boolean directoryOnly) {}

    private final int maxEntries;
    private final int maxDepth;

    SourceDiscovery() {
        this(100_000, 64);
    }

    SourceDiscovery(int maxEntries, int maxDepth) {
        this.maxEntries = maxEntries;
        this.maxDepth = maxDepth;
    }

    Map<String, String> read(Path root, Path component, Protocol.Policy policy, AnalysisModel model)
            throws IOException {
        for (Path parent = component; parent != null && parent.startsWith(root); parent = parent.getParent()) {
            BasicFileAttributes attributes = attributes(parent);
            if (attributes.isSymbolicLink() || attributes.isOther())
                throw new AnalyzerFailure(50, "SOURCE_PATH_ESCAPE", "Component traverses a source link");
        }
        List<IgnoreRule> rules = ignoreRules(root);
        Map<String, String> sources = new TreeMap<>();
        for (Path parent = component; !parent.equals(root); parent = parent.getParent()) {
            String relative = root.relativize(parent).toString().replace('\\', '/');
            if (excluded(relative, policy) || ignored(relative, true, rules)) return sources;
        }
        Deque<Directory> pending = new ArrayDeque<>();
        pending.push(new Directory(component, 0));
        long bytes = 0;
        int visited = 0;
        while (!pending.isEmpty()) {
            interrupted();
            Directory directory = pending.pop();
            if (directory.depth() > maxDepth)
                throw new AnalyzerFailure(50, "DEPTH_LIMIT", "Directory nesting exceeds " + maxDepth + " levels");
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> scan = Files.newDirectoryStream(directory.path())) {
                for (Path entry : scan) {
                    interrupted();
                    if (++visited > maxEntries)
                        throw new AnalyzerFailure(50, "ENTRY_LIMIT", "Repository exceeds entry budget");
                    entries.add(entry);
                }
            }
            entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path entry : entries) {
                interrupted();
                String relative = root.relativize(entry).toString().replace('\\', '/');
                BasicFileAttributes attributes = attributes(entry);
                if (excluded(relative, policy) || ignored(relative, attributes.isDirectory(), rules)) continue;
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    diagnose(model, "SOURCE_PATH_ESCAPE", "Source link or special entry was not followed", relative);
                    continue;
                }
                if (attributes.isDirectory()) {
                    pending.push(new Directory(entry, directory.depth() + 1));
                    continue;
                }
                if (!entry.getFileName().toString().endsWith(".java")) continue;
                if (!attributes.isRegularFile())
                    throw new AnalyzerFailure(50, "SPECIAL_FILE_REJECTED", "Only regular source files can be read");
                if (++model.discovered > 5000 || attributes.size() > MAX_FILE_BYTES)
                    throw new AnalyzerFailure(50, "SOURCE_LIMIT", "Static source input limit exceeded");
                byte[] data = readBounded(entry, MAX_FILE_BYTES);
                bytes += data.length;
                if (bytes > MAX_SOURCE_BYTES)
                    throw new AnalyzerFailure(50, "SOURCE_LIMIT", "Static source input exceeds 64 MiB");
                try {
                    String text = StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(ByteBuffer.wrap(data))
                            .toString();
                    if (text.indexOf('\0') >= 0) {
                        diagnose(model, "INVALID_ENCODING", "NUL byte in Java source", relative);
                    } else sources.put(relative, text);
                } catch (CharacterCodingException error) {
                    diagnose(model, "INVALID_ENCODING", "Java source is not valid UTF-8", relative);
                }
            }
        }
        return sources;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static byte[] readBounded(Path path, int limit) throws IOException {
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate(limit + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) interrupted();
            if (buffer.position() > limit)
                throw new AnalyzerFailure(50, "SOURCE_LIMIT", "Input grew beyond its byte limit");
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    private static void interrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new AnalyzerFailure(50, "DURATION_LIMIT", "Source discovery was cancelled");
    }

    private static void diagnose(AnalysisModel model, String code, String message, String path) {
        model.failed++;
        if (model.diagnostics.size() < 100)
            model.diagnostics.add(new Protocol.Diagnostic(code, "WARNING", message, path, null, Map.of()));
    }

    private static boolean excluded(String path, Protocol.Policy policy) {
        List<String> parts = Arrays.asList(path.split("/"));
        if (parts.stream().anyMatch(EXCLUDED::contains)) return true;
        if (!policy.includeTests()
                && parts.stream()
                        .anyMatch(part -> Set.of("test", "tests", "__tests__").contains(part))) return true;
        return !policy.includeGeneratedSources() && parts.stream().anyMatch(GENERATED::contains);
    }

    private static List<IgnoreRule> ignoreRules(Path root) throws IOException {
        Path file = root.resolve(".semanticmapignore");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!attributes(file).isRegularFile())
            throw new AnalyzerFailure(50, "SOURCE_PATH_ESCAPE", ".semanticmapignore must be a regular file");
        if (Files.size(file) > 65_536)
            throw new AnalyzerFailure(50, "IGNORE_LIMIT", ".semanticmapignore exceeds 64 KiB");
        String content = StandardCharsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(readBounded(file, 65_536)))
                .toString();
        List<IgnoreRule> rules = new ArrayList<>();
        for (String line : content.replaceFirst("^\\uFEFF", "").split("\\R")) {
            String value = line.stripTrailing();
            if (value.isEmpty() || value.startsWith("#")) continue;
            boolean include = value.startsWith("!");
            if (include) value = value.substring(1);
            if (value.isEmpty()) continue;
            if (rules.size() >= 1024 || value.length() > 1024)
                throw new AnalyzerFailure(50, "IGNORE_LIMIT", "Ignore pattern budget exceeded");
            boolean directoryOnly = value.endsWith("/");
            if (directoryOnly) value = value.substring(0, value.length() - 1);
            boolean anchored = value.startsWith("/");
            if (anchored) value = value.substring(1);
            StringBuilder regex = new StringBuilder(anchored || value.contains("/") ? "^" : "^(?:.*/)?");
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (character == '\\' && i + 1 < value.length()) {
                    regex.append(Pattern.quote(String.valueOf(value.charAt(++i))));
                } else if (character == '*' && i + 1 < value.length() && value.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < value.length() && value.charAt(i + 1) == '/') {
                        regex.append("(?:.*/)?");
                        i++;
                    } else regex.append(".*");
                } else if (character == '*') regex.append("[^/]*");
                else if (character == '?') regex.append("[^/]");
                else regex.append(Pattern.quote(String.valueOf(character)));
            }
            rules.add(new IgnoreRule(Pattern.compile(regex + "$"), include, directoryOnly));
        }
        return rules;
    }

    private static boolean ignored(String path, boolean directory, List<IgnoreRule> rules) {
        boolean ignored = false;
        for (IgnoreRule rule : rules)
            if ((!rule.directoryOnly() || directory)
                    && rule.pattern().matcher(path).matches()) ignored = !rule.include();
        return ignored;
    }
}
