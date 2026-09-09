package io.semanticmap.platform.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class RuntimeServiceTest {
    private final Db db = mock(Db.class);
    private final UUID org = UUID.randomUUID(),
            project = UUID.randomUUID(),
            run = UUID.randomUUID(),
            node = UUID.randomUUID();
    private final TenantContext tenant = mock(TenantContext.class);
    private final Access access = mock(Access.class);
    private final Audit audit = mock(Audit.class);
    private final RuntimeService service = new RuntimeService(db, tenant, access, audit);

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "FAILED", "CANCELLED"})
    void rejectsImportUntilLockedAnalysisHasCompleted(String status) {
        when(tenant.orgId()).thenReturn(org);
        when(access.run(run)).thenReturn(Map.of("projectId", project, "status", "SUCCEEDED"));
        when(db.one(contains("FOR UPDATE"), eq(run), eq(org), eq(project)))
                .thenReturn(Map.of("id", run, "status", status));
        assertThatThrownBy(() -> service.ingest(run, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("completed analysis");
        verify(db).one(contains("FOR UPDATE"), eq(run), eq(org), eq(project));
        verifyNoMoreInteractions(db);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "PARTIALLY_SUCCEEDED"})
    void completedAnalysisAcceptsImport(String status) {
        when(tenant.orgId()).thenReturn(org);
        when(access.run(run)).thenReturn(Map.of("projectId", project));
        when(db.one(contains("FOR UPDATE"), eq(run), eq(org), eq(project)))
                .thenReturn(Map.of("id", run, "status", status));
        when(db.one(contains("count(*) AS total FROM runtime_span"), eq(org), eq(project), eq(run)))
                .thenReturn(Map.of("total", 0));
        assertThat(service.ingest(run, List.of()))
                .containsEntry("status", "INGESTED")
                .containsEntry("acceptedSpans", 0);
    }

    @Test
    void missingSemanticAttributesNeverMatchesFromNameOrDatabaseAttributes() {
        assertThat(service.match(org, project, run, Map.of("name", "createOrder", "db.system.name", "postgresql"))
                        .status())
                .isEqualTo("UNRESOLVED");
        verifyNoInteractions(db);
    }

    @Test
    void exactStableKeyQueriesAreTenantProjectAndRunScoped() {
        when(db.rows(anyString(), eq(org), eq(project), eq(run), eq("java:Order#create")))
                .thenReturn(List.of(Map.of("id", node)));
        assertThat(service.match(org, project, run, Map.of("semantic.stable_key", "java:Order#create")))
                .isEqualTo(new RuntimeService.Match(node, "MATCHED_STABLE_KEY"));
        verify(db)
                .rows(
                        contains("organization_id=? AND project_id=? AND analysis_run_id=? AND stable_key=?"),
                        eq(org),
                        eq(project),
                        eq(run),
                        eq("java:Order#create"));
    }

    @Test
    void unresolvedExplicitKeyDoesNotFallBackToAnotherSymbol() {
        when(db.rows(anyString(), eq(org), eq(project), eq(run), eq("missing"))).thenReturn(List.of());
        assertThat(service.match(
                        org,
                        project,
                        run,
                        Map.of(
                                "semantic.stable_key",
                                "missing",
                                "semantic.qualified_symbol",
                                "io.example.Order#create()")))
                .isEqualTo(new RuntimeService.Match(null, "UNRESOLVED"));
        verify(db, times(1)).rows(anyString(), eq(org), eq(project), eq(run), eq("missing"));
    }

    @Test
    void ambiguousQualifiedSymbolsAreUnresolvedAndNeverChooseFirst() {
        String symbol = "io.example.Order#create()";
        when(db.rows(anyString(), eq(org), eq(project), eq(run), eq(symbol), eq(symbol), eq(symbol)))
                .thenReturn(List.of(Map.of("id", node), Map.of("id", UUID.randomUUID())));
        assertThat(service.match(org, project, run, Map.of("semantic.qualified_symbol", symbol)))
                .isEqualTo(new RuntimeService.Match(null, "AMBIGUOUS"));
    }
}
