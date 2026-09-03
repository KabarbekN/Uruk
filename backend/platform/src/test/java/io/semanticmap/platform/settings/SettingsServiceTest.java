package io.semanticmap.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class SettingsServiceTest {
    @Test
    void disabledWorksWithoutProviderConfiguration() {
        var config = new AiConfiguration("", "", "", "localhost", 100);
        config.validate("DISABLED", false);
        assertThat(config.available("LOCAL_PROVIDER")).isFalse();
        assertThat(config.available("REMOTE_PROVIDER")).isFalse();
        assertThat(config.estimatedCostUsd(100, 20)).isNull();
    }

    @Test
    void projectAdministratorCannotEnableRemoteAiForTheOrganization() {
        Db db = mock(Db.class);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID()),
                        "",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_PROJECT_ADMIN"))));
        try {
            var service = new SettingsService(
                    db,
                    new TenantContext(),
                    mock(Audit.class),
                    new AiConfiguration("https://provider.example", "model", "key", "localhost", 100),
                    new MockEnvironment());
            assertThatThrownBy(() -> service.update(new SettingsService.Update("REMOTE_PROVIDER", true, 7)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("403");
            verifyNoInteractions(db);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void remoteRequiresExplicitOptInHttpsAndApiKey() {
        var config = new AiConfiguration("https://provider.example", "model", "key", "localhost", 100);
        assertThatThrownBy(() -> config.validate("REMOTE_PROVIDER", false)).isInstanceOf(ResponseStatusException.class);
        config.validate("REMOTE_PROVIDER", true);
        assertThatThrownBy(() -> config.validate("LOCAL_PROVIDER", true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new AiConfiguration("http://provider.example", "model", "key", "localhost", 100)
                        .validate("REMOTE_PROVIDER", true))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new AiConfiguration(
                                "https://user:pass@provider.example", "model", "key", "localhost", 100)
                        .validate("REMOTE_PROVIDER", true))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void onlyAdministratorCanChangePolicyAndSavingDoesNotNeedAnAiClient() {
        Db db = mock(Db.class);
        TenantContext tenant = mock(TenantContext.class);
        Audit audit = mock(Audit.class);
        UUID org = UUID.randomUUID();
        when(tenant.orgId()).thenReturn(org);
        when(tenant.userId()).thenReturn(UUID.randomUUID());
        when(db.one(anyString(), any()))
                .thenAnswer(i -> new LinkedHashMap<>(
                        Map.of("aiMode", "LOCAL_PROVIDER", "remoteAllowed", false, "retentionDays", 7)));
        var config = new AiConfiguration("http://localhost:11434", "local-model", "", "localhost", 100);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        var service = new SettingsService(db, tenant, audit, config, environment);
        var result = service.update(new SettingsService.Update("local", false, 7));
        verify(tenant).requireRole("ORG_ADMIN");
        assertThat(result).containsEntry("authMode", "DEVELOPMENT").doesNotContainKeys("apiKey", "baseUrl");
        verify(db)
                .update(
                        "UPDATE organization SET ai_mode=?,remote_allowed=?,retention_days=? WHERE id=?",
                        "LOCAL_PROVIDER",
                        false,
                        7,
                        org);
    }
}
