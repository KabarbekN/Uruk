package io.semanticmap.platform.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/system")
public class AiModelManagementController {
    private final HardwareProfilingService profilingService;
    private final SettingsService settingsService;
    private final AiConfiguration aiConfiguration;
    private final TenantContext tenant;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public AiModelManagementController(
            HardwareProfilingService profilingService,
            SettingsService settingsService,
            AiConfiguration aiConfiguration,
            TenantContext tenant,
            ObjectMapper mapper) {
        this.profilingService = profilingService;
        this.settingsService = settingsService;
        this.aiConfiguration = aiConfiguration;
        this.tenant = tenant;
        this.mapper = mapper;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @GetMapping("/resources")
    public HardwareProfilingService.HardwareProfile getResources() {
        return profilingService.getProfile();
    }

    public record PullRequest(@NotBlank String model) {}

    @PostMapping(value = "/ai/pull", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter pullModel(@Valid @RequestBody PullRequest request) {
        tenant.requireRole("ORG_ADMIN", "PROJECT_ADMIN");
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes timeout for model download

        CompletableFuture.runAsync(() -> {
            try {
                String ollamaUrl = profilingService.getOllamaBaseUrl();
                String payload = mapper.writeValueAsString(Map.of("name", request.model(), "stream", true));

                HttpRequest post = HttpRequest.newBuilder()
                        .uri(URI.create(ollamaUrl + "/api/pull"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<java.io.InputStream> response =
                        httpClient.send(post, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    emitter.send(SseEmitter.event()
                            .name("pull.error")
                            .data(Map.of("error", "Ollama returned status " + response.statusCode())));
                    emitter.complete();
                    return;
                }

                try (var reader = new BufferedReader(new InputStreamReader(response.body()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        JsonNode node = mapper.readTree(line);
                        String status = node.path("status").asText();
                        long total = node.path("total").asLong(0);
                        long completed = node.path("completed").asLong(0);

                        int percent = total > 0 ? (int) ((completed * 100) / total) : 0;
                        emitter.send(SseEmitter.event()
                                .name("pull.progress")
                                .data(Map.of(
                                        "status", status,
                                        "total", total,
                                        "completed", completed,
                                        "percent", percent)));
                    }
                }

                // Automatically activate after download
                aiConfiguration.configure(request.model(), ollamaUrl, "ollama");
                settingsService.update(new SettingsService.Update("LOCAL_PROVIDER", false, 7));

                emitter.send(SseEmitter.event()
                        .name("pull.completed")
                        .data(Map.of(
                                "model",
                                request.model(),
                                "active",
                                true,
                                "message",
                                "Model " + request.model() + " successfully downloaded and activated!")));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("pull.error")
                            .data(Map.of("error", e.getMessage() != null ? e.getMessage() : "Download error")));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    public record SelectRequest(
            @NotBlank String model,
            @NotBlank String providerType, // "LOCAL" or "REMOTE"
            String apiKey,
            String baseUrl) {}

    @PostMapping("/ai/select")
    public Map<String, Object> selectModel(@Valid @RequestBody SelectRequest request) {
        tenant.requireRole("ORG_ADMIN", "PROJECT_ADMIN", "ANALYST", "DEVELOPER");

        if ("LOCAL".equalsIgnoreCase(request.providerType()) || "OLLAMA".equalsIgnoreCase(request.providerType())) {
            String ollamaUrl = profilingService.getOllamaBaseUrl();
            String localUrl = (request.baseUrl() != null && !request.baseUrl().isBlank())
                    ? request.baseUrl().strip().replaceAll("/+$", "")
                    : ollamaUrl;
            aiConfiguration.configure(request.model(), localUrl, "ollama");
            settingsService.update(new SettingsService.Update("LOCAL_PROVIDER", false, 7));
        } else {
            String remoteUrl = (request.baseUrl() != null && !request.baseUrl().isBlank())
                    ? request.baseUrl().strip().replaceAll("/+$", "")
                    : defaultRemoteBaseUrl(request.model());
            String apiKey = request.apiKey() != null ? request.apiKey().trim() : "";
            aiConfiguration.configure(request.model(), remoteUrl, apiKey);
            settingsService.update(new SettingsService.Update("REMOTE_PROVIDER", true, 7));
        }

        return Map.of(
                "model", request.model(),
                "providerType", request.providerType().toUpperCase(),
                "selected", true);
    }

    private static String defaultRemoteBaseUrl(String model) {
        if (model.contains("claude")) {
            return "https://api.anthropic.com/v1";
        } else if (model.contains("gemini")) {
            return "https://generativelanguage.googleapis.com/v1beta/openai";
        } else if (model.contains("deepseek")) {
            return "https://api.deepseek.com";
        }
        return "https://api.openai.com/v1";
    }
}
