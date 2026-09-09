package io.semanticmap.platform.analysis;

import io.semanticmap.platform.ai.EnrichmentService;
import io.semanticmap.platform.shared.Db;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class EnrichmentTaskHandler implements TaskHandler {
    private final Db db;
    private final EnrichmentService enrichmentService;
    private final AnalysisEvents events;

    public EnrichmentTaskHandler(Db db, EnrichmentService enrichmentService, AnalysisEvents events) {
        this.db = db;
        this.enrichmentService = enrichmentService;
        this.events = events;
    }

    @Override
    public String type() {
        return "ENRICH_BUSINESS_MAP";
    }

    @Override
    public Outcome execute(TaskLease task, BooleanSupplier cancelled) {
        return new Outcome(() -> {
            var nodes = db.rows(
                    """
                    SELECT scenario.id, endpoint.label
                    FROM business_scenario story
                    JOIN semantic_node scenario
                      ON scenario.id = story.node_id
                     AND scenario.organization_id = story.organization_id
                     AND scenario.project_id = story.project_id
                     AND scenario.analysis_run_id = story.analysis_run_id
                    JOIN semantic_node endpoint
                      ON endpoint.id = story.entry_node_id
                     AND endpoint.organization_id = story.organization_id
                     AND endpoint.project_id = story.project_id
                     AND endpoint.analysis_run_id = story.analysis_run_id
                    WHERE story.organization_id = ? AND story.project_id = ? AND story.analysis_run_id = ?
                      AND endpoint.kind = 'ENDPOINT'
                    ORDER BY endpoint.stable_key
                    """,
                    task.organizationId(),
                    task.projectId(),
                    task.runId());

            int total = nodes.size();
            int done = 0;
            int failed = 0;

            events.append(
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    "ai.enrichment.started",
                    "Начато автоматическое ИИ-описание " + total + " endpoint-сценариев",
                    85);

            for (Map<String, Object> node : nodes) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                UUID nodeId = (UUID) node.get("id");
                try {
                    enrichmentService.enrichNode(task.organizationId(), task.projectId(), nodeId, null);
                    done++;
                    int pct = 85 + (done * 10 / Math.max(1, total));
                    events.append(
                            task.organizationId(),
                            task.projectId(),
                            task.runId(),
                            "ai.enrichment.progress",
                            "Готово " + done + " из " + total + " Endpoint Stories",
                            pct);
                } catch (Exception ignored) {
                    // Static findings remain useful when one AI request is unavailable or rejected.
                    failed++;
                }
            }

            db.update(
                    "UPDATE analysis_run SET progress = 95 WHERE organization_id = ? AND project_id = ? AND id = ?",
                    task.organizationId(),
                    task.projectId(),
                    task.runId());

            events.append(
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    "ai.enrichment.completed",
                    "Автоматическое описание Endpoint Stories завершено: " + done + " готово, " + failed
                            + " без описания",
                    95);
        });
    }
}
