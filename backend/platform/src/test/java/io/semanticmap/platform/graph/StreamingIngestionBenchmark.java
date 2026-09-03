package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.contract.Protocol;
import io.semanticmap.platform.graph.internal.FactIngestion;
import io.semanticmap.platform.shared.Db;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Explicit Surefire selection only; see docs/INGESTION_BENCHMARK.md. */
class StreamingIngestionBenchmark {
    private static final int MILLION = 1_000_000;
    private static final int CHUNK = 100_000;
    private static final long MIB = 1024L * 1024;
    private static final String IMAGE =
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73";
    private static final String ANALYZER = "streaming-benchmark";
    private static final String VERSION = "test-only-v1";
    private static final String SOURCE = "// Synthetic benchmark evidence only.";
    private final ObjectMapper mapper = new ObjectMapper();

    private record Scope(UUID org, UUID project, UUID revision, UUID run) {}

    public static void main(String[] args) throws Throwable {
        new StreamingIngestionBenchmark().streamsRealIngestionIntoPostgres();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    void streamsRealIngestionIntoPostgres() throws Throwable {
        int count = Integer.parseInt(System.getProperty("semanticmap.benchmark.factCount", "1000000"));
        assertThat(count).isBetween(1, MILLION);
        assertThat(Runtime.version().feature()).as("Use Java 21").isEqualTo(21);
        assertThat(ManagementFactory.getRuntimeMXBean().getInputArguments())
                .as("Use a Surefire fork with -Xmx512m")
                .anyMatch(arg -> arg.equalsIgnoreCase("-Xmx512m"));
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(512 * MIB);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("backend/platform/pom.xml"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Run from the repository or a Maven module");
        Path reports = Files.createDirectories(root.resolve("backend/platform/target/output/ingestion-benchmark"));
        Path report = Files.createDirectory(reports.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID()));
        Path tempParent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path temp = Files.createTempDirectory(tempParent, "semanticmap-ingestion-benchmark-");
        var metrics = new LinkedHashMap<String, Object>();
        var chunks = new ArrayList<Map<String, Object>>();
        var peak = new AtomicLong();
        var committed = new AtomicLong();
        var phase = new AtomicReference<>("setup");
        long started = System.nanoTime();
        Throwable failure = null;
        var sampler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "ingestion-benchmark-sampler");
            thread.setDaemon(true);
            return thread;
        });
        metrics.putAll(Map.of(
                "status",
                "RUNNING",
                "requestedFacts",
                count,
                "chunkSize",
                CHUNK,
                "postgresImage",
                IMAGE,
                "heapLimitBytes",
                Runtime.getRuntime().maxMemory(),
                "gcBefore",
                gc(),
                "startedAt",
                Instant.now().toString(),
                "chunks",
                chunks));
        metrics.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        metrics.put("javaVersion", System.getProperty("java.runtime.version"));
        metrics.put("operatingSystem", System.getProperty("os.name"));
        System.out.println("Benchmark metrics: " + report.resolve("metrics.json"));
        try (var postgres = new PostgreSQLContainer<>(
                        DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withImagePullPolicy(ignored -> false)
                .withStartupTimeout(java.time.Duration.ofMinutes(2))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withMemory(2048 * MIB)
                        .withMemorySwap(2048 * MIB)
                        .withNanoCPUs(2_000_000_000L))) {
            report(report, metrics);
            sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(heap(), Math::max), 0, 1, TimeUnit.SECONDS);
            sampler.scheduleAtFixedRate(
                    () -> System.out.printf(
                            Locale.ROOT,
                            "Benchmark: %s, committed-and-verified=%,d, elapsed=%.1fs, heap=%,d bytes%n",
                            phase.get(),
                            committed.get(),
                            seconds(started),
                            heap()),
                    30,
                    30,
                    TimeUnit.SECONDS);
            postgres.start();
            var source =
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var properties = new Properties();
            properties.setProperty("connectTimeout", "10");
            properties.setProperty("socketTimeout", "150");
            source.setConnectionProperties(properties);
            Flyway.configure().dataSource(source).load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.setQueryTimeout(120);
            var db = new Db(jdbc, mapper);
            metrics.put(
                    "postgresServerVersion",
                    db.one("SELECT version() AS version").get("version"));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            transactions.setTimeout(1800);
            Path workspace = Files.createDirectory(temp.resolve("workspace"));
            var scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            seed(db, scope, workspace);
            var ingestion = new FactIngestion(db, mapper);
            metrics.put("analysisRunId", scope.run());
            metrics.put("setupSeconds", seconds(started));
            for (int offset = 0, index = 0; offset < count; offset += CHUNK, index++) {
                int size = Math.min(CHUNK, count - offset);
                UUID execution = UUID.randomUUID();
                String component = "component-" + index;
                Path facts = temp.resolve("facts.ndjson");
                var chunk = new LinkedHashMap<String, Object>();
                chunks.add(chunk);
                chunk.putAll(Map.of("executionId", execution, "index", index, "facts", size));
                phase.set("generation execution " + (index + 1));
                long chunkStarted = System.nanoTime();
                long timer = System.nanoTime();
                Path evidence =
                        Files.createDirectory(workspace.resolve(component)).resolve("source.txt");
                Files.writeString(evidence, SOURCE + "\n");
                long[] bytes = generate(facts, component, offset, size);
                chunk.putAll(Map.of(
                        "generationSeconds",
                        seconds(timer),
                        "ndjsonBytes",
                        bytes[0],
                        "maxLineBytesIncludingLf",
                        bytes[1],
                        "sourceBytes",
                        Files.size(evidence)));
                seedExecution(db, scope, execution, component, facts);
                report(report, metrics);
                phase.set("ingestion-and-commit execution " + (index + 1));
                timer = System.nanoTime();
                transactions.executeWithoutResult(tx -> ingestion.ingest(
                        scope.org(), scope.project(), scope.revision(), scope.run(), execution, workspace, facts));
                chunk.put("ingestionSeconds", seconds(timer));
                phase.set("verification execution " + (index + 1));
                timer = System.nanoTime();
                chunk.put("counts", verify(db, scope, execution, size));
                chunk.put("verificationSeconds", seconds(timer));
                committed.addAndGet(size);
                chunk.put("elapsedSeconds", seconds(chunkStarted));
                metrics.put("committedVerifiedFacts", committed.get());
                metrics.put("sampledPeakHeapBytes", peak.accumulateAndGet(heap(), Math::max));
                report(report, metrics);
                System.out.printf(
                        Locale.ROOT,
                        "Execution %d: %,d facts, %,d bytes; generation %.3fs, ingestion+commit %.3fs, queries %.3fs%n",
                        index + 1,
                        size,
                        bytes[0],
                        chunk.get("generationSeconds"),
                        chunk.get("ingestionSeconds"),
                        chunk.get("verificationSeconds"));
                Files.delete(facts);
            }
            phase.set("final-verification");
            long timer = System.nanoTime();
            metrics.put("totals", verify(db, scope, null, count));
            metrics.put("finalVerificationSeconds", seconds(timer));
            timer = System.nanoTime();
            metrics.put(
                    "relations",
                    db.rows(
                            """
                    SELECT name, pg_relation_size(name::regclass) AS heap_bytes,
                    pg_indexes_size(name::regclass) AS index_bytes, pg_total_relation_size(name::regclass) AS total_bytes
                    FROM (VALUES ('raw_fact'), ('evidence'), ('fact_quarantine')) AS relations(name)
                    """));
            metrics.put(
                    "databaseBytes",
                    db.one("SELECT pg_database_size(current_database()) AS bytes")
                            .get("bytes"));
            metrics.put("storageQuerySeconds", seconds(timer));
            for (String key : List.of("generationSeconds", "ingestionSeconds", "verificationSeconds"))
                metrics.put(
                        key,
                        chunks.stream()
                                .mapToDouble(c -> ((Number) c.get(key)).doubleValue())
                                .sum());
            for (String key : List.of("ndjsonBytes", "sourceBytes"))
                metrics.put(
                        key,
                        chunks.stream()
                                .mapToLong(c -> ((Number) c.get(key)).longValue())
                                .sum());
            metrics.put("ingestionFactsPerSecond", count / ((Number) metrics.get("ingestionSeconds")).doubleValue());
            phase.set("cleanup");
        } catch (Throwable ex) {
            failure = ex;
        } finally {
            sampler.shutdownNow();
        }
        try {
            Path resolved = temp.toRealPath();
            if (!resolved.getParent().equals(tempParent)
                    || !resolved.getFileName().toString().startsWith("semanticmap-ingestion-benchmark-"))
                throw new IOException("Refusing cleanup outside owned temporary directory: " + resolved);
            // This owned tree has at most ten source files, ten directories and one shard; do not follow links.
            try (var files = Files.walk(resolved)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        } catch (Throwable ex) {
            if (failure == null) failure = ex;
            else failure.addSuppressed(ex);
        }
        metrics.putAll(Map.of(
                "status",
                failure == null ? "PASSED" : "FAILED",
                "millionFactAcceptancePassed",
                failure == null && count == MILLION,
                "sampledPeakHeapBytes",
                peak.accumulateAndGet(heap(), Math::max),
                "gcAfter",
                gc(),
                "elapsedSecondsIncludingCleanup",
                seconds(started)));
        if (failure != null) metrics.put("failure", failure.toString());
        try {
            report(report, metrics);
        } catch (Throwable ex) {
            if (failure == null) failure = ex;
            else failure.addSuppressed(ex);
        }
        if (failure != null) throw failure;
        System.out.println(
                "Benchmark PASSED; millionFactAcceptancePassed=" + (count == MILLION) + "; metrics=" + report);
    }

    private long[] generate(Path output, String component, int offset, int count) throws IOException {
        var evidence = List.of(
                new Protocol.Evidence(component + "/source.txt", 1, 1, 1, SOURCE.length() + 1, Protocol.hash(SOURCE)));
        var properties = Map.<String, Object>of(
                "name", "syntheticBenchmarkMethod", "ownerKey", "benchmark:" + component, "benchmarkOnly", true);
        var writer = mapper.writerFor(Protocol.Fact.class);
        long bytes = 0, maxLineBytes = 0;
        try (var stream = new BufferedOutputStream(Files.newOutputStream(output), 64 * 1024)) {
            for (int i = 0; i < count; i++) {
                String key = "benchmark:fact:" + (offset + i);
                byte[] line = writer.writeValueAsBytes(new Protocol.Fact(
                        Protocol.VERSION,
                        key,
                        "METHOD",
                        key,
                        new Protocol.Subject("METHOD", key),
                        properties,
                        "STATIC_SYNTAX",
                        0.75,
                        evidence));
                if (line.length >= MIB || bytes + line.length + 1 > 128 * MIB)
                    throw new IOException("NDJSON bounds exceeded");
                stream.write(line);
                stream.write('\n');
                bytes += line.length + 1;
                maxLineBytes = Math.max(maxLineBytes, line.length + 1);
            }
        }
        assertThat(Files.size(output)).isEqualTo(bytes);
        return new long[] {bytes, maxLineBytes};
    }

    private static Map<String, Object> verify(Db db, Scope s, UUID execution, int expected) {
        var counts = db.one(
                """
                SELECT count(*) AS facts, count(DISTINCT fact_id) AS fact_ids, count(DISTINCT stable_key) AS stable_keys,
                count(DISTINCT analyzer_execution_id) AS executions FROM raw_fact
                WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND (?::uuid IS NULL OR analyzer_execution_id=?)
                """,
                s.org(),
                s.project(),
                s.run(),
                execution,
                execution);
        for (String key : List.of("facts", "factIds", "stableKeys"))
            assertThat(((Number) counts.get(key)).longValue()).as(key).isEqualTo(expected);
        assertThat(((Number) counts.get("executions")).longValue()).isEqualTo((expected + CHUNK - 1) / CHUNK);
        counts.putAll(db.one(
                """
                SELECT count(*) AS evidence, count(DISTINCT e.raw_fact_id) AS evidenced_facts,
                count(*) FILTER (WHERE e.verified AND e.source_available AND e.expired_at IS NULL
                  AND e.snippet=? AND e.snippet_hash=? AND e.start_line=1 AND e.end_line=1 AND e.start_column=1
                  AND e.end_column=? AND e.analyzer_id=? AND e.analyzer_version=? AND e.image_digest IS NULL
                  AND e.origin='STATIC_SYNTAX' AND e.revision_id=? AND f.revision_id=e.revision_id
                  AND f.analyzer_execution_id=e.analyzer_execution_id AND f.payload->>'contractVersion'=?
                  AND f.payload #>> '{evidence,0,filePath}'=e.file_path
                  AND f.payload #>> '{evidence,0,snippetHash}'=e.snippet_hash) AS valid_evidence
                FROM evidence e JOIN raw_fact f ON f.id=e.raw_fact_id AND f.organization_id=e.organization_id
                  AND f.project_id=e.project_id AND f.analysis_run_id=e.analysis_run_id
                WHERE e.organization_id=? AND e.project_id=? AND e.analysis_run_id=? AND (?::uuid IS NULL OR e.analyzer_execution_id=?)
                """,
                SOURCE,
                Protocol.hash(SOURCE),
                SOURCE.length() + 1,
                ANALYZER,
                VERSION,
                s.revision(),
                Protocol.VERSION,
                s.org(),
                s.project(),
                s.run(),
                execution,
                execution));
        for (String key : List.of("evidence", "evidencedFacts", "validEvidence"))
            assertThat(((Number) counts.get(key)).longValue()).as(key).isEqualTo(expected);
        counts.putAll(db.one(
                """
                SELECT count(*) AS quarantined FROM fact_quarantine
                WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND (?::uuid IS NULL OR analyzer_execution_id=?)
                """,
                s.org(),
                s.project(),
                s.run(),
                execution,
                execution));
        assertThat(((Number) counts.get("quarantined")).longValue()).isZero();
        return counts;
    }

    private static void seed(Db db, Scope s, Path workspace) {
        UUID user = UUID.randomUUID();
        db.update("INSERT INTO organization(id,name) VALUES (?,?)", s.org(), "Test-only ingestion benchmark");
        db.update(
                "INSERT INTO user_account(id,subject,display_name) VALUES (?,?,?)", user, user.toString(), "Benchmark");
        db.update(
                "INSERT INTO project(id,organization_id,name) VALUES (?,?,?)",
                s.project(),
                s.org(),
                "Synthetic benchmark");
        db.update(
                "INSERT INTO revision(id,organization_id,project_id,commit_sha,branch,fingerprint) VALUES (?,?,?,?,?,?)",
                s.revision(),
                s.org(),
                s.project(),
                "test-only",
                "benchmark",
                Protocol.hash("test-only"));
        db.update(
                """
                INSERT INTO analysis_run(id,organization_id,project_id,revision_id,status,repository_url,requested_by,config_hash,workspace_path)
                VALUES (?,?,?,?,'RUNNING',?,?,?,?)
                """,
                s.run(),
                s.org(),
                s.project(),
                s.revision(),
                workspace.toUri().toString(),
                user,
                Protocol.hash("benchmark"),
                workspace.toString());
        db.update(
                "INSERT INTO analyzer_definition(id,analyzer_key,version,image_reference,enabled) VALUES (?,?,?,?,false)",
                UUID.randomUUID(),
                ANALYZER,
                VERSION,
                "test-only:generated-ndjson");
    }

    private static void seedExecution(Db db, Scope s, UUID execution, String root, Path output) {
        UUID component = UUID.randomUUID();
        db.update(
                """
                INSERT INTO technology_component(id,organization_id,project_id,revision_id,analysis_run_id,root_path,
                languages,frameworks,build_systems,database_technologies,evidence,fingerprint)
                VALUES (?,?,?,?,?,?,'[]','[]','[]','[]','[]',?)
                """,
                component,
                s.org(),
                s.project(),
                s.revision(),
                s.run(),
                root,
                Protocol.hash(root));
        db.update(
                """
                INSERT INTO analyzer_execution(id,organization_id,project_id,revision_id,analysis_run_id,component_id,
                analyzer_key,version,image_reference,status,reason,output_path,finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,'SUCCEEDED',?,?,now())
                """,
                execution,
                s.org(),
                s.project(),
                s.revision(),
                s.run(),
                component,
                ANALYZER,
                VERSION,
                "test-only:generated-ndjson",
                "Test-only streaming benchmark",
                output.toString());
    }

    private void report(Path directory, Map<String, Object> metrics) throws IOException {
        Path pending = directory.resolve("metrics.pending.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(pending.toFile(), metrics);
        Files.move(pending, directory.resolve("metrics.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static long heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static double seconds(long start) {
        return (System.nanoTime() - start) / 1_000_000_000.0;
    }

    private static List<Map<String, Object>> gc() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(bean -> Map.<String, Object>of(
                        "name",
                        bean.getName(),
                        "collectionCount",
                        bean.getCollectionCount(),
                        "collectionTimeMillis",
                        bean.getCollectionTime()))
                .toList();
    }
}
