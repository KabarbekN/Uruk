package io.semanticmap.platform.analysis;

import io.semanticmap.platform.graph.GraphPipeline;
import io.semanticmap.platform.shared.Db;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

final class GraphTaskHandlers {
    private GraphTaskHandlers() {}

    @Component
    static class Build implements TaskHandler {
        private final Db db;
        private final GraphPipeline graph;
        private final AnalysisEvents events;

        Build(Db db, GraphPipeline graph, AnalysisEvents events) {
            this.db = db;
            this.graph = graph;
            this.events = events;
        }

        @Override
        public String type() {
            return "BUILD_SEMANTIC_GRAPH";
        }

        @Override
        public Outcome execute(TaskLease task, BooleanSupplier cancelled) {
            var run = db.one(
                    "SELECT revision_id FROM analysis_run WHERE organization_id=? AND project_id=? AND id=?",
                    task.organizationId(),
                    task.projectId(),
                    task.runId());
            return new Outcome(() -> {
                graph.build(task.organizationId(), task.projectId(), (UUID) run.get("revisionId"), task.runId());
                db.update(
                        "UPDATE analysis_run SET progress=85 WHERE organization_id=? AND project_id=? AND id=?",
                        task.organizationId(),
                        task.projectId(),
                        task.runId());
                events.append(
                        task.organizationId(),
                        task.projectId(),
                        task.runId(),
                        "semantic.graph.completed",
                        "Semantic graph, rules and scenarios built",
                        85);
            });
        }
    }

    @Component
    static class Diff implements TaskHandler {
        private final Db db;
        private final GraphPipeline graph;
        private final AnalysisEvents events;

        Diff(Db db, GraphPipeline graph, AnalysisEvents events) {
            this.db = db;
            this.graph = graph;
            this.events = events;
        }

        @Override
        public String type() {
            return "BUILD_SEMANTIC_DIFF";
        }

        @Override
        public Outcome execute(TaskLease task, BooleanSupplier cancelled) {
            var run = db.one(
                    "SELECT baseline_run_id FROM analysis_run WHERE organization_id=? AND project_id=? AND id=?",
                    task.organizationId(),
                    task.projectId(),
                    task.runId());
            return new Outcome(() -> {
                graph.diff(task.organizationId(), task.projectId(), (UUID) run.get("baselineRunId"), task.runId());
                events.append(
                        task.organizationId(),
                        task.projectId(),
                        task.runId(),
                        "semantic.diff.completed",
                        "Baseline semantic diff built",
                        95);
            });
        }
    }

    @Component
    static class Complete implements TaskHandler {
        private final Db db;
        private final AnalysisEvents events;

        Complete(Db db, AnalysisEvents events) {
            this.db = db;
            this.events = events;
        }

        @Override
        public String type() {
            return "COMPLETE_ANALYSIS";
        }

        @Override
        public Outcome execute(TaskLease task, BooleanSupplier cancelled) {
            return new Outcome(() -> {
                var counts = db.one(
                        """
                        SELECT
                          (SELECT count(*) FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=?) AS nodes,
                          (SELECT count(*) FROM fact_quarantine WHERE organization_id=? AND project_id=? AND analysis_run_id=?) AS quarantined,
                          (SELECT count(*) FROM analyzer_execution WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND status<>'SUCCEEDED') AS gaps,
                          (SELECT count(*) FROM technology_component WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND diagnostics<>'[]'::jsonb) AS detection_gaps
                        """,
                        task.organizationId(),
                        task.projectId(),
                        task.runId(),
                        task.organizationId(),
                        task.projectId(),
                        task.runId(),
                        task.organizationId(),
                        task.projectId(),
                        task.runId(),
                        task.organizationId(),
                        task.projectId(),
                        task.runId());
                boolean empty = ((Number) counts.get("nodes")).longValue() == 0;
                boolean partial = ((Number) counts.get("quarantined")).longValue() > 0
                        || ((Number) counts.get("gaps")).longValue() > 0
                        || ((Number) counts.get("detectionGaps")).longValue() > 0;
                String status = empty ? "FAILED" : partial ? "PARTIALLY_SUCCEEDED" : "SUCCEEDED";
                db.update(
                        "UPDATE analysis_run SET status=?,progress=100,current_stage=?,finished_at=clock_timestamp(),failure_code=?,failure_message=? WHERE organization_id=? AND project_id=? AND id=?",
                        status,
                        status,
                        empty ? "NO_USABLE_SEMANTIC_RESULTS" : null,
                        empty
                                ? "No usable semantic results were produced; inspect diagnostics and analyzer coverage"
                                : null,
                        task.organizationId(),
                        task.projectId(),
                        task.runId());
                if (!empty)
                    events.append(
                            task.organizationId(),
                            task.projectId(),
                            task.runId(),
                            "canvas.ready",
                            "Semantic canvas is available",
                            100);
                events.append(
                        task.organizationId(),
                        task.projectId(),
                        task.runId(),
                        empty ? "analysis.failed" : "analysis.completed",
                        status,
                        100);
            });
        }
    }
}
