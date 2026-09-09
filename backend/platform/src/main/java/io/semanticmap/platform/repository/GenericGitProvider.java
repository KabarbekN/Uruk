package io.semanticmap.platform.repository;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GenericGitProvider implements GitProvider {

    private static final Logger log = LoggerFactory.getLogger(GenericGitProvider.class);
    private final GitClient gitClient;

    public GenericGitProvider(GitClient gitClient) {
        this.gitClient = gitClient;
    }

    @Override
    public String name() {
        return "GENERIC_GIT";
    }

    @Override
    public boolean supports(String url) {
        if (url == null || url.isBlank()) return false;
        String trimmed = url.trim().toLowerCase();
        return trimmed.startsWith("https://")
                || trimmed.startsWith("http://")
                || trimmed.startsWith("ssh://")
                || trimmed.startsWith("git@");
    }

    @Override
    public String normalizeUrl(String rawUrl) {
        if (rawUrl == null) return "";
        return rawUrl.trim().replaceAll("/+$", "").replaceAll("\\.git$", "");
    }

    @Override
    public ResolvedRepository resolve(String normalizedUrl, String token) {
        String cleanUrl = normalizeUrl(normalizedUrl);
        String owner = "git";
        String repoName = "repository";

        String[] parts = cleanUrl.split("/");
        if (parts.length >= 2) {
            owner = parts[parts.length - 2];
            repoName = parts[parts.length - 1];
        } else if (parts.length == 1) {
            repoName = parts[0];
        }
        String fullName = owner + "/" + repoName;

        // 1. Probe publicly
        try {
            List<String> branches = gitClient.listBranches(cleanUrl, null);
            String defaultBranch = pickDefaultBranch(branches);
            return new ResolvedRepository(
                    name(), owner, repoName, fullName, cleanUrl, "PUBLIC", true, defaultBranch, branches, null);
        } catch (Exception publicErr) {
            log.debug("Generic Git public probe failed for {}: {}", cleanUrl, publicErr.getMessage());
        }

        // 2. Probe with token
        if (token != null && !token.isBlank()) {
            try {
                List<String> branches = gitClient.listBranches(cleanUrl, token.trim());
                String defaultBranch = pickDefaultBranch(branches);
                return new ResolvedRepository(
                        name(), owner, repoName, fullName, cleanUrl, "PRIVATE", true, defaultBranch, branches, null);
            } catch (Exception authErr) {
                log.warn("Generic Git authenticated probe failed for {}: {}", cleanUrl, authErr.getMessage());
                return new ResolvedRepository(
                        name(),
                        owner,
                        repoName,
                        fullName,
                        cleanUrl,
                        "ACCESS_DENIED",
                        false,
                        "main",
                        List.of(),
                        "Доступ к удаленному Git-репозиторию отклонен");
            }
        }

        return new ResolvedRepository(
                name(),
                owner,
                repoName,
                fullName,
                cleanUrl,
                "PRIVATE",
                false,
                "main",
                List.of(),
                "Git-репозиторий требует аутентификации");
    }

    @Override
    public List<RemoteRepositorySummary> listRepositories(String token, int page, int perPage) throws IOException {
        return List.of();
    }

    private String pickDefaultBranch(List<String> branches) {
        if (branches == null || branches.isEmpty()) return "main";
        if (branches.contains("main")) return "main";
        if (branches.contains("master")) return "master";
        return branches.getFirst();
    }
}
