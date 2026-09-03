package io.semanticmap.platform.analysis;

import io.semanticmap.platform.graph.GraphPipeline;
import io.semanticmap.platform.shared.Db;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
class IngestionTaskHandler implements TaskHandler {
    private final Db db;
    private final GraphPipeline graph;
    private final AnalysisEvents events;

    IngestionTaskHandler(Db db, GraphPipeline graph, AnalysisEvents events) {
        this.db = db;
        this.graph = graph;
        this.events = events;
    }

    @Override
    public String type() {
        return "INGEST_ANALYZER";
    }

    @Override
    public Outcome execute(TaskLease task, BooleanSupplier cancelled) {
        var run = db.one(
                "SELECT revision_id,workspace_path FROM analysis_run WHERE organization_id=? AND project_id=? AND id=?",
                task.organizationId(),
                task.projectId(),
                task.runId());
        var execution = db.one(
                "SELECT status,output_path FROM analyzer_execution WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=?",
                task.organizationId(),
                task.projectId(),
                task.runId(),
                task.payloadId("executionId"));
        return new Outcome(() -> {
            if (List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED").contains(execution.get("status"))) {
                graph.ingest(
                        task.organizationId(),
                        task.projectId(),
                        (UUID) run.get("revisionId"),
                        task.runId(),
                        task.payloadId("executionId"),
                        Path.of((String) run.get("workspacePath")),
                        Path.of((String) execution.get("outputPath")));
                events.append(
                        task.organizationId(),
                        task.projectId(),
                        task.runId(),
                        "facts.ingestion.completed",
                        "Analyzer facts validated and ingested",
                        70);
            } else
                events.append(
                        task.organizationId(),
                        task.projectId(),
                        task.runId(),
                        "analyzer.coverage.gap",
                        "Failed analyzer has no usable output",
                        70);
        });
    }
}
