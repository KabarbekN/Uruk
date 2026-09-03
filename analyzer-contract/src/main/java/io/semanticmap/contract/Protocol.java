package io.semanticmap.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class Protocol {
    public static final String VERSION = "1.0";

    private Protocol() {}

    public record Evidence(
            String filePath, int startLine, int startColumn, int endLine, int endColumn, String snippetHash) {}

    public record Subject(String kind, String stableKey) {}

    public record Fact(
            String contractVersion,
            String factId,
            String kind,
            String stableKey,
            Subject subject,
            Map<String, Object> properties,
            String origin,
            double confidence,
            List<Evidence> evidence) {}

    public record Diagnostic(
            String code,
            String severity,
            String message,
            String filePath,
            Integer startLine,
            Map<String, Object> metadata) {}

    public record Component(String rootPath, List<String> languages, List<String> frameworks, String buildSystem) {}

    public record Revision(String commitSha, String branch) {}

    public record Policy(
            String networkAccess,
            boolean executeBuildScripts,
            boolean resolveDependencies,
            boolean includeTests,
            boolean includeGeneratedSources,
            int maxDurationSeconds,
            long maxOutputBytes) {}

    public record Request(
            String contractVersion,
            String analysisId,
            String organizationId,
            String projectId,
            Revision revision,
            Component component,
            List<String> requestedCapabilities,
            List<String> changedFiles,
            Policy policy) {}

    public static String hash(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String factId(String key) {
        return hash(key).substring(7);
    }
}
