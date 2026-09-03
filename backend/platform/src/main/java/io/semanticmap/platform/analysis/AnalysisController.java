package io.semanticmap.platform.analysis;

import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisController {
    private final AnalysisLifecycle lifecycle;
    private final Access access;
    private final TenantContext tenant;
    private final Db db;
    private final io.semanticmap.platform.runner.RunnerPolicy policy;

    public AnalysisController(
            AnalysisLifecycle lifecycle,
            Access access,
            TenantContext tenant,
            Db db,
            io.semanticmap.platform.runner.RunnerPolicy policy) {
        this.lifecycle = lifecycle;
        this.access = access;
        this.tenant = tenant;
        this.db = db;
        this.policy = policy;
    }

    @GetMapping("/api/v1/projects/{project}/analysis-runs")
    public List<AnalysisLifecycle.Run> list(@PathVariable UUID project) {
        return lifecycle.list(project);
    }

    @PostMapping("/api/v1/projects/{project}/analysis-runs")
    public AnalysisLifecycle.Run create(
            @PathVariable UUID project,
            @RequestBody(required = false) AnalysisLifecycle.Start request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key)
            throws IOException {
        return lifecycle.start(project, request, key);
    }

    @GetMapping("/api/v1/analysis-runs/{id}")
    public AnalysisLifecycle.Run get(@PathVariable UUID id) {
        return lifecycle.get(id);
    }

    @PostMapping("/api/v1/analysis-runs/{id}/cancel")
    public AnalysisLifecycle.Run cancel(@PathVariable UUID id) {
        return lifecycle.cancel(id);
    }

    @GetMapping("/api/v1/analysis-runs/{id}/coverage")
    public Map<String, Object> coverage(@PathVariable UUID id) {
        var run = access.run(id);
        var executions = db.rows(
                "SELECT id,analyzer_key,status,coverage FROM analyzer_execution WHERE organization_id=? AND project_id=? AND analysis_run_id=? ORDER BY analyzer_key,id",
                tenant.orgId(),
                run.get("projectId"),
                id);
        return Map.of(
                "analysisRunId",
                id,
                "status",
                run.get("status"),
                "analyzers",
                executions,
                "components",
                components(run, id),
                "complete",
                "SUCCEEDED".equals(run.get("status")));
    }

    @GetMapping("/api/v1/analysis-runs/{id}/diagnostics")
    public List<Map<String, Object>> diagnostics(@PathVariable UUID id) {
        var run = access.run(id);
        var result = new ArrayList<Map<String, Object>>();
        for (var row : db.rows(
                "SELECT id,analyzer_key,diagnostics FROM analyzer_execution WHERE organization_id=? AND project_id=? AND analysis_run_id=? ORDER BY analyzer_key,id",
                tenant.orgId(),
                run.get("projectId"),
                id)) {
            for (var diagnostic : maps(row.get("diagnostics"))) {
                var item = new java.util.LinkedHashMap<>(diagnostic);
                item.put("analyzerExecutionId", row.get("id"));
                item.put("analyzerKey", row.get("analyzerKey"));
                result.add(item);
            }
        }
        for (var row : components(run, id)) result.addAll(maps(row.get("diagnostics")));
        if (run.get("failureCode") != null)
            result.add(Map.of(
                    "code",
                    run.get("failureCode"),
                    "severity",
                    "ERROR",
                    "message",
                    String.valueOf(run.get("failureMessage"))));
        return result;
    }

    @GetMapping("/api/v1/analysis-runs/{id}/analyzer-plan")
    public Map<String, Object> plan(@PathVariable UUID id) {
        var run = access.run(id);
        return Map.of(
                "analysisRunId",
                id,
                "components",
                components(run, id),
                "analyzers",
                db.rows(
                        "SELECT id,component_id,analyzer_key,version,image_reference,image_digest,status,reason,requested_capabilities,coverage FROM analyzer_execution WHERE organization_id=? AND project_id=? AND analysis_run_id=? ORDER BY component_id,analyzer_key",
                        tenant.orgId(),
                        run.get("projectId"),
                        id));
    }

    @GetMapping("/api/v1/analyzer-executions/{id}")
    public Map<String, Object> execution(@PathVariable UUID id) {
        var row = db.one(
                "SELECT id,organization_id,project_id,analysis_run_id,analyzer_key,version,image_digest,status,coverage,diagnostics,created_at,finished_at FROM analyzer_execution WHERE organization_id=? AND id=?",
                tenant.orgId(),
                id);
        access.run((UUID) row.get("analysisRunId"));
        return row;
    }

    @GetMapping("/api/v1/analyzer-executions/{id}/diagnostics")
    public Object executionDiagnostics(@PathVariable UUID id) {
        return execution(id).get("diagnostics");
    }

    @GetMapping("/api/v1/analyzer-executions/{id}/facts")
    public List<Map<String, Object>> facts(@PathVariable UUID id, @RequestParam(defaultValue = "100") int limit) {
        var row = execution(id);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("FACT_PAGE_LIMIT_MUST_BE_1_TO_1000");
        return db.rows(
                "SELECT id,kind,stable_key,origin,confidence,payload FROM raw_fact WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND analyzer_execution_id=? ORDER BY id LIMIT ?",
                tenant.orgId(),
                row.get("projectId"),
                row.get("analysisRunId"),
                id,
                limit);
    }

    @GetMapping("/api/v1/analyzer-executions/{id}/logs")
    public Map<String, Object> logs(@PathVariable UUID id) throws IOException {
        var row = execution(id);
        var run = access.run((UUID) row.get("analysisRunId"));
        if (run.get("artifactsDeletedAt") != null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GONE, "Analyzer logs have expired");
        var output = db.one(
                "SELECT output_path FROM analyzer_execution WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=?",
                tenant.orgId(),
                row.get("projectId"),
                row.get("analysisRunId"),
                id);
        if (output.get("outputPath") == null) return Map.of("available", false, "text", "");
        var path = java.nio.file.Path.of((String) output.get("outputPath"))
                .getParent()
                .resolve("analyzer.log");
        policy.hostPath(path);
        if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return Map.of("available", false, "text", "");
        byte[] bytes;
        try (var input = java.nio.file.Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(262145);
        }
        String text = new String(bytes, 0, Math.min(bytes.length, 262144), java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\r\\n]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:password|secret|api[_-]?key|token)\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]");
        return Map.of("available", true, "text", text, "truncated", bytes.length > 262144);
    }

    private List<Map<String, Object>> components(Map<String, Object> run, UUID id) {
        return db.rows(
                "SELECT id,root_path,languages,frameworks,build_systems,database_technologies,evidence,fingerprint,diagnostics FROM technology_component WHERE organization_id=? AND project_id=? AND analysis_run_id=? ORDER BY root_path",
                tenant.orgId(),
                run.get("projectId"),
                id);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list
                ? list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .toList()
                : List.of();
    }
}
