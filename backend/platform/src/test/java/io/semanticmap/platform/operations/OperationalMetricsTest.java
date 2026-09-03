package io.semanticmap.platform.operations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.semanticmap.platform.shared.Db;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationalMetricsTest {
    @Test
    void exposesActualSnapshotsAndPreservesStalenessOnFailure() {
        Db db = mock(Db.class);
        var registry = new SimpleMeterRegistry();
        var metrics = new OperationalMetrics(db, registry);
        assertThat(registry.get("semantic.queue.pending").gauge().value()).isNaN();
        when(db.one(anyString()))
                .thenReturn(
                        Map.of("pending", 3L, "running", 2L),
                        Map.of("analyzerSeconds", 1.5, "analyzerFailed", 1L),
                        Map.of("inputTokens", 40L));
        metrics.refresh();
        assertThat(registry.get("semantic.queue.pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("semantic.analyzer.last_hour.mean_seconds")
                        .gauge()
                        .value())
                .isEqualTo(1.5);
        assertThat(registry.get("semantic.llm.last_hour.reported_output_tokens")
                        .gauge()
                        .value())
                .isNaN();
        when(db.one(anyString())).thenThrow(new IllegalStateException("unavailable"));
        metrics.refresh();
        assertThat(registry.get("semantic.queue.pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("semantic.metrics.snapshot_age_seconds").gauge().value())
                .isGreaterThanOrEqualTo(0);
    }
}
