package io.semanticmap.platform.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/oauth")
public class RepositoryOAuthController {

    private static final Logger log = LoggerFactory.getLogger(RepositoryOAuthController.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final Map<String, String> pendingStates = new ConcurrentHashMap<>();

    private volatile String githubClientId;
    private volatile String githubClientSecret;

    private volatile String gitlabClientId;
    private volatile String gitlabClientSecret;

    public RepositoryOAuthController(
            @Value("${semantic.oauth.github.client-id:${GITHUB_OAUTH_CLIENT_ID:}}") String githubClientId,
            @Value("${semantic.oauth.github.client-secret:${GITHUB_OAUTH_CLIENT_SECRET:}}") String githubClientSecret,
            @Value("${semantic.oauth.gitlab.client-id:${GITLAB_OAUTH_CLIENT_ID:}}") String gitlabClientId,
            @Value("${semantic.oauth.gitlab.client-secret:${GITLAB_OAUTH_CLIENT_SECRET:}}") String gitlabClientSecret) {
        this.githubClientId = githubClientId != null ? githubClientId.trim() : "";
        this.githubClientSecret = githubClientSecret != null ? githubClientSecret.trim() : "";
        this.gitlabClientId = gitlabClientId != null ? gitlabClientId.trim() : "";
        this.gitlabClientSecret = gitlabClientSecret != null ? gitlabClientSecret.trim() : "";
    }

    public record OAuthStatus(
            boolean githubConfigured, String githubClientId, boolean gitlabConfigured, String gitlabClientId) {}

    public record OAuthConfigRequest(String provider, String clientId, String clientSecret) {}

    @GetMapping("/status")
    public OAuthStatus status() {
        return new OAuthStatus(
                githubClientId != null
                        && !githubClientId.isBlank()
                        && githubClientSecret != null
                        && !githubClientSecret.isBlank(),
                githubClientId,
                gitlabClientId != null
                        && !gitlabClientId.isBlank()
                        && gitlabClientSecret != null
                        && !gitlabClientSecret.isBlank(),
                gitlabClientId);
    }

    @PostMapping("/config")
    public Map<String, Object> configure(@RequestBody OAuthConfigRequest request) {
        if ("github".equalsIgnoreCase(request.provider())) {
            this.githubClientId =
                    request.clientId() != null ? request.clientId().trim() : "";
            this.githubClientSecret =
                    request.clientSecret() != null ? request.clientSecret().trim() : "";
            return Map.of(
                    "provider", "github", "configured", !githubClientId.isBlank() && !githubClientSecret.isBlank());
        } else if ("gitlab".equalsIgnoreCase(request.provider())) {
            this.gitlabClientId =
                    request.clientId() != null ? request.clientId().trim() : "";
            this.gitlabClientSecret =
                    request.clientSecret() != null ? request.clientSecret().trim() : "";
            return Map.of(
                    "provider", "gitlab", "configured", !gitlabClientId.isBlank() && !gitlabClientSecret.isBlank());
        }
        throw new IllegalArgumentException("Unknown provider: " + request.provider());
    }

    private final Map<String, Long> lastDevicePollTime = new ConcurrentHashMap<>();
    private final Map<String, DevicePollResponse> lastDevicePollResponse = new ConcurrentHashMap<>();
    private final Map<String, Integer> devicePollInterval = new ConcurrentHashMap<>();

    private static final String DEFAULT_GITHUB_CLIENT_ID = "178c6fc778ccc68e1d6a";

    public record DeviceStartResponse(
            String deviceCode, String userCode, String verificationUri, int expiresIn, int interval) {}

    public record DevicePollRequest(String deviceCode) {}

    public record DevicePollResponse(String status, String token, String username, String error, Integer interval) {}

    @PostMapping("/device/github/start")
    public ResponseEntity<?> startGithubDeviceFlow() {
        try {
            String clientId =
                    (githubClientId != null && !githubClientId.isBlank()) ? githubClientId : DEFAULT_GITHUB_CLIENT_ID;
            String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) + "&scope="
                    + URLEncoder.encode("repo,read:user", StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://github.com/login/device/code"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return ResponseEntity.status(response.statusCode()).body(response.body());
            }

            JsonNode node = objectMapper.readTree(response.body());
            String deviceCode = node.path("device_code").asText("");
            String userCode = node.path("user_code").asText("");
            String verificationUri = node.path("verification_uri").asText("https://github.com/login/device");
            int expiresIn = node.path("expires_in").asInt(900);
            int interval = node.path("interval").asInt(5);

            return ResponseEntity.ok(
                    new DeviceStartResponse(deviceCode, userCode, verificationUri, expiresIn, interval));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/device/github/poll")
    public ResponseEntity<DevicePollResponse> pollGithubDeviceFlow(@RequestBody DevicePollRequest pollRequest) {
        String code =
                pollRequest.deviceCode() != null ? pollRequest.deviceCode().trim() : "";
        if (code.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new DevicePollResponse("error", null, null, "deviceCode is blank", null));
        }

        long now = System.currentTimeMillis();
        Long lastTime = lastDevicePollTime.get(code);
        int requiredIntervalSec = devicePollInterval.getOrDefault(code, 5);

        // If client polls before requiredIntervalSec, return cached response to avoid GitHub rate limit penalties
        if (lastTime != null && (now - lastTime) < (requiredIntervalSec * 1000L - 300L)) {
            DevicePollResponse cached = lastDevicePollResponse.get(code);
            if (cached != null) {
                return ResponseEntity.ok(cached);
            }
        }

        lastDevicePollTime.put(code, now);

        try {
            String clientId =
                    (githubClientId != null && !githubClientId.isBlank()) ? githubClientId : DEFAULT_GITHUB_CLIENT_ID;
            String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&device_code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://github.com/login/oauth/access_token"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "SemanticBusinessMap")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info(
                    "GitHub device poll: device_code={}, status={}, body={}",
                    code,
                    response.statusCode(),
                    response.body());
            JsonNode node = objectMapper.readTree(response.body());

            if (node.has("access_token")) {
                String token = node.path("access_token").asText();
                String username = "user";
                try {
                    HttpRequest userReq = HttpRequest.newBuilder()
                            .uri(URI.create("https://api.github.com/user"))
                            .header("Authorization", "Bearer " + token)
                            .header("User-Agent", "SemanticBusinessMap")
                            .GET()
                            .build();
                    HttpResponse<String> userRes = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString());
                    JsonNode userJson = objectMapper.readTree(userRes.body());
                    if (userJson.has("login")) {
                        username = userJson.get("login").asText();
                    }
                } catch (Exception ignored) {
                }

                DevicePollResponse success = new DevicePollResponse("success", token, username, null, null);
                lastDevicePollResponse.put(code, success);
                return ResponseEntity.ok(success);
            }

            String error = node.path("error").asText();
            int newInterval = node.path("interval").asInt(5);
            if (newInterval > requiredIntervalSec) {
                devicePollInterval.put(code, newInterval);
            }

            DevicePollResponse resp;
            if ("authorization_pending".equals(error)) {
                resp = new DevicePollResponse("pending", null, null, null, newInterval);
            } else if ("slow_down".equals(error)) {
                int penaltyInterval = Math.max(newInterval, requiredIntervalSec + 5);
                devicePollInterval.put(code, penaltyInterval);
                resp = new DevicePollResponse(
                        "slow_down", null, null, "GitHub запросил замедление опроса", penaltyInterval);
            } else if ("expired_token".equals(error)) {
                resp = new DevicePollResponse("expired", null, null, "Срок действия кода истек", null);
            } else if ("access_denied".equals(error)) {
                resp = new DevicePollResponse("denied", null, null, "Пользователь отклонил запрос", null);
            } else {
                resp = new DevicePollResponse("error", null, null, error, null);
            }

            lastDevicePollResponse.put(code, resp);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new DevicePollResponse("error", null, null, e.getMessage(), null));
        }
    }

    @GetMapping("/authorize/{provider}")
    public void authorize(
            @PathVariable String provider,
            @RequestParam(required = false) String redirect_uri,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {

        String state = UUID.randomUUID().toString();
        String callbackUrl = redirect_uri;
        if (callbackUrl == null || callbackUrl.isBlank()) {
            String scheme = request.getHeader("X-Forwarded-Proto") != null
                    ? request.getHeader("X-Forwarded-Proto")
                    : request.getScheme();
            String host = request.getHeader("Host");
            callbackUrl = scheme + "://" + host + "/api/v1/auth/oauth/callback/" + provider;
        }

        pendingStates.put(state, callbackUrl);

        if ("github".equalsIgnoreCase(provider)) {
            if (githubClientId == null || githubClientId.isBlank()) {
                response.sendError(HttpStatus.BAD_REQUEST.value(), "GitHub OAuth client_id is not configured");
                return;
            }
            String url = "https://github.com/login/oauth/authorize"
                    + "?client_id=" + URLEncoder.encode(githubClientId, StandardCharsets.UTF_8)
                    + "&scope=repo,read:user"
                    + "&state=" + state
                    + "&redirect_uri=" + URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);
            response.sendRedirect(url);
        } else if ("gitlab".equalsIgnoreCase(provider)) {
            if (gitlabClientId == null || gitlabClientId.isBlank()) {
                response.sendError(HttpStatus.BAD_REQUEST.value(), "GitLab OAuth client_id is not configured");
                return;
            }
            String url = "https://gitlab.com/oauth/authorize"
                    + "?client_id=" + URLEncoder.encode(gitlabClientId, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8)
                    + "&response_type=code"
                    + "&state=" + state
                    + "&scope=read_repository+read_user";
            response.sendRedirect(url);
        } else {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "Unsupported OAuth provider: " + provider);
        }
    }

    @GetMapping(value = "/callback/{provider}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(required = false, name = "error_description") String errorDescription) {

        if (error != null) {
            String msg = errorDescription != null ? errorDescription : error;
            return ResponseEntity.ok(renderHtmlError(provider, msg));
        }

        if (code == null || code.isBlank()) {
            return ResponseEntity.ok(renderHtmlError(provider, "Missing authorization code"));
        }

        String callbackUrl = state != null ? pendingStates.remove(state) : null;
        if (callbackUrl == null) {
            callbackUrl = "";
        }

        try {
            if ("github".equalsIgnoreCase(provider)) {
                return ResponseEntity.ok(exchangeGithubToken(code, callbackUrl));
            } else if ("gitlab".equalsIgnoreCase(provider)) {
                return ResponseEntity.ok(exchangeGitlabToken(code, callbackUrl));
            } else {
                return ResponseEntity.ok(renderHtmlError(provider, "Unsupported provider"));
            }
        } catch (Exception e) {
            return ResponseEntity.ok(renderHtmlError(provider, "Exchange failed: " + e.getMessage()));
        }
    }

    private String exchangeGithubToken(String code, String callbackUrl) throws IOException, InterruptedException {
        String body = "client_id=" + URLEncoder.encode(githubClientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(githubClientSecret, StandardCharsets.UTF_8)
                + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);

        HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create("https://github.com/login/oauth/access_token"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> tokenRes = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(tokenRes.body());

        if (json.has("error")) {
            return renderHtmlError(
                    "GitHub",
                    json.path("error_description").asText(json.path("error").asText()));
        }

        String token = json.path("access_token").asText();
        if (token == null || token.isBlank()) {
            return renderHtmlError("GitHub", "No access_token received");
        }

        // Fetch user login
        String username = "user";
        try {
            HttpRequest userReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user"))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", "SemanticBusinessMap")
                    .GET()
                    .build();
            HttpResponse<String> userRes = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString());
            JsonNode userJson = objectMapper.readTree(userRes.body());
            if (userJson.has("login")) {
                username = userJson.get("login").asText();
            }
        } catch (Exception ignored) {
        }

        return renderHtmlSuccess("github", token, username);
    }

    private String exchangeGitlabToken(String code, String callbackUrl) throws IOException, InterruptedException {
        String body = "client_id=" + URLEncoder.encode(gitlabClientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(gitlabClientSecret, StandardCharsets.UTF_8)
                + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&grant_type=authorization_code"
                + "&redirect_uri=" + URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);

        HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create("https://gitlab.com/oauth/token"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> tokenRes = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(tokenRes.body());

        if (json.has("error")) {
            return renderHtmlError(
                    "GitLab",
                    json.path("error_description").asText(json.path("error").asText()));
        }

        String token = json.path("access_token").asText();
        if (token == null || token.isBlank()) {
            return renderHtmlError("GitLab", "No access_token received");
        }

        // Fetch user login
        String username = "user";
        try {
            HttpRequest userReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://gitlab.com/api/v4/user"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> userRes = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString());
            JsonNode userJson = objectMapper.readTree(userRes.body());
            if (userJson.has("username")) {
                username = userJson.get("username").asText();
            }
        } catch (Exception ignored) {
        }

        return renderHtmlSuccess("gitlab", token, username);
    }

    private String renderHtmlSuccess(String provider, String token, String username) {
        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <title>OAuth Success</title>
          <style>
            body { font-family: system-ui, -apple-system, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #f8f9fa; color: #212529; }
            .card { background: white; padding: 32px; border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.08); text-align: center; max-width: 380px; }
            .check { font-size: 36px; color: #2b8a3e; margin-bottom: 12px; }
            h3 { margin: 0 0 8px 0; font-size: 18px; }
            p { font-size: 14px; color: #495057; margin: 0; }
          </style>
        </head>
        <body>
          <div class="card">
            <div class="check">✓</div>
            <h3>Авторизация успешна!</h3>
            <p>Вы вошли через %s (@%s). Возврат в приложение...</p>
          </div>
          <script>
            const payload = {
              type: 'SBM_OAUTH_SUCCESS',
              provider: '%s',
              token: '%s',
              username: '%s'
            };
            if (window.opener) {
              window.opener.postMessage(payload, '*');
              setTimeout(function() { window.close(); }, 600);
            } else {
              window.location.href = '/projects';
            }
          </script>
        </body>
        </html>
        """
                .formatted(provider, username, provider, token, username);
    }

    private String renderHtmlError(String provider, String message) {
        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <title>OAuth Error</title>
          <style>
            body { font-family: system-ui, -apple-system, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #fff5f5; color: #c92a2a; }
            .card { background: white; padding: 32px; border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.08); text-align: center; max-width: 420px; }
            h3 { margin: 0 0 10px 0; }
            p { font-size: 13px; color: #495057; word-break: break-word; }
          </style>
        </head>
        <body>
          <div class="card">
            <h3>Ошибка авторизации %s</h3>
            <p>%s</p>
          </div>
          <script>
            if (window.opener) {
              window.opener.postMessage({ type: 'SBM_OAUTH_ERROR', provider: '%s', error: '%s' }, '*');
            }
          </script>
        </body>
        </html>
        """
                .formatted(provider, message, provider, message);
    }
}
