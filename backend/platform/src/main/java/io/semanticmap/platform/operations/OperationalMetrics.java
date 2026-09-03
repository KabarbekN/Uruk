package io.semanticmap.platform.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.semanticmap.platform.shared.Db;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class OperationalMetrics {
    private static final Map<String, String> METRICS = Map.of(
            "pending",
            "semantic.queue.pending",
            "running",
            "semantic.queue.running",
            "analyzerSeconds",
            "semantic.analyzer.last_hour.mean_seconds",
            "analyzerFailed",
            "semantic.analyzer.last_hour.failed",
            "inputTokens",
            "semantic.llm.last_hour.reported_input_tokens",
            "outputTokens",
            "semantic.llm.last_hour.reported_output_tokens",
            "cost",
            "semantic.llm.last_hour.reported_estimated_usd",
            "latency",
            "semantic.llm.last_hour.mean_latency_ms");
    private final Db db;
    private volatile Map<String, Double> values = Map.of();
    private volatile long refreshedAt;

    OperationalMetrics(Db db, MeterRegistry metrics) {
        this.db = db;
        METRICS.forEach((key, name) -> Gauge.builder(name, this, state -> state.values.getOrDefault(key, Double.NaN))
                .register(metrics));
        Gauge.builder(
                        "semantic.metrics.snapshot_age_seconds",
                        this,
                        state -> state.refreshedAt == 0
                                ? Double.NaN
                                : (System.currentTimeMillis() - state.refreshedAt) / 1000.0)
                .register(metrics);
    }

    @Scheduled(
            fixedDelayString = "${semantic.metrics.refresh-ms:30000}",
            initialDelayString = "${semantic.metrics.initial-delay-ms:5000}")
    void refresh() {
        try {
            var data = new HashMap<>(
                    db.one(
                            "SELECT count(*) FILTER(WHERE status='PENDING') AS pending,count(*) FILTER(WHERE status='RUNNING') AS running FROM analysis_task WHERE status IN ('PENDING','RUNNING')"));
            data.putAll(
                    db.one(
                            "SELECT avg(extract(epoch FROM finished_at-started_at)) AS analyzer_seconds,count(*) FILTER(WHERE status='FAILED_PERMANENTLY') AS analyzer_failed FROM analysis_task WHERE task_type='RUN_ANALYZER' AND finished_at>now()-interval '1 hour'"));
            data.putAll(
                    db.one(
                            "SELECT sum(input_tokens) AS input_tokens,sum(output_tokens) AS output_tokens,sum(estimated_cost_usd) AS cost,avg(latency_ms) AS latency FROM llm_execution WHERE created_at>now()-interval '1 hour'"));
            var next = new HashMap<String, Double>();
            METRICS.keySet()
                    .forEach(key -> next.put(key, data.get(key) instanceof Number n ? n.doubleValue() : Double.NaN));
            values = Map.copyOf(next);
            refreshedAt = System.currentTimeMillis();
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(getClass())
                    .warn("Metrics snapshot unavailable: {}", e.getClass().getSimpleName());
        }
    }
}
