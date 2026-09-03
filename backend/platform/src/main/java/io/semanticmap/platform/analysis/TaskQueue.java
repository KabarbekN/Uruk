package io.semanticmap.platform.analysis;

import io.semanticmap.platform.shared.Db;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TaskQueue {
    private final Db db;
    private final AnalysisEvents events;
    private final TransactionTemplate tx;
    private final int leaseSeconds;

    public TaskQueue(
            Db db,
            AnalysisEvents events,
            PlatformTransactionManager manager,
            @Value("${semantic.worker.lease-seconds:120}") int leaseSeconds) {
        this.db = db;
        this.events = events;
        this.tx = new TransactionTemplate(manager);
        if (leaseSeconds < 10 || leaseSeconds > 3600) throw new IllegalArgumentException("INVALID_TASK_LEASE");
        this.leaseSeconds = leaseSeconds;
    }

    public UUID enqueue(
            UUID org,
            UUID project,
            UUID run,
            String type,
            String key,
            Map<String, Object> payload,
            List<UUID> dependencies,
            boolean acceptFailed) {
        UUID id = UUID.randomUUID();
        var task = db.one(
                "INSERT INTO analysis_task(id,organization_id,project_id,analysis_run_id,task_type,idempotency_key,payload,accept_failed_dependencies) VALUES (?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (analysis_run_id,idempotency_key) DO UPDATE SET idempotency_key=excluded.idempotency_key RETURNING id",
                id,
                org,
                project,
                run,
                type,
                key,
                db.json(payload),
                acceptFailed);
        id = (UUID) task.get("id");
        for (UUID dependency : dependencies) {
            db.update(
                    "INSERT INTO analysis_task_dependency(organization_id,project_id,analysis_run_id,task_id,depends_on_task_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                    org,
                    project,
                    run,
                    id,
                    dependency);
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    public Optional<TaskLease> claim(String owner) {
        UUID token = UUID.randomUUID();
        var rows = db.rows(
                """
                WITH next_task AS (
                  SELECT t.id FROM analysis_task t
                  JOIN analysis_run r ON r.organization_id=t.organization_id AND r.project_id=t.project_id AND r.id=t.analysis_run_id
                  WHERE t.status='PENDING' AND t.available_at<=clock_timestamp() AND t.attempt<t.max_attempts
                    AND r.status IN ('QUEUED','RUNNING') AND r.cancellation_requested_at IS NULL
                    AND NOT EXISTS (
                      SELECT 1 FROM analysis_task_dependency d JOIN analysis_task p
                        ON p.organization_id=d.organization_id AND p.project_id=d.project_id AND p.analysis_run_id=d.analysis_run_id AND p.id=d.depends_on_task_id
                      WHERE d.organization_id=t.organization_id AND d.project_id=t.project_id AND d.analysis_run_id=t.analysis_run_id AND d.task_id=t.id
                        AND (p.status IN ('PENDING','RUNNING') OR (NOT t.accept_failed_dependencies AND p.status<>'SUCCEEDED')))
                  ORDER BY t.priority,t.available_at,t.created_at,t.id FOR UPDATE OF t SKIP LOCKED LIMIT 1
                )
                UPDATE analysis_task t SET status='RUNNING',locked_by=?,lease_token=?,locked_until=clock_timestamp()+(? * interval '1 second'),
                  heartbeat_at=clock_timestamp(),started_at=coalesce(started_at,clock_timestamp()),attempt=attempt+1
                FROM next_task n WHERE t.id=n.id RETURNING t.*
                """,
                owner,
                token,
                leaseSeconds);
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.getFirst();
        return Optional.of(new TaskLease(
                (UUID) row.get("id"),
                (UUID) row.get("organizationId"),
                (UUID) row.get("projectId"),
                (UUID) row.get("analysisRunId"),
                (String) row.get("taskType"),
                (Map<String, Object>) row.get("payload"),
                owner,
                token,
                ((Number) row.get("attempt")).intValue()));
    }

    public boolean heartbeat(TaskLease lease) {
        return db.update(
                        """
                UPDATE analysis_task t SET heartbeat_at=clock_timestamp(),locked_until=clock_timestamp()+(? * interval '1 second')
                WHERE t.organization_id=? AND t.project_id=? AND t.analysis_run_id=? AND t.id=? AND t.locked_by=? AND t.lease_token=?
                  AND t.status='RUNNING' AND t.locked_until>clock_timestamp()
                  AND EXISTS (SELECT 1 FROM analysis_run r WHERE r.organization_id=t.organization_id AND r.project_id=t.project_id AND r.id=t.analysis_run_id AND r.status IN ('QUEUED','RUNNING') AND r.cancellation_requested_at IS NULL)
                """,
                        leaseSeconds,
                        lease.organizationId(),
                        lease.projectId(),
                        lease.runId(),
                        lease.id(),
                        lease.owner(),
                        lease.token())
                == 1;
    }

    public boolean mutate(TaskLease lease, Runnable mutation) {
        return fenced(lease, mutation, false);
    }

    public boolean complete(TaskLease lease, Runnable mutation) {
        return fenced(lease, mutation, true);
    }

    private boolean fenced(TaskLease lease, Runnable mutation, boolean complete) {
        return Boolean.TRUE.equals(tx.execute(transaction -> {
            if (!lockRun(lease)) return false;
            var owned = db.rows(
                    "SELECT id FROM analysis_task WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=? AND locked_by=? AND lease_token=? AND status='RUNNING' AND locked_until>clock_timestamp() FOR UPDATE",
                    lease.organizationId(),
                    lease.projectId(),
                    lease.runId(),
                    lease.id(),
                    lease.owner(),
                    lease.token());
            if (owned.isEmpty()) return false;
            mutation.run();
            if (complete) {
                db.update(
                        "UPDATE analysis_task SET status='SUCCEEDED',progress_percent=100,finished_at=clock_timestamp(),locked_until=NULL,locked_by=NULL,lease_token=NULL WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=? AND lease_token=?",
                        lease.organizationId(),
                        lease.projectId(),
                        lease.runId(),
                        lease.id(),
                        lease.token());
            }
            return true;
        }));
    }

    private boolean lockRun(TaskLease lease) {
        return !db.rows(
                        "SELECT id FROM analysis_run WHERE organization_id=? AND project_id=? AND id=? AND status IN ('QUEUED','RUNNING') AND cancellation_requested_at IS NULL FOR UPDATE",
                        lease.organizationId(),
                        lease.projectId(),
                        lease.runId())
                .isEmpty();
    }

    public boolean started(TaskLease lease) {
        return mutate(lease, () -> {
            db.update(
                    "UPDATE analysis_run SET status='RUNNING',started_at=coalesce(started_at,clock_timestamp()),current_stage=?,progress=greatest(progress,?) WHERE organization_id=? AND project_id=? AND id=?",
                    lease.type(),
                    progress(lease.type()),
                    lease.organizationId(),
                    lease.projectId(),
                    lease.runId());
            events.append(
                    lease.organizationId(),
                    lease.projectId(),
                    lease.runId(),
                    eventType(lease.type()),
                    lease.type().replace('_', ' '),
                    progress(lease.type()));
        });
    }

    public void fail(TaskLease lease, String code) {
        tx.executeWithoutResult(transaction -> {
            if (!lockRun(lease)) return;
            var owned = db.rows(
                    "SELECT max_attempts FROM analysis_task WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=? AND locked_by=? AND lease_token=? AND status='RUNNING' AND locked_until>clock_timestamp() FOR UPDATE",
                    lease.organizationId(),
                    lease.projectId(),
                    lease.runId(),
                    lease.id(),
                    lease.owner(),
                    lease.token());
            if (owned.isEmpty()) return;
            boolean retry = lease.attempt() < ((Number) owned.getFirst().get("maxAttempts")).intValue();
            db.update(
                    "UPDATE analysis_task SET status=?,error_code=?,error_message=?,available_at=clock_timestamp()+(? * interval '1 second'),finished_at=CASE WHEN ? THEN NULL ELSE clock_timestamp() END,locked_until=NULL,locked_by=NULL,lease_token=NULL WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=? AND lease_token=?",
                    retry ? "PENDING" : "FAILED_PERMANENTLY",
                    code,
                    code,
                    Math.min(300, 2L << Math.min(lease.attempt(), 7)),
                    retry,
                    lease.organizationId(),
                    lease.projectId(),
                    lease.runId(),
                    lease.id(),
                    lease.token());
            events.append(
                    lease.organizationId(),
                    lease.projectId(),
                    lease.runId(),
                    retry ? "task.retry" : "task.failed",
                    code,
                    progress(lease.type()));
            if (!retry && isAnalyzerTask(lease.type())) executionFailed(lease, code);
            if (!retry && !isAnalyzerTask(lease.type()))
                failRun(lease.organizationId(), lease.projectId(), lease.runId(), code);
        });
    }

    public void recover() {
        // This unscoped scan is the privileged worker queue, never a caller-facing lookup.
        var expired = db.rows(
                "SELECT organization_id,project_id,analysis_run_id,id FROM analysis_task WHERE status='RUNNING' AND locked_until<clock_timestamp() ORDER BY locked_until LIMIT 100");
        for (var item : expired)
            tx.executeWithoutResult(transaction -> {
                UUID org = (UUID) item.get("organizationId"),
                        project = (UUID) item.get("projectId"),
                        run = (UUID) item.get("analysisRunId"),
                        id = (UUID) item.get("id");
                var runs = db.rows(
                        "SELECT status FROM analysis_run WHERE organization_id=? AND project_id=? AND id=? FOR UPDATE",
                        org,
                        project,
                        run);
                if (runs.isEmpty()) return;
                var recovered = db.rows(
                        "UPDATE analysis_task SET status=CASE WHEN attempt<max_attempts THEN 'PENDING' ELSE 'FAILED_PERMANENTLY' END,available_at=clock_timestamp()+(LEAST(300,power(2,attempt)::integer)*interval '1 second'),locked_by=NULL,lease_token=NULL,locked_until=NULL,error_code='LEASE_EXPIRED',error_message='Worker lease expired',finished_at=CASE WHEN attempt<max_attempts THEN NULL ELSE clock_timestamp() END WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=? AND status='RUNNING' AND locked_until<clock_timestamp() RETURNING *",
                        org,
                        project,
                        run,
                        id);
                if (recovered.isEmpty()) return;
                var row = recovered.getFirst();
                events.append(
                        org, project, run, "task.recovered", "Worker lease expired; task recovered", progress((String)
                                row.get("taskType")));
                if (row.get("status").equals("FAILED_PERMANENTLY")) {
                    if (isAnalyzerTask((String) row.get("taskType"))) {
                        @SuppressWarnings("unchecked")
                        var payload = (Map<String, Object>) row.get("payload");
                        executionFailed(
                                new TaskLease(
                                        id,
                                        org,
                                        project,
                                        run,
                                        (String) row.get("taskType"),
                                        payload,
                                        "",
                                        UUID.randomUUID(),
                                        0),
                                "LEASE_EXPIRED");
                    } else failRun(org, project, run, "LEASE_EXPIRED");
                }
            });
    }

    private void executionFailed(TaskLease lease, String code) {
        db.update(
                "UPDATE analyzer_execution SET status='FAILED',finished_at=clock_timestamp(),diagnostics=diagnostics || ?::jsonb WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id=?",
                db.json(List.of(Map.of(
                        "code", code, "severity", "ERROR", "message", "Analyzer task failed after bounded retries"))),
                lease.organizationId(),
                lease.projectId(),
                lease.runId(),
                lease.payloadId("executionId"));
    }

    private void failRun(UUID org, UUID project, UUID run, String code) {
        db.update(
                "UPDATE analysis_run SET status='FAILED',current_stage='FAILED',failure_code=?,failure_message=?,finished_at=clock_timestamp() WHERE organization_id=? AND project_id=? AND id=? AND status IN ('QUEUED','RUNNING')",
                code,
                code,
                org,
                project,
                run);
        db.update(
                "UPDATE analysis_task SET status='CANCELLED',finished_at=clock_timestamp(),locked_until=NULL,lease_token=NULL,locked_by=NULL WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND status IN ('PENDING','RUNNING')",
                org,
                project,
                run);
        db.update(
                "UPDATE analyzer_execution SET status='CANCELLED',finished_at=clock_timestamp() WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND status IN ('PLANNED','RUNNING')",
                org,
                project,
                run);
        events.append(org, project, run, "analysis.failed", code, 100);
    }

    private static boolean isAnalyzerTask(String type) {
        return type.equals("RUN_ANALYZER") || type.equals("INGEST_ANALYZER");
    }

    private static String eventType(String type) {
        return switch (type) {
            case "CHECKOUT_REPOSITORY" -> "repository.checkout.started";
            case "RUN_ANALYZER" -> "analyzer.started";
            case "INGEST_ANALYZER" -> "facts.ingestion.started";
            default -> "analysis.progress";
        };
    }

    public static int progress(String type) {
        return switch (type) {
            case "CHECKOUT_REPOSITORY" -> 5;
            case "DETECT_COMPONENTS" -> 15;
            case "RESOLVE_ANALYZERS" -> 25;
            case "RUN_ANALYZER" -> 40;
            case "INGEST_ANALYZER" -> 65;
            case "BUILD_SEMANTIC_GRAPH" -> 80;
            case "BUILD_SEMANTIC_DIFF" -> 90;
            case "COMPLETE_ANALYSIS" -> 100;
            default -> 0;
        };
    }
}
