package io.semanticmap.platform.analysis;

import io.micrometer.core.instrument.MeterRegistry;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.TenantContext;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AnalysisEventController {
    private final Access access;
    private final TenantContext tenant;
    private final AnalysisEvents events;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            2, Thread.ofPlatform().daemon().name("analysis-sse-", 0).factory());
    private final Semaphore connections = new Semaphore(128);

    public AnalysisEventController(Access access, TenantContext tenant, AnalysisEvents events, MeterRegistry metrics) {
        this.access = access;
        this.tenant = tenant;
        this.events = events;
        executor.setRemoveOnCancelPolicy(true);
        metrics.gauge("semantic.sse.connections", connections, permits -> 128 - permits.availablePermits());
    }

    @GetMapping(value = "/api/v1/analysis-runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastId,
            jakarta.servlet.http.HttpServletResponse response) {
        var run = access.run(id);
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        long sequence = 0;
        if (lastId != null) {
            try {
                sequence = Long.parseLong(lastId);
                if (sequence < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("INVALID_LAST_EVENT_ID");
            }
        }
        if (!connections.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Event stream capacity reached");
        UUID org = tenant.orgId(), project = (UUID) run.get("projectId");
        var emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));
        var cursor = new AtomicLong(sequence);
        var closed = new AtomicBoolean();
        var future = new AtomicReference<java.util.concurrent.ScheduledFuture<?>>();
        Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                connections.release();
                var task = future.get();
                if (task != null) task.cancel(false);
            }
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        Runnable poll = () -> {
            if (closed.get()) return;
            try {
                var state = access.run(id); // Recheck membership and archival with the captured caller identity.
                var batch = events.after(org, project, id, cursor.get());
                for (var event : batch) {
                    long next = ((Number) event.get("sequenceNumber")).longValue();
                    emitter.send(SseEmitter.event().id(Long.toString(next)).data(event, MediaType.APPLICATION_JSON));
                    cursor.set(next);
                }
                if (batch.size() < 500 && !List.of("QUEUED", "RUNNING").contains(state.get("status"))) {
                    emitter.complete();
                    close.run();
                } else if (batch.isEmpty()) emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | RuntimeException e) {
                emitter.completeWithError(e);
                close.run();
            }
        };
        future.set(executor.scheduleWithFixedDelay(
                new DelegatingSecurityContextRunnable(poll, SecurityContextHolder.getContext()),
                0,
                1,
                TimeUnit.SECONDS));
        if (closed.get()) future.get().cancel(false);
        return emitter;
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
