package io.semanticmap.platform.shared;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class TenantContextTest {
    private final TenantContext tenant = new TenantContext();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthenticationCannotSelectTenant() {
        assertThatThrownBy(tenant::orgId).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void viewerCannotReviewOrChangeSettings() {
        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new TenantContext.Principal(org, user),
                        "",
                        List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
        assertThat(tenant.orgId()).isEqualTo(org);
        assertThatThrownBy(() -> tenant.requireRole("REVIEWER")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tenant.requireRole("ORG_ADMIN")).isInstanceOf(ResponseStatusException.class);
    }
}
