package io.semanticmap.platform.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.repository.RepositoryPolicy;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TaskQueueIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

    static Db db;
    static TransactionTemplate tx;
    static DataSourceTransactionManager manager;
    UUID org, project, run;
    TaskQueue queue;
    AnalysisEvents events;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();
        db = new Db(new JdbcTemplate(source), new ObjectMapper());
        manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
    }

    @BeforeEach
    void setup() {
        db.update("TRUNCATE organization CASCADE");
        org = UUID.randomUUID();
        project = UUID.randomUUID();
        run = UUID.randomUUID();
        db.update("INSERT INTO organization(id,name) VALUES (?,?)", org, "Queue tests");
        db.update(
                "INSERT INTO project(id,organization_id,name,repository_path) VALUES (?,?,?,?)",
                project,
                org,
                "Queue tests",
                "test-repository");
        db.update(
                "INSERT INTO analysis_run(id,organization_id,project_id,repository_url,requested_by,config_hash) VALUES (?,?,?,?,?,?)",
                run,
                org,
                project,
                "test-repository",
                UUID.randomUUID(),
                "test-config");
        events = new AnalysisEvents(db);
        queue = new TaskQueue(db, events, manager, 30);
    }

    UUID task(String type, String key, List<UUID> dependencies, boolean accept) {
        return queue.enqueue(org, project, run, type, key, Map.of(), dependencies, accept);
    }

    @Test
    void concurrentClaimsNeverReturnSameTaskAndDependenciesWait() throws Exception {
        UUID first = task("CHECKOUT_REPOSITORY", "first", List.of(), false);
        UUID second = task("DETECT_COMPONENTS", "second", List.of(), false);
        UUID dependent = task("BUILD_SEMANTIC_GRAPH", "third", List.of(first, second), false);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> {
                start.await();
                return queue.claim("worker-a").orElseThrow();
            });
            var b = executor.submit(() -> {
                start.await();
                return queue.claim("worker-b").orElseThrow();
            });
            start.countDown();
            var leaseA = a.get(10, TimeUnit.SECONDS);
            var leaseB = b.get(10, TimeUnit.SECONDS);
            assertThat(List.of(leaseA.id(), leaseB.id())).containsExactlyInAnyOrder(first, second);
            assertThat(queue.claim("worker-c")).isEmpty();
            assertThat(queue.complete(leaseA, () -> {})).isTrue();
            assertThat(queue.claim("worker-c")).isEmpty();
            assertThat(queue.complete(leaseB, () -> {})).isTrue();
            assertThat(queue.claim("worker-c").orElseThrow().id()).isEqualTo(dependent);
        }
    }

    @Test
    void recoveryFencesOldOwnerEvenWhenWorkerNameIsReused() {
        task("CHECKOUT_REPOSITORY", "recover", List.of(), false);
        var old = queue.claim("worker").orElseThrow();
        db.update("UPDATE analysis_task SET locked_until=clock_timestamp()-interval '1 second' WHERE id=?", old.id());
        assertThat(queue.heartbeat(old)).isFalse();
        queue.recover();
        db.update("UPDATE analysis_task SET available_at=clock_timestamp() WHERE id=?", old.id());
        var replacement = queue.claim("worker").orElseThrow();
        assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(queue.complete(old, () -> {
                    throw new AssertionError("Stale owner mutated state");
                }))
                .isFalse();
        assertThat(queue.heartbeat(old)).isFalse();
        assertThat(queue.complete(replacement, () -> {})).isTrue();
    }

    @Test
    void fencedCommitRemainsAuthoritativeWhileRecoveryWaitsForItsLock() throws Exception {
        task("BUILD_SEMANTIC_GRAPH", "long-commit", List.of(), false);
        var lease = queue.claim("worker").orElseThrow();
        db.update(
                "UPDATE analysis_task SET locked_until=clock_timestamp()+interval '500 milliseconds' WHERE id=?",
                lease.id());
        var entered = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var commit = executor.submit(() -> queue.complete(lease, () -> {
                entered.countDown();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var recovery = executor.submit(() -> {
                Thread.sleep(650);
                queue.recover();
                return true;
            });
            assertThat(commit.get(10, TimeUnit.SECONDS)).isTrue();
            recovery.get(10, TimeUnit.SECONDS);
            assertThat(db.one("SELECT status FROM analysis_task WHERE id=?", lease.id())
                            .get("status"))
                    .isEqualTo("SUCCEEDED");
            assertThat(queue.claim("replacement")).isEmpty();
        }
    }

    @Test
    void cancellationFencesCompletionAndPendingClaims() {
        task("CHECKOUT_REPOSITORY", "cancel", List.of(), false);
        var lease = queue.claim("worker").orElseThrow();
        var lifecycle = lifecycle(4, 12);
        tx.executeWithoutResult(status -> lifecycle.cancel(run));
        assertThat(queue.complete(lease, () -> {
                    throw new AssertionError("Cancelled task mutated state");
                }))
                .isFalse();
        assertThat(queue.heartbeat(lease)).isFalse();
        assertThat(queue.claim("worker")).isEmpty();
        assertThat(db.one("SELECT status FROM analysis_task WHERE id=?", lease.id())
                        .get("status"))
                .isEqualTo("CANCELLED");
        assertThat(events.after(org, project, run, 0))
                .extracting(e -> e.get("type"))
                .contains("analysis.cancelled");
    }

    @Test
    void failureExhaustionTerminatesRunAndNoRetryResurrectsIt() {
        UUID id = task("CHECKOUT_REPOSITORY", "exhaust", List.of(), false);
        db.update("UPDATE analysis_task SET max_attempts=1 WHERE id=?", id);
        var lease = queue.claim("worker").orElseThrow();
        queue.fail(lease, "CHECKOUT_FAILED");
        assertThat(db.one("SELECT status FROM analysis_task WHERE id=?", id).get("status"))
                .isEqualTo("FAILED_PERMANENTLY");
        assertThat(db.one("SELECT status FROM analysis_run WHERE id=?", run).get("status"))
                .isEqualTo("FAILED");
        assertThat(queue.claim("worker")).isEmpty();
    }

    @Test
    void terminalDependenciesAllowPartialPipelineToContinue() {
        UUID failed = task("RUN_ANALYZER", "failed", List.of(), false);
        UUID tolerant = task("INGEST_ANALYZER", "tolerant", List.of(failed), true);
        task("DETECT_COMPONENTS", "strict", List.of(failed), false);
        db.update("UPDATE analysis_task SET status='FAILED_PERMANENTLY' WHERE id=?", failed);
        assertThat(queue.claim("worker").orElseThrow().id()).isEqualTo(tolerant);
        assertThat(queue.claim("worker")).isEmpty();
    }

    @Test
    void dependencyForeignKeysRejectCrossProjectEdges() {
        UUID first = task("CHECKOUT_REPOSITORY", "first", List.of(), false);
        UUID otherProject = UUID.randomUUID(), otherRun = UUID.randomUUID();
        db.update("INSERT INTO project(id,organization_id,name) VALUES (?,?,?)", otherProject, org, "Other");
        db.update(
                "INSERT INTO analysis_run(id,organization_id,project_id,repository_url,requested_by,config_hash) VALUES (?,?,?,?,?,?)",
                otherRun,
                org,
                otherProject,
                "other",
                UUID.randomUUID(),
                "other");
        UUID second =
                queue.enqueue(org, otherProject, otherRun, "CHECKOUT_REPOSITORY", "second", Map.of(), List.of(), false);
        assertThatThrownBy(() -> db.update(
                        "INSERT INTO analysis_task_dependency(organization_id,project_id,analysis_run_id,task_id,depends_on_task_id) VALUES (?,?,?,?,?)",
                        org,
                        project,
                        run,
                        first,
                        second))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void eventsReplayOnlyNewScopedEvents() {
        tx.executeWithoutResult(status -> {
            events.append(org, project, run, "first", "first", 10);
            events.append(org, project, run, "second", "second", 20);
        });
        var all = events.after(org, project, run, 0);
        assertThat(all).hasSize(2);
        long cursor = ((Number) all.getFirst().get("sequenceNumber")).longValue();
        assertThat(events.after(org, project, run, cursor))
                .extracting(e -> e.get("type"))
                .containsExactly("second");
        assertThat(events.after(UUID.randomUUID(), project, run, 0)).isEmpty();
    }

    @Test
    void idempotentRequestWorksAtQuotaAndNewProjectIsRejected() {
        db.update("DELETE FROM analysis_run WHERE id=?", run);
        var lifecycle = lifecycle(1, 12);
        var first = tx.execute(status -> start(lifecycle, project, "same"));
        var replay = tx.execute(status -> start(lifecycle, project, "same"));
        assertThat(replay.id()).isEqualTo(first.id());
        UUID other = UUID.randomUUID();
        db.update(
                "INSERT INTO project(id,organization_id,name,repository_path) VALUES (?,?,?,?)",
                other,
                org,
                "Other",
                "other-repository");
        assertThatThrownBy(() -> tx.execute(status -> start(lifecycle, other, "different")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("429");
    }

    @Test
    void organizationQuotaIsAtomicAcrossConcurrentProjectStarts() throws Exception {
        db.update("DELETE FROM analysis_run WHERE id=?", run);
        UUID other = UUID.randomUUID();
        db.update(
                "INSERT INTO project(id,organization_id,name,repository_path) VALUES (?,?,?,?)",
                other,
                org,
                "Other",
                "other-repository");
        var lifecycle = lifecycle(1, 12);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> {
                start.await();
                try {
                    return tx.execute(status -> start(lifecycle, project, "a")) != null;
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    return false;
                }
            });
            var b = executor.submit(() -> {
                start.await();
                try {
                    return tx.execute(status -> start(lifecycle, other, "b")) != null;
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    return false;
                }
            });
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private AnalysisLifecycle.Run start(AnalysisLifecycle lifecycle, UUID projectId, String key) {
        try {
            return lifecycle.start(projectId, new AnalysisLifecycle.Start(null, "HEAD"), key);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private AnalysisLifecycle lifecycle(int activeLimit, int hourlyLimit) {
        TenantContext tenant = mock(TenantContext.class);
        when(tenant.orgId()).thenReturn(org);
        when(tenant.userId()).thenReturn(UUID.randomUUID());
        Access access = mock(Access.class);
        when(access.project(any()))
                .thenAnswer(call ->
                        db.one("SELECT * FROM project WHERE organization_id=? AND id=?", org, call.getArgument(0)));
        when(access.run(any()))
                .thenAnswer(call -> db.one(
                        "SELECT * FROM analysis_run WHERE organization_id=? AND id=?", org, call.getArgument(0)));
        return new AnalysisLifecycle(
                db,
                access,
                tenant,
                new Audit(db),
                queue,
                events,
                mock(RepositoryPolicy.class),
                activeLimit,
                hourlyLimit);
    }
}
