package io.semanticmap.platform.analysis;

import io.semanticmap.platform.detection.AnalyzerResolver;
import io.semanticmap.platform.shared.Db;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
class ResolutionTaskHandler implements TaskHandler {
    private final Db db;
    private final AnalyzerResolver resolver;
    private final TaskQueue queue;
    private final AnalysisEvents events;

    ResolutionTaskHandler(Db db, AnalyzerResolver resolver, TaskQueue queue, AnalysisEvents events) {
        this.db = db;
        this.resolver = resolver;
        this.queue = queue;
        this.events = events;
    }

    @Override
    public String type() {
        return "RESOLVE_ANALYZERS";
    }

    @Override
    public Outcome execute(TaskLease task, BooleanSupplier cancelled) {
        var run = db.one(
                "SELECT revision_id,baseline_run_id FROM analysis_run WHERE organization_id=? AND project_id=? AND id=?",
                task.organizationId(),
                task.projectId(),
                task.runId());
        var components = db.rows(
                "SELECT * FROM technology_component WHERE organization_id=? AND project_id=? AND analysis_run_id=? ORDER BY root_path",
                task.organizationId(),
                task.projectId(),
                task.runId());
        record Resolved(Map<String, Object> component, List<AnalyzerResolver.Plan> plans) {}
        var resolved = components.stream()
                .map(c -> new Resolved(
                        c,
                        resolver.resolve(
                                AnalyzerResolver.strings(c.get("languages")),
                                AnalyzerResolver.strings(c.get("frameworks")),
                                AnalyzerResolver.strings(c.get("databaseTechnologies")))))
                .toList();
        return new Outcome(() -> {
            List<UUID> ingestions = new ArrayList<>();
            for (var item : resolved) {
                var component = item.component();
                for (var plan : item.plans()) {
                    UUID execution = (UUID) db.one(
                                    "INSERT INTO analyzer_execution(id,organization_id,project_id,revision_id,analysis_run_id,component_id,analyzer_key,version,image_reference,reason,requested_capabilities) VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (analysis_run_id,component_id,analyzer_key) DO UPDATE SET reason=excluded.reason RETURNING id",
                                    UUID.randomUUID(),
                                    task.organizationId(),
                                    task.projectId(),
                                    run.get("revisionId"),
                                    task.runId(),
                                    component.get("id"),
                                    plan.analyzerKey(),
                                    plan.version(),
                                    plan.imageReference(),
                                    plan.reason(),
                                    db.json(plan.capabilities()))
                            .get("id");
                    UUID analyzer = queue.enqueue(
                            task.organizationId(),
                            task.projectId(),
                            task.runId(),
                            "RUN_ANALYZER",
                            "run:" + execution,
                            Map.of("executionId", execution),
                            List.of(task.id()),
                            false);
                    ingestions.add(queue.enqueue(
                            task.organizationId(),
                            task.projectId(),
                            task.runId(),
                            "INGEST_ANALYZER",
                            "ingest:" + execution,
                            Map.of("executionId", execution),
                            List.of(analyzer),
                            true));
                }
                var uncovered = AnalyzerResolver.strings(component.get("languages")).stream()
                        .filter(l -> !Set.of("JAVA", "SQL", "JAVASCRIPT", "TYPESCRIPT")
                                .contains(l))
                        .toList();
                if (item.plans().isEmpty() || !uncovered.isEmpty()) {
                    db.update(
                            "UPDATE technology_component SET diagnostics=diagnostics || ?::jsonb WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=?",
                            db.json(List.of(Map.of(
                                    "code",
                                    "ANALYZER_COVERAGE_GAP",
                                    "severity",
                                    "WARNING",
                                    "filePath",
                                    component.get("rootPath"),
                                    "message",
                                    "No enabled compatible analyzer for "
                                            + (uncovered.isEmpty() ? component.get("languages") : uncovered)))),
                            task.organizationId(),
                            task.projectId(),
                            task.runId(),
                            component.get("id"));
                }
            }
            if (ingestions.isEmpty()) ingestions.add(task.id());
            UUID graph = queue.enqueue(
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    "BUILD_SEMANTIC_GRAPH",
                    "graph",
                    Map.of(),
                    ingestions,
                    true);
            UUID end = graph;
            if (run.get("baselineRunId") != null)
                end = queue.enqueue(
                        task.organizationId(),
                        task.projectId(),
                        task.runId(),
                        "BUILD_SEMANTIC_DIFF",
                        "diff",
                        Map.of(),
                        List.of(graph),
                        false);
            queue.enqueue(
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    "COMPLETE_ANALYSIS",
                    "complete",
                    Map.of(),
                    List.of(end),
                    false);
            db.update(
                    "UPDATE analysis_run SET progress=30 WHERE organization_id=? AND project_id=? AND id=?",
                    task.organizationId(),
                    task.projectId(),
                    task.runId());
            events.append(
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    "analyzer.resolved",
                    "Analyzer plan persisted before execution",
                    30);
        });
    }
}
