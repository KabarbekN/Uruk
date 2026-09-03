package io.semanticmap.platform.graph.internal;

import io.semanticmap.contract.Protocol;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class EvidenceVerifier {
    public record Verified(Protocol.Evidence source, String snippet) {}

    /** A session belongs to one ingestion of one immutable workspace, never to a shared verifier. */
    public Session session(Path workspace) {
        return new Session(workspace);
    }

    public final class Session {
        private static final int MAX_ENTRIES = 1024;
        private static final long MAX_RETAINED_BYTES = 8L * 1024 * 1024;
        private final Path workspace;
        private final LinkedHashMap<String, Source> sources = new LinkedHashMap<>(16, 0.75f, true);
        private long retainedBytes;

        private Session(Path workspace) {
            this.workspace = workspace;
        }

        public Verified verify(Protocol.Evidence evidence) throws IOException {
            if (evidence == null || evidence.filePath() == null) throw invalid("EVIDENCE_PATH_MISSING");
            var cached = sources.get(evidence.filePath());
            if (cached != null) return verifyLines(cached.lines(), evidence);
            Path file = resolveSource(workspace, evidence);
            validateRange(evidence);
            var source = load(file, evidence.filePath());
            if (source == null) return EvidenceVerifier.this.verify(workspace, evidence);
            var result = verifyLines(source.lines(), evidence);
            while (sources.size() >= MAX_ENTRIES) evict();
            sources.put(evidence.filePath(), source);
            retainedBytes += source.bytes();
            return result;
        }

        private Source load(Path file, String key) {
            var content = new ArrayList<String>();
            long bytes = 512L + 2L * key.length();
            if (!makeRoom(bytes)) return null;
            try (var lines = openSource(file)) {
                BoundedLines.Line line;
                while ((line = lines.next()) != null) {
                    if (line.oversized() || line.invalidUtf8()) return null;
                    // Include UTF-16 storage, String/array overhead and spare ArrayList capacity.
                    bytes += 64L + 2L * line.text().length();
                    if (!makeRoom(bytes)) return null;
                    content.add(line.text());
                }
                return new Source(content, bytes);
            } catch (IOException ex) {
                // An unreadable/oversized suffix must not reject otherwise valid prefix evidence.
                return null;
            }
        }

        private boolean makeRoom(long bytes) {
            if (bytes > MAX_RETAINED_BYTES) return false;
            while (retainedBytes + bytes > MAX_RETAINED_BYTES) evict();
            return true;
        }

        private void evict() {
            retainedBytes -= sources.pollFirstEntry().getValue().bytes();
        }
    }

    private record Source(List<String> lines, long bytes) {}

    public Verified verify(Path workspace, Protocol.Evidence evidence) throws IOException {
        Path file = resolveSource(workspace, evidence);
        validateRange(evidence);
        var selected = new ArrayList<String>();
        int snippetSize = 0;
        try (var lines = openSource(file)) {
            for (int number = 1; number <= evidence.endLine(); number++) {
                var line = lines.next();
                if (line == null) throw invalid("INVALID_RANGE");
                if (line.oversized() || line.invalidUtf8()) throw invalid("SOURCE_ENCODING_OR_LINE_LIMIT");
                if (number >= evidence.startLine()) {
                    selected.add(line.text());
                    snippetSize += line.text().length() + 1;
                    if (snippetSize > 64 * 1024) throw invalid("SNIPPET_LIMIT");
                }
            }
        }
        return verifySelected(selected, evidence);
    }

    Path resolveSource(Path workspace, Protocol.Evidence evidence) throws IOException {
        if (evidence == null || evidence.filePath() == null) throw invalid("EVIDENCE_PATH_MISSING");
        String name = evidence.filePath().replace('\\', '/');
        if (name.isBlank() || name.startsWith("/") || name.contains(":")) throw invalid("PATH_ESCAPE");
        Path relative = Path.of(name);
        for (Path part : relative) if (part.toString().equals("..")) throw invalid("PATH_ESCAPE");
        Path root = workspace.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || file.equals(root)) throw invalid("PATH_ESCAPE");
        rejectLinks(root);
        rejectLinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw invalid("SOURCE_NOT_REGULAR_FILE");
        if (!file.toRealPath().startsWith(root.toRealPath())) throw invalid("PATH_ESCAPE");
        return file;
    }

    BoundedLines openSource(Path file) throws IOException {
        return new BoundedLines(
                new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)),
                64 * 1024,
                16L * 1024 * 1024);
    }

    private static void validateRange(Protocol.Evidence evidence) throws IOException {
        if (evidence.startLine() < 1
                || evidence.endLine() < evidence.startLine()
                || evidence.endLine() - evidence.startLine() > 2000
                || evidence.startColumn() < 1
                || evidence.endColumn() < 1) throw invalid("INVALID_RANGE");
    }

    private static Verified verifyLines(List<String> content, Protocol.Evidence evidence) throws IOException {
        validateRange(evidence);
        if (evidence.startLine() > content.size()) throw invalid("INVALID_RANGE");
        var selected = content.subList(evidence.startLine() - 1, Math.min(evidence.endLine(), content.size()));
        int snippetSize = 0;
        for (String line : selected) {
            snippetSize += line.length() + 1;
            if (snippetSize > 64 * 1024) throw invalid("SNIPPET_LIMIT");
        }
        if (evidence.endLine() > content.size()) throw invalid("INVALID_RANGE");
        return verifySelected(selected, evidence);
    }

    private static Verified verifySelected(List<String> selected, Protocol.Evidence evidence) throws IOException {
        if (evidence.startColumn() > selected.getFirst().length() + 1
                || evidence.endColumn() > selected.getLast().length() + 1
                || (evidence.startLine() == evidence.endLine() && evidence.endColumn() < evidence.startColumn()))
            throw invalid("INVALID_COLUMN_RANGE");
        String snippet = String.join("\n", selected);
        if (!Protocol.hash(snippet).equals(evidence.snippetHash())) throw invalid("SNIPPET_HASH_MISMATCH");
        return new Verified(evidence, snippet);
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw invalid("SYMLINK_REJECTED");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !current.toRealPath().equals(current.toAbsolutePath().normalize()))
                throw invalid("LINK_OR_REPARSE_POINT_REJECTED");
        }
    }

    private static IOException invalid(String reason) {
        return new IOException(reason);
    }
}
