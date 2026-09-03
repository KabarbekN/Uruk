package io.semanticmap.platform.graph;

import io.semanticmap.platform.graph.internal.Semantics;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/analysis-runs/{id}/layout")
public class CanvasLayoutController {
    public record Position(double x, double y) {}

    public record Viewport(double x, double y, double zoom) {}

    public record Layout(@NotNull @Size(max = 2000) Map<String, Position> positions, @NotNull Viewport viewport) {}

    private final Db db;
    private final TenantContext tenant;
    private final Access access;

    public CanvasLayoutController(Db db, TenantContext tenant, Access access) {
        this.db = db;
        this.tenant = tenant;
        this.access = access;
    }

    @GetMapping
    public Map<String, Object> get(
            @PathVariable UUID id, @RequestParam(defaultValue = "BUSINESS") GraphQueries.View view) {
        var run = access.run(id);
        access.project(Semantics.uuid(run.get("projectId")));
        UUID org = tenant.orgId(), user = tenant.userId();
        Object project = run.get("projectId");
        Object baseline = run.get("baselineRunId");
        var currentState = db.rows(
                "SELECT viewport FROM canvas_view_state WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND user_id=? AND view_type=?",
                org,
                project,
                id,
                user,
                view.name());
        Object layoutRun = !currentState.isEmpty() || baseline == null ? id : baseline;
        var positions = db.rows(
                "SELECT l.semantic_node_stable_key,l.x,l.y FROM canvas_layout l JOIN semantic_node n ON n.organization_id=l.organization_id AND n.project_id=l.project_id AND n.stable_key=l.semantic_node_stable_key AND n.analysis_run_id=? WHERE l.organization_id=? AND l.project_id=? AND l.analysis_run_id=? AND l.user_id=? AND l.view_type=? ORDER BY l.semantic_node_stable_key LIMIT 2000",
                id,
                org,
                project,
                layoutRun,
                user,
                view.name());
        var state = currentState.isEmpty()
                ? db.rows(
                        "SELECT viewport FROM canvas_view_state WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND user_id=? AND view_type=?",
                        org,
                        project,
                        layoutRun,
                        user,
                        view.name())
                : currentState;
        var result = new LinkedHashMap<String, Object>();
        for (var row : positions)
            result.put(row.get("semanticNodeStableKey").toString(), Map.of("x", row.get("x"), "y", row.get("y")));
        return Map.of(
                "positions",
                result,
                "viewport",
                state.isEmpty()
                        ? Map.of("x", 0, "y", 0, "zoom", 1)
                        : state.getFirst().get("viewport"));
    }

    @PutMapping
    @Transactional
    public Map<String, Object> put(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "BUSINESS") GraphQueries.View view,
            @Valid @RequestBody Layout layout) {
        var run = access.run(id);
        access.project(Semantics.uuid(run.get("projectId")));
        UUID org = tenant.orgId(), user = tenant.userId();
        Object project = run.get("projectId");
        validate(layout.viewport().x(), layout.viewport().y());
        if (!Double.isFinite(layout.viewport().zoom())
                || layout.viewport().zoom() < .01
                || layout.viewport().zoom() > 10) throw bad("Invalid viewport zoom");
        for (var item : layout.positions().entrySet()) {
            if (item.getValue() == null || item.getKey().length() > 2048) throw bad("Invalid position");
            validate(item.getValue().x(), item.getValue().y());
        }
        long found = ((Number) db.one(
                                "SELECT count(*) AS total FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND stable_key IN (SELECT jsonb_array_elements_text(?::jsonb))",
                                org,
                                project,
                                id,
                                db.json(layout.positions().keySet()))
                        .get("total"))
                .longValue();
        if (found != layout.positions().size()) throw bad("Layout contains a node outside this analysis");
        db.one(
                "SELECT id FROM analysis_run WHERE organization_id=? AND project_id=? AND id=? FOR UPDATE",
                org,
                project,
                id);
        db.update(
                "DELETE FROM canvas_layout WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND user_id=? AND view_type=?",
                org,
                project,
                id,
                user,
                view.name());
        for (var item : layout.positions().entrySet())
            db.update(
                    "INSERT INTO canvas_layout(organization_id,project_id,analysis_run_id,user_id,view_type,semantic_node_stable_key,x,y) VALUES (?,?,?,?,?,?,?,?)",
                    org,
                    project,
                    id,
                    user,
                    view.name(),
                    item.getKey(),
                    item.getValue().x(),
                    item.getValue().y());
        db.update(
                "INSERT INTO canvas_view_state(organization_id,project_id,analysis_run_id,user_id,view_type,viewport) VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT (organization_id,project_id,analysis_run_id,user_id,view_type) DO UPDATE SET viewport=excluded.viewport,updated_at=now()",
                org,
                project,
                id,
                user,
                view.name(),
                db.json(layout.viewport()));
        return get(id, view);
    }

    private static void validate(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 1e7 || Math.abs(y) > 1e7)
            throw bad("Invalid canvas coordinates");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
