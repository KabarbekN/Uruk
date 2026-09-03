package io.semanticmap.platform.settings;

import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SettingsService {
    public record Update(
            @NotBlank String aiMode,
            @NotNull Boolean remoteAllowed,
            @NotNull @Min(1) @Max(365) Integer retentionDays) {}

    private final Db db;
    private final TenantContext tenant;
    private final Audit audit;
    private final AiConfiguration config;
    private final Environment environment;

    public SettingsService(Db db, TenantContext tenant, Audit audit, AiConfiguration config, Environment environment) {
        this.db = db;
        this.tenant = tenant;
        this.audit = audit;
        this.config = config;
        this.environment = environment;
    }

    public Map<String, Object> get() {
        var row = db.one("SELECT ai_mode,remote_allowed,retention_days FROM organization WHERE id=?", tenant.orgId());
        row.put("authMode", environment.acceptsProfiles(Profiles.of("dev")) ? "DEVELOPMENT" : "OIDC");
        row.put(
                "capabilities",
                Map.of(
                        "localAiConfigured",
                        config.available("LOCAL_PROVIDER"),
                        "remoteAiConfigured",
                        config.available("REMOTE_PROVIDER"),
                        "runtimeTraceIngestion",
                        true,
                        "runtimeEvidence",
                        true,
                        "deterministicAnalysis",
                        true));
        return row;
    }

    @Transactional
    public Map<String, Object> update(Update request) {
        tenant.requireRole("ORG_ADMIN");
        String mode = normalizeMode(request.aiMode());
        if (request.retentionDays() == null
                || request.retentionDays() < 1
                || request.retentionDays() > 365
                || request.remoteAllowed() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid organization settings");
        config.validate(mode, request.remoteAllowed());
        db.one("SELECT id FROM organization WHERE id=? FOR UPDATE", tenant.orgId());
        db.update(
                "UPDATE organization SET ai_mode=?,remote_allowed=?,retention_days=? WHERE id=?",
                mode,
                request.remoteAllowed(),
                request.retentionDays(),
                tenant.orgId());
        audit.record(
                tenant.orgId(),
                null,
                tenant.userId(),
                "ORGANIZATION_POLICY_CHANGED",
                Map.of(
                        "aiMode",
                        mode,
                        "remoteAllowed",
                        request.remoteAllowed(),
                        "retentionDays",
                        request.retentionDays()));
        return get();
    }

    public static String normalizeMode(String input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI mode required");
        return switch (input.toUpperCase(Locale.ROOT)) {
            case "DISABLED" -> "DISABLED";
            case "LOCAL", "LOCAL_PROVIDER" -> "LOCAL_PROVIDER";
            case "REMOTE", "REMOTE_PROVIDER" -> "REMOTE_PROVIDER";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown AI mode");
        };
    }
}
