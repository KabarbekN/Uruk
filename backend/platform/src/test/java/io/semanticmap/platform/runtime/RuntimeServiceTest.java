package io.semanticmap.platform.runtime;

import static org.assertj.core.api.Assertions.assertThat;
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

class RuntimeServiceTest {
    private final Db db = mock(Db.class);
    private final UUID org = UUID.randomUUID(),
            project = UUID.randomUUID(),
            run = UUID.randomUUID(),
            node = UUID.randomUUID();
    private final RuntimeService service =
            new RuntimeService(db, mock(TenantContext.class), mock(Access.class), mock(Audit.class));

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
