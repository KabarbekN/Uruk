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
public class GitHubProvider implements GitProvider {

    private static final Logger log = LoggerFactory.getLogger(GitHubProvider.class);
    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");

    private final GitClient gitClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GitHubProvider(GitClient gitClient) {
        this.gitClient = gitClient;
    }

    @Override
    public String name() {
        return "GITHUB";
    }

    @Override
    public boolean supports(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase();
        return lower.contains("github.com");
    }

    @Override
    public String normalizeUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String trimmed = rawUrl.trim();
        Matcher matcher = GITHUB_URL_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return "https://github.com/" + matcher.group(1) + "/" + matcher.group(2);
        }
        return trimmed.replaceAll("/+$", "").replaceAll("\\.git$", "");
    }

    @Override
    public ResolvedRepository resolve(String normalizedUrl, String token) {
        String cleanUrl = normalizeUrl(normalizedUrl);
        Matcher matcher = GITHUB_URL_PATTERN.matcher(cleanUrl);

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

        // 1. Probe publicly without credentials first
        try {
            List<String> branches = gitClient.listBranches(cleanUrl, null);
            String defaultBranch = pickDefaultBranch(branches);
            return new ResolvedRepository(
                    name(), owner, repoName, fullName, cleanUrl, "PUBLIC", true, defaultBranch, branches, null);
        } catch (Exception publicErr) {
            log.debug("Public probe failed for {}: {}", cleanUrl, publicErr.getMessage());
        }

        // 2. If token is available, probe with authentication
        if (token != null && !token.isBlank()) {
            try {
                List<String> branches = gitClient.listBranches(cleanUrl, token.trim());
                String defaultBranch = pickDefaultBranch(branches);
                return new ResolvedRepository(
                        name(), owner, repoName, fullName, cleanUrl, "PRIVATE", true, defaultBranch, branches, null);
            } catch (Exception authErr) {
                log.warn("Authenticated probe failed for {}: {}", cleanUrl, authErr.getMessage());
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
                        "Доступ к приватному репозиторию отклонен Git-провайдером");
            }
        }

        // 3. No token and not publicly accessible -> requires authorization
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
                "Репозиторий приватный или требует авторизации");
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
                    "https://api.github.com/user/repos?sort=updated&direction=desc&per_page=" + size + "&page=" + p;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token.trim())
                    .header("User-Agent", "SemanticBusinessMap")
                    .GET()
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("GitHub list user repos returned status {}: {}", res.statusCode(), res.body());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(res.body());
            var list = new ArrayList<RemoteRepositorySummary>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    list.add(new RemoteRepositorySummary(
                            String.valueOf(item.path("id").asLong()),
                            item.path("name").asText(""),
                            item.path("full_name").asText(""),
                            item.path("html_url").asText(""),
                            item.path("default_branch").asText("main"),
                            item.path("private").asBoolean(false),
                            item.path("description").asText(""),
                            item.path("language").asText("")));
                }
            }
            return list;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted listing GitHub repositories", e);
        }
    }

    private String pickDefaultBranch(List<String> branches) {
        if (branches == null || branches.isEmpty()) return "main";
        if (branches.contains("main")) return "main";
        if (branches.contains("master")) return "master";
        return branches.getFirst();
    }
}
