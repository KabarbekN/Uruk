package io.semanticmap.platform.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GitLabProvider implements GitProvider {

    private static final Logger log = LoggerFactory.getLogger(GitLabProvider.class);
    private static final Pattern GITLAB_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?gitlab\\.com/([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");

    private final GitClient gitClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GitLabProvider(GitClient gitClient) {
        this.gitClient = gitClient;
    }

    @Override
    public String name() {
        return "GITLAB";
    }

    @Override
    public boolean supports(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase();
        return lower.contains("gitlab.com") || lower.contains("gitlab");
    }

    @Override
    public String normalizeUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String trimmed = rawUrl.trim();
        Matcher matcher = GITLAB_URL_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return "https://gitlab.com/" + matcher.group(1) + "/" + matcher.group(2);
        }
        return trimmed.replaceAll("/+$", "").replaceAll("\\.git$", "");
    }

    @Override
    public ResolvedRepository resolve(String normalizedUrl, String token) {
        String cleanUrl = normalizeUrl(normalizedUrl);
        Matcher matcher = GITLAB_URL_PATTERN.matcher(cleanUrl);

        String owner = "unknown";
        String repoName = "repository";
        if (matcher.matches()) {
            owner = matcher.group(1);
            repoName = matcher.group(2);
        } else {
            String[] parts = cleanUrl.split("/");
            if (parts.length >= 2) {
                owner = parts[parts.length - 2];
                repoName = parts[parts.length - 1];
            }
        }
        String fullName = owner + "/" + repoName;

        // 1. Probe publicly without credentials
        try {
            List<String> branches = gitClient.listBranches(cleanUrl, null);
            String defaultBranch = pickDefaultBranch(branches);
            return new ResolvedRepository(
                    name(), owner, repoName, fullName, cleanUrl, "PUBLIC", true, defaultBranch, branches, null);
        } catch (Exception publicErr) {
            log.debug("GitLab public probe failed for {}: {}", cleanUrl, publicErr.getMessage());
        }

        // 2. Probe with credentials if provided
        if (token != null && !token.isBlank()) {
            try {
                List<String> branches = gitClient.listBranches(cleanUrl, token.trim());
                String defaultBranch = pickDefaultBranch(branches);
                return new ResolvedRepository(
                        name(), owner, repoName, fullName, cleanUrl, "PRIVATE", true, defaultBranch, branches, null);
            } catch (Exception authErr) {
                log.warn("GitLab authenticated probe failed for {}: {}", cleanUrl, authErr.getMessage());
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
                        "Доступ к приватному репозиторию GitLab отклонен");
            }
        }

        // 3. Private or needs auth
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
                "Репозиторий GitLab приватный или требует авторизации");
    }

    @Override
    public List<RemoteRepositorySummary> listRepositories(String token, int page, int perPage) throws IOException {
        if (token == null || token.isBlank()) {
            return List.of();
        }
        try {
            int p = Math.max(1, page);
            int size = Math.min(Math.max(1, perPage), 100);
            String apiUrl =
                    "https://gitlab.com/api/v4/projects?membership=true&simple=true&per_page=" + size + "&page=" + p;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token.trim())
                    .header("User-Agent", "SemanticBusinessMap")
                    .GET()
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("GitLab list projects returned status {}: {}", res.statusCode(), res.body());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(res.body());
            var list = new ArrayList<RemoteRepositorySummary>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    boolean isPrivate =
                            !"public".equalsIgnoreCase(item.path("visibility").asText("private"));
                    list.add(new RemoteRepositorySummary(
                            String.valueOf(item.path("id").asLong()),
                            item.path("name").asText(""),
                            item.path("path_with_namespace").asText(""),
                            item.path("web_url").asText(""),
                            item.path("default_branch").asText("main"),
                            isPrivate,
                            item.path("description").asText(""),
                            ""));
                }
            }
            return list;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted listing GitLab repositories", e);
        }
    }

    private String pickDefaultBranch(List<String> branches) {
        if (branches == null || branches.isEmpty()) return "main";
        if (branches.contains("main")) return "main";
        if (branches.contains("master")) return "master";
        return branches.getFirst();
    }
}
