package io.semanticmap.platform.settings;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AiConfiguration {
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Set<String> localHosts;
    private final int dailyAttempts;

    @Value("${semantic.ai.input-usd-per-million:-1}")
    private BigDecimal inputUsdPerMillion = BigDecimal.valueOf(-1);

    @Value("${semantic.ai.output-usd-per-million:-1}")
    private BigDecimal outputUsdPerMillion = BigDecimal.valueOf(-1);

    public AiConfiguration(
            @Value("${semantic.ai.base-url:}") String baseUrl,
            @Value("${semantic.ai.model:}") String model,
            @Value("${semantic.ai.api-key:}") String apiKey,
            @Value("${semantic.ai.local-hosts:localhost,127.0.0.1,::1,[::1]}") String localHosts,
            @Value("${semantic.ai.daily-attempt-quota:100}") int dailyAttempts) {
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
        this.model = model.strip();
        this.apiKey = apiKey;
        this.localHosts = Arrays.stream(localHosts.split(","))
                .map(String::strip)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.dailyAttempts = Math.max(2, Math.min(10000, dailyAttempts));
    }

    public void validate(String mode, boolean remoteAllowed) {
        if (mode.equals("DISABLED")) return;
        if (model.isBlank() || model.length() > 200 || baseUrl.isBlank())
            fail("AI provider URL and model must be configured");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid configured AI provider URL");
        }
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())))
            fail("Invalid configured AI provider URL");
        if (mode.equals("LOCAL_PROVIDER")) {
            if (!localHosts.contains(uri.getHost().toLowerCase(Locale.ROOT)))
                fail("Provider host is not configured as controlled infrastructure");
        } else if (mode.equals("REMOTE_PROVIDER")) {
            if (!remoteAllowed) fail("Remote AI requires organization administrator opt-in");
            if (!"https".equals(uri.getScheme()) || apiKey.isBlank())
                fail("Remote AI requires HTTPS and a configured API key");
        } else fail("Unknown AI mode");
    }

    public boolean available(String mode) {
        try {
            validate(mode, true);
            return true;
        } catch (ResponseStatusException e) {
            return false;
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public String apiKey() {
        return apiKey.isBlank() ? "local-provider" : apiKey;
    }

    public int dailyAttempts() {
        return dailyAttempts;
    }

    public BigDecimal estimatedCostUsd(Integer inputTokens, Integer outputTokens) {
        if (inputTokens == null
                || outputTokens == null
                || inputTokens < 0
                || outputTokens < 0
                || inputUsdPerMillion.signum() < 0
                || outputUsdPerMillion.signum() < 0) return null;
        return inputUsdPerMillion
                .multiply(BigDecimal.valueOf(inputTokens))
                .add(outputUsdPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                .movePointLeft(6);
    }

    private static void fail(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
