package io.semanticmap.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.semanticmap.platform.settings.AiConfiguration;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class EnrichmentServiceTest {
    @Test
    void disabledReturnsExplicitStatusWithoutLoadingEvidenceOrCallingProvider() {
        UUID org = UUID.randomUUID(), project = UUID.randomUUID(), node = UUID.randomUUID();
        Db db = mock(Db.class);
        TenantContext tenant = mock(TenantContext.class);
        Access access = mock(Access.class);
        EvidenceBundle.Loader loader = mock(EvidenceBundle.Loader.class);
        EnrichmentGateway gateway = mock(EnrichmentGateway.class);
        when(tenant.orgId()).thenReturn(org);
        when(tenant.userId()).thenReturn(UUID.randomUUID());
        var nodeRow = Map.<String, Object>of("id", node, "projectId", project);
        when(loader.node(node)).thenReturn(nodeRow);
        when(db.one("SELECT ai_mode,remote_allowed FROM organization WHERE id=?", org))
                .thenReturn(Map.of("aiMode", "DISABLED", "remoteAllowed", false));
        var service = new EnrichmentService(
                db,
                tenant,
                mock(Audit.class),
                access,
                new AiConfiguration("", "", "", "localhost", 100),
                loader,
                gateway,
                mock(EnrichmentValidator.class),
                mock(PlatformTransactionManager.class));
        assertThat(service.enrich(node)).containsEntry("status", "DISABLED").containsEntry("trustStatus", "UNVERIFIED");
        verifyNoInteractions(gateway);
        verify(loader, never()).load(any());
        verify(access).requireProjectRole(project, "ANALYST", "DEVELOPER");
    }
}
