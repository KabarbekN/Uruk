package io.semanticmap.platform.repository;

import java.io.IOException;
import java.util.List;

public interface GitProvider {
    String name();

    boolean supports(String url);

    String normalizeUrl(String rawUrl);

    ResolvedRepository resolve(String normalizedUrl, String token);

    List<RemoteRepositorySummary> listRepositories(String token, int page, int perPage) throws IOException;

    record ResolvedRepository(
            String provider,
            String owner,
            String name,
            String fullName,
            String normalizedUrl,
            String visibility, // "PUBLIC", "PRIVATE", "ACCESS_DENIED", "NOT_FOUND"
            boolean hasAccess,
            String defaultBranch,
            List<String> branches,
            String errorMessage) {}

    record RemoteRepositorySummary(
            String id,
            String name,
            String fullName,
            String url,
            String defaultBranch,
            boolean isPrivate,
            String description,
            String language) {}
}
