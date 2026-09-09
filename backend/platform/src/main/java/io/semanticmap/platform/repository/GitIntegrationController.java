package io.semanticmap.platform.repository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/git")
public class GitIntegrationController {

    private final RepositoryResolutionService resolutionService;

    public GitIntegrationController(RepositoryResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    public record ResolveRequest(@NotBlank(message = "URL cannot be blank") String url, String token) {}

    @PostMapping("/repositories/resolve")
    public ResponseEntity<GitProvider.ResolvedRepository> resolveRepository(
            @Valid @RequestBody ResolveRequest request) {
        GitProvider.ResolvedRepository resolved = resolutionService.resolve(request.url(), request.token());
        return ResponseEntity.ok(resolved);
    }

    @GetMapping("/repositories")
    public ResponseEntity<List<GitProvider.RemoteRepositorySummary>> listRepositories(
            @RequestParam(defaultValue = "GITHUB") String provider,
            @RequestParam(required = false) String token,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int perPage)
            throws IOException {
        List<GitProvider.RemoteRepositorySummary> repos =
                resolutionService.listRepositories(provider, token, page, perPage);
        return ResponseEntity.ok(repos);
    }

    @GetMapping("/connections")
    public ResponseEntity<Map<String, Object>> getConnections() {
        // Can be expanded to report current connected states for GitHub/GitLab
        return ResponseEntity.ok(Map.of(
                "githubSupported", true,
                "gitlabSupported", true,
                "genericSupported", true));
    }
}
