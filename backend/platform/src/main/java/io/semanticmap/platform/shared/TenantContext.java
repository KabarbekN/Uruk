package io.semanticmap.platform.shared;

import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TenantContext {
    public static final UUID DEV_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    public record Principal(UUID organizationId, UUID userId) {}

    public UUID orgId() {
        Object principal = authentication().getPrincipal();
        if (principal instanceof Principal p) return p.organizationId();
        if (principal instanceof Jwt jwt) return claimUuid(jwt, "organization_id");
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant identity required");
    }

    public UUID userId() {
        Object principal = authentication().getPrincipal();
        if (principal instanceof Principal p) return p.userId();
        if (principal instanceof Jwt jwt) return claimUuid(jwt, "user_id");
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User identity required");
    }

    public void requireRole(String... roles) {
        var authorities = authentication().getAuthorities();
        boolean allowed = authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ORG_ADMIN")
                        || Arrays.stream(roles).anyMatch(r -> a.getAuthority().equals("ROLE_" + r)));
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient role");
    }

    public boolean hasRole(String role) {
        return authentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    private Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return auth;
    }

    private UUID claimUuid(Jwt jwt, String claim) {
        try {
            return UUID.fromString(jwt.getClaimAsString(claim));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing or invalid tenant identity");
        }
    }
}
