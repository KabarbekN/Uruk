package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CanvasLayoutControllerTest {
    private final UUID run = UUID.randomUUID(),
            org = UUID.randomUUID(),
            project = UUID.randomUUID(),
            user = UUID.randomUUID();
    private final TenantContext tenant = mock(TenantContext.class);
    private final Access access = mock(Access.class);

    @Test
    void pinRoundTripAndLegacyReplacementClearPins() {
        var db = new LayoutData();
        var controller = controller(db);
        var viewport = new CanvasLayoutController.Viewport(1, 2, 1.5);
        var positions = Map.of(
                "rule:minimum", new CanvasLayoutController.Position(17, 23),
                "endpoint:create", new CanvasLayoutController.Position(40, 50));
        var result = controller.put(
                run,
                GraphQueries.View.BUSINESS,
                new CanvasLayoutController.Layout(positions, viewport, Set.of("rule:minimum")));
        assertThat(result).containsEntry("pinnedStableKeys", List.of("rule:minimum"));
        assertThat(controller.put(
                        run, GraphQueries.View.BUSINESS, new CanvasLayoutController.Layout(positions, viewport)))
                .containsEntry("pinnedStableKeys", List.of());
    }

    @Test
    void rejectsPinnedNodeWithoutSavedPositionBeforeMutatingLayout() {
        var db = mock(Db.class);
        var controller = controller(db);
        var layout = new CanvasLayoutController.Layout(
                Map.of("rule:minimum", new CanvasLayoutController.Position(17, 23)),
                new CanvasLayoutController.Viewport(0, 0, 1),
                Set.of("outside"));
        assertThatThrownBy(() -> controller.put(run, GraphQueries.View.BUSINESS, layout))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Pinned nodes must have saved positions");
        verifyNoInteractions(db);
    }

    @Test
    void baselinePinsAreReturnedOnlyAlongsideSurvivingPositions() {
        var db = mock(Db.class);
        UUID baseline = UUID.randomUUID();
        when(db.rows(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT l.semantic_node_stable_key")) {
                assertThat(invocation.getArgument(4, Object.class)).isEqualTo(baseline);
                return List.of(Map.of("semanticNodeStableKey", "rule:minimum", "x", 17, "y", 23, "pinned", true));
            }
            return List.of();
        });
        var controller = controller(db);
        when(access.run(run)).thenReturn(Map.of("projectId", project, "baselineRunId", baseline));
        assertThat(controller.get(run, GraphQueries.View.BUSINESS))
                .containsEntry("pinnedStableKeys", List.of("rule:minimum"));
    }

    private CanvasLayoutController controller(Db db) {
        when(tenant.orgId()).thenReturn(org);
        when(tenant.userId()).thenReturn(user);
        when(access.run(run)).thenReturn(Map.of("projectId", project));
        return new CanvasLayoutController(db, tenant, access);
    }

    private static final class LayoutData extends Db {
        private final List<Map<String, Object>> positions = new ArrayList<>();

        LayoutData() {
            super(null, new ObjectMapper());
        }

        @Override
        public Map<String, Object> one(String sql, Object... args) {
            return Map.of("total", 2);
        }

        @Override
        public List<Map<String, Object>> rows(String sql, Object... args) {
            if (sql.startsWith("SELECT l.semantic_node_stable_key")) return positions;
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("DELETE FROM canvas_layout")) positions.clear();
            if (sql.startsWith("INSERT INTO canvas_layout("))
                positions.add(Map.of("semanticNodeStableKey", args[5], "x", args[6], "y", args[7], "pinned", args[8]));
            return 1;
        }
    }
}
