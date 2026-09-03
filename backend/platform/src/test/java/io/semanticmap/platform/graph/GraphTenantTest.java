package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.semanticmap.platform.graph.internal.SemanticDiffer;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GraphTenantTest {
    private final UUID org = UUID.randomUUID(), project = UUID.randomUUID(), id = UUID.randomUUID();
    private final Db db = mock(Db.class);
    private final TenantContext tenant = mock(TenantContext.class);
    private final Access access = mock(Access.class);
    private final GraphQueries queries = new GraphQueries(db, tenant, access, mock(SemanticDiffer.class));

    @Test
    void checksProjectMembershipAfterTenantScopedNodeLookup() {
        when(tenant.orgId()).thenReturn(org);
        when(db.one(anyString(), eq(id), eq(org))).thenReturn(Map.of("id", id, "projectId", project));
        when(access.project(project)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> queries.nodeEvidence(id)).isInstanceOf(ResponseStatusException.class);
        verify(access).project(project);
        verify(db).one(contains("n.organization_id=?"), eq(id), eq(org));
    }

    @Test
    void neverFallsBackToUnscopedLookupForOtherTenant() {
        when(tenant.orgId()).thenReturn(org);
        when(db.one(anyString(), eq(id), eq(org))).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> queries.scopedEdge(id)).isInstanceOf(ResponseStatusException.class);
        verify(db).one(contains("organization_id=?"), eq(id), eq(org));
        verifyNoInteractions(access);
    }

    @Test
    void rejectsDiffRunFromAnotherProject() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        when(access.run(from)).thenReturn(Map.of("projectId", UUID.randomUUID()));
        assertThatThrownBy(() -> queries.diff(project, from, to)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(db);
    }
}
