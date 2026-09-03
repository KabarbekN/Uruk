package io.semanticmap.platform.analysis;

import io.semanticmap.platform.runner.AnalyzerExecutionPort;
import io.semanticmap.platform.shared.Db;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "semantic.worker.enabled", havingValue = "true", matchIfMissing = true)
class ContainerRecovery {
    private static final Logger log = LoggerFactory.getLogger(ContainerRecovery.class);
    private final Db db;
    private final AnalyzerExecutionPort runner;

    ContainerRecovery(Db db, AnalyzerExecutionPort runner) {
        this.db = db;
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${semantic.worker.container-recovery-ms:30000}", initialDelay = 15000)
    void recover() {
        try {
            for (var execution : runner.running()) {
                var active = db.rows(
                        """
                        SELECT t.id FROM analysis_task t
                        JOIN analysis_run r ON r.organization_id=t.organization_id AND r.project_id=t.project_id AND r.id=t.analysis_run_id
                        JOIN analyzer_execution e ON e.organization_id=t.organization_id AND e.project_id=t.project_id AND e.analysis_run_id=t.analysis_run_id
                        WHERE e.id=? AND t.lease_token=? AND t.task_type='RUN_ANALYZER' AND t.payload->>'executionId'=?
                          AND t.status='RUNNING' AND t.locked_until>clock_timestamp()
                          AND r.status IN ('QUEUED','RUNNING') AND r.cancellation_requested_at IS NULL
                        """,
                        execution.executionId(),
                        execution.attemptId(),
                        execution.executionId().toString());
                if (active.isEmpty()) runner.cancelAttempt(execution);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Container recovery unavailable: {}", e.getClass().getSimpleName());
        }
    }
}
