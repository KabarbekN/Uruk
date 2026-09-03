package io.semanticmap.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AnalysisWorkerTest {
    @Test
    void keepsLeaseAliveWhileWaitingForFencedCommitLock() throws Exception {
        var queue = mock(TaskQueue.class);
        var handler = mock(TaskHandler.class);
        var lease = new TaskLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "RUN_ANALYZER",
                Map.of(),
                "test-owner",
                UUID.randomUUID(),
                1);
        var waiting = new CountDownLatch(1);
        var heartbeat = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var persisted = new CountDownLatch(1);
        when(queue.claim(anyString())).thenReturn(Optional.of(lease), Optional.empty());
        when(queue.started(lease)).thenReturn(true);
        when(queue.heartbeat(lease)).thenAnswer(call -> {
            if (waiting.getCount() == 0) heartbeat.countDown();
            return true;
        });
        when(handler.type()).thenReturn("RUN_ANALYZER");
        when(handler.execute(eq(lease), any())).thenReturn(new TaskHandler.Outcome(persisted::countDown));
        when(queue.complete(eq(lease), any())).thenAnswer(call -> {
            waiting.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_LOCK_TIMEOUT");
            call.getArgument(1, Runnable.class).run();
            return true;
        });
        var worker = new AnalysisWorker(queue, List.of(handler), new SimpleMeterRegistry(), 1, 1, 120);
        try {
            worker.start();
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(heartbeat.await(5, TimeUnit.SECONDS))
                    .as("Heartbeat must continue before lock ownership")
                    .isTrue();
            release.countDown();
            assertThat(persisted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            worker.stop();
        }
    }
}
