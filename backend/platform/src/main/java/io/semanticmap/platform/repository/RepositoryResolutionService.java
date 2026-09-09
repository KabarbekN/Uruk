package io.semanticmap.platform.repository;

import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.TenantContext;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RepositoryResolutionService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryResolutionService.class);

    private final List<GitProvider> providers;
    private final CredentialStore credentialStore;
    private final Audit audit;
    private final TenantContext tenant;

    public RepositoryResolutionService(
            List<GitProvider> providers, CredentialStore credentialStore, Audit audit, TenantContext tenant) {
        // Sort providers so GITHUB and GITLAB take precedence over GENERIC_GIT
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(p -> "GENERIC_GIT".equalsIgnoreCase(p.name()) ? 1 : 0))
                .toList();
        this.credentialStore = credentialStore;
        this.audit = audit;
        this.tenant = tenant;
    }

    public GitProvider.ResolvedRepository resolve(String rawUrl, String explicitToken) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL cannot be empty");
        }

        GitProvider provider = providers.stream()
                .filter(p -> p.supports(rawUrl))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported Git repository protocol or URL"));

        String token = explicitToken;
        if (token == null || token.isBlank()) {
            // Check if there is a stored token in CredentialStore by reference key or provider
            // (e.g. if explicitToken was a reference key or if tenant has a saved connection)
        }

        GitProvider.ResolvedRepository resolved = provider.resolve(rawUrl, token);
        log.info(
                "Resolved repository url='{}' provider='{}' visibility='{}' hasAccess={}",
                resolved.normalizedUrl(),
                resolved.provider(),
                resolved.visibility(),
                resolved.hasAccess());

        try {
            if (tenant != null && tenant.orgId() != null) {
                audit.record(
                        tenant.orgId(),
                        null,
                        tenant.userId(),
                        "REPOSITORY_RESOLVED",
                        Map.of(
                                "url", resolved.normalizedUrl(),
                                "provider", resolved.provider(),
                                "visibility", resolved.visibility(),
                                "hasAccess", resolved.hasAccess()));
            }
        } catch (Exception ignored) {
            // Tenant context might not be present for anonymous resolution
        }

        return resolved;
    }

    public List<GitProvider.RemoteRepositorySummary> listRepositories(
            String providerName, String token, int page, int perPage) throws IOException {
        GitProvider provider = providers.stream()
                .filter(p -> p.name().equalsIgnoreCase(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Git provider: " + providerName));

        return provider.listRepositories(token, page, perPage);
    }
}
