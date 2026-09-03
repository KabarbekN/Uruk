package io.semanticmap.platform.analysis;

import io.semanticmap.contract.Protocol;
import io.semanticmap.platform.detection.AnalyzerResolver;
import io.semanticmap.platform.runner.AnalyzerExecutionPort;
import io.semanticmap.platform.runner.RunnerPolicy;
import io.semanticmap.platform.shared.Db;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
class AnalyzerTaskHandler implements TaskHandler {
    private final Db db;
    private final AnalyzerExecutionPort runner;
    private final RunnerPolicy policy;
    private final TaskQueue queue;
    private final AnalysisEvents events;

    AnalyzerTaskHandler(
            Db db, AnalyzerExecutionPort runner, RunnerPolicy policy, TaskQueue queue, AnalysisEvents events) {
        this.db = db;
        this.runner = runner;
        this.policy = policy;
        this.queue = queue;
        this.events = events;
    }

    @Override
    public String type() {
        return "RUN_ANALYZER";
    }

    @Override
    public Outcome execute(TaskLease task, BooleanSupplier cancelled) throws Exception {
        UUID executionId = task.payloadId("executionId");
        var run = db.one(
                "SELECT * FROM analysis_run WHERE organization_id=? AND project_id=? AND id=?",
                task.organizationId(),
                task.projectId(),
                task.runId());
        var execution = db.one(
                "SELECT * FROM analyzer_execution WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=?",
                task.organizationId(),
                task.projectId(),
                task.runId(),
                executionId);
        var component = db.one(
                "SELECT * FROM technology_component WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=?",
                task.organizationId(),
                task.projectId(),
                task.runId(),
                execution.get("componentId"));
        var changed = db
                .rows(
                        "SELECT file_path FROM revision_changed_file WHERE organization_id=? AND project_id=? AND analysis_run_id=? ORDER BY file_path",
                        task.organizationId(),
                        task.projectId(),
                        task.runId())
                .stream()
                .map(f -> (String) f.get("filePath"))
                .toList();
        var buildSystems = AnalyzerResolver.strings(component.get("buildSystems"));
        var request = new Protocol.Request(
                Protocol.VERSION,
                task.runId().toString(),
                task.organizationId().toString(),
                task.projectId().toString(),
                new Protocol.Revision((String) run.get("revision"), (String) run.get("requestedRef")),
                new Protocol.Component(
                        (String) component.get("rootPath"),
                        AnalyzerResolver.strings(component.get("languages")),
                        AnalyzerResolver.strings(component.get("frameworks")),
                        buildSystems.isEmpty() ? "NONE" : buildSystems.getFirst()),
                AnalyzerResolver.strings(execution.get("requestedCapabilities")),
                changed,
                new Protocol.Policy(
                        "DENY", false, false, true, false, policy.timeoutSeconds(), policy.maxOutputBytes()));
        if (!queue.mutate(task, () -> {
            db.update(
                    "UPDATE analyzer_execution SET status='RUNNING' WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=?",
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    executionId);
            events.append(
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    "analyzer.pull.started",
                    "Resolving immutable image for " + execution.get("analyzerKey"),
                    40);
        })) throw new IOException("TASK_LEASE_LOST");
        if (task.attempt() > 1) runner.cancel(executionId);
        Path workspace = Path.of((String) run.get("workspacePath"));
        Path directory = workspace
                .getParent()
                .resolve("executions")
                .resolve(executionId.toString())
                .resolve(task.token().toString());
        var result = runner.execute(new AnalyzerExecutionPort.Command(
                executionId,
                task.token(),
                (String) execution.get("analyzerKey"),
                (String) execution.get("version"),
                (String) execution.get("imageReference"),
                workspace,
                directory,
                request,
                cancelled));
        return new Outcome(() -> {
            db.update(
                    "UPDATE analyzer_execution SET status=?,image_digest=?,output_path=?,coverage=?::jsonb,diagnostics=?::jsonb,finished_at=clock_timestamp() WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=?",
                    result.status(),
                    result.imageDigest(),
                    result.output().toString(),
                    db.json(result.coverage()),
                    db.json(result.diagnostics()),
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    executionId);
            db.update(
                    "UPDATE analysis_run SET progress=greatest(progress,60) WHERE organization_id=? AND project_id=? AND id=?",
                    task.organizationId(),
                    task.projectId(),
                    task.runId());
            events.append(
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    "analyzer.completed",
                    execution.get("analyzerKey") + ": " + result.status(),
                    60);
        });
    }
}
