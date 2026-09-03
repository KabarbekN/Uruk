package io.semanticmap.platform.analysis;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "semantic.worker.enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private final TaskQueue queue;
    private final MeterRegistry metrics;
    private final Map<String, TaskHandler> handlers;
    private final String owner = UUID.randomUUID().toString();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            3, Thread.ofPlatform().daemon().name("analysis-worker-", 0).factory());
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, AtomicBoolean> active = new ConcurrentHashMap<>();
    private final Semaphore slots;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final int heartbeatSeconds;

    public AnalysisWorker(
            TaskQueue queue,
            List<TaskHandler> handlers,
            MeterRegistry metrics,
            @Value("${semantic.worker.concurrency:2}") int concurrency,
            @Value("${semantic.worker.heartbeat-seconds:5}") int heartbeatSeconds,
            @Value("${semantic.worker.lease-seconds:120}") int leaseSeconds) {
        this.queue = queue;
        this.metrics = metrics;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(TaskHandler::type, h -> h));
        if (concurrency < 1 || concurrency > 16 || heartbeatSeconds < 1 || heartbeatSeconds * 3 >= leaseSeconds)
            throw new IllegalArgumentException("INVALID_WORKER_CONFIGURATION");
        this.slots = new Semaphore(concurrency);
        this.heartbeatSeconds = heartbeatSeconds;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        queue.recover();
                    } catch (RuntimeException e) {
                        log.warn("Task recovery failed: {}", e.getClass().getSimpleName());
                    }
                },
                2,
                10,
                TimeUnit.SECONDS);
    }

    private void poll() {
        while (!stopping.get() && slots.tryAcquire()) {
            try {
                var lease = queue.claim(owner);
                if (lease.isEmpty()) {
                    slots.release();
                    return;
                }
                workers.submit(() -> execute(lease.get()));
            } catch (RuntimeException e) {
                slots.release();
                log.warn("Task claim failed: {}", e.getClass().getSimpleName());
                return;
            }
        }
    }

    private void execute(TaskLease lease) {
        var lost = new AtomicBoolean();
        var committing = new AtomicBoolean();
        active.put(lease.id(), lost);
        var heartbeat = scheduler.scheduleWithFixedDelay(
                () -> {
                    // Fenced commits hold the task row. They are authoritative while holding that lock.
                    if (committing.get() || lost.get()) return;
                    try {
                        if (!queue.heartbeat(lease) && !committing.get()) lost.set(true);
                    } catch (RuntimeException e) {
                        if (!committing.get()) lost.set(true);
                        log.warn("Task heartbeat unavailable task={}", lease.id());
                    }
                },
                heartbeatSeconds,
                heartbeatSeconds,
                TimeUnit.SECONDS);
        TaskHandler.Outcome outcome = null;
        boolean completed = false;
        var previousContext = MDC.getCopyOfContextMap();
        MDC.put("organizationId", lease.organizationId().toString());
        MDC.put("projectId", lease.projectId().toString());
        MDC.put("analysisRunId", lease.runId().toString());
        MDC.put("taskId", lease.id().toString());
        if (lease.payload().get("executionId") != null)
            MDC.put("analyzerExecutionId", lease.payload().get("executionId").toString());
        var duration = Timer.start(metrics);
        String result = "abandoned";
        try {
            if (!queue.started(lease)) return;
            TaskHandler handler = handlers.get(lease.type());
            if (handler == null) throw new IllegalStateException("UNREGISTERED_TASK_TYPE");
            outcome = handler.execute(lease, () -> lost.get() || stopping.get());
            if (lost.get() || stopping.get()) return;
            Runnable persist = outcome.persist();
            completed = queue.complete(lease, () -> {
                // Waiting for the run lock does not yet protect this task's lease.
                committing.set(true);
                persist.run();
            });
            if (completed) result = "committed";
        } catch (Exception e) {
            result = "failed";
            String message = e.getMessage();
            String code =
                    message != null && message.matches("[A-Z][A-Z0-9_]{1,99}") ? message : "TASK_EXECUTION_FAILED";
            log.warn(
                    "Task failed id={} type={} code={} cause={}",
                    lease.id(),
                    lease.type(),
                    code,
                    e.getClass().getSimpleName());
            try {
                queue.fail(lease, code);
            } catch (RuntimeException failure) {
                log.warn("Task failure recording unavailable id={}", lease.id());
            }
        } finally {
            heartbeat.cancel(false);
            active.remove(lease.id());
            slots.release();
            if (!completed && outcome != null) {
                try {
                    outcome.abandoned().run();
                } catch (Exception e) {
                    log.warn("Abandoned task artifact cleanup failed id={}", lease.id());
                }
            }
            duration.stop(Timer.builder("semantic.task.duration")
                    .tag("type", lease.type())
                    .tag("outcome", result)
                    .register(metrics));
            MDC.clear();
            if (previousContext != null) MDC.setContextMap(previousContext);
        }
    }

    @PreDestroy
    public void stop() {
        stopping.set(true);
        active.values().forEach(flag -> flag.set(true));
        scheduler.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
