# Streaming Ingestion Benchmark

Opt-in test: `backend/platform/src/test/java/io/semanticmap/platform/graph/StreamingIngestionBenchmark.java`. It uses real Testcontainers PostgreSQL, Flyway migrations, `FactIngestion`, contract schema, evidence verification, `Db`, and Spring transactions. No mocks, runtime changes, production/API data, or new dependencies. Its `*Benchmark` name keeps it out of default Surefire/Failsafe suites.

## Run After Resource Clearance

Use Java 21 and Maven 3.9.x from the repository root. Do not run alongside frontend performance work, another Maven compiler, or Docker builds. PostgreSQL `postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73` (the Compose image) and Testcontainers' helper/Ryuk image must already be cached; PostgreSQL uses a never-pull policy. Do not pull during the frontend window.

PowerShell, 1k smoke:

```powershell
mvn -pl backend/platform -am '-Dtest=StreamingIngestionBenchmark' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dsemanticmap.benchmark.factCount=1000' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2' '-DforkCount=1' '-DreuseForks=false' '-Djunit.jupiter.execution.parallel.enabled=false' test
```

Repeat with count `10000` for 10k. Full million, using the default count:

```powershell
mvn -pl backend/platform -am '-Dtest=StreamingIngestionBenchmark' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2' '-DforkCount=1' '-DreuseForks=false' '-Djunit.jupiter.execution.parallel.enabled=false' test
```

Do not add `clean`, parallel Maven, or `verify`. Source is prepared but **not compiled or executed yet**; these commands are not acceptance results.

The same test method has a standalone `main` entry point for running the JVM
inside Linux Docker with the compiled test classpath. This avoids measuring the
Windows-to-Docker published-port boundary on every JDBC call. It still creates
an isolated Testcontainers PostgreSQL instance and runs the same assertions.
The launcher must impose an external 60-minute timeout: JUnit's `@Timeout` does
not apply to a direct `main` invocation. Reports include the operating system
and Java runtime; timings from different topologies are not interchangeable.

## Workload and Limits

Default: 1,000,000 distinct facts across ten sequential 100k analyzer executions in one run, with distinct execution/component IDs. The count property accepts 1 through 1,000,000. Each execution uses one transaction, including commit in the ingestion timing. Generation streams one fact at a time and retains only one NDJSON shard on disk.

Each test-only METHOD fact references one real full source line; hashes exclude the final LF. There is one immutable source file per execution. Maximum planned row size is approximately 519 bytes including LF, or 49.5 MiB per 100k shard. Actual UTF-8 bytes are measured and checked against the existing 1 MiB line / 128 MiB execution limits; 100k is below the 250k fact limit.

The JVM requires `-Xmx512m`; the command exposes two processors. PostgreSQL is capped at two CPUs and 2 GiB memory with no additional swap allowance. The total JUnit test timeout is 60 minutes, each transaction 30 minutes, nontransactional JDBC queries 120 seconds, and connection/socket/startup waits are finite. These are operational safety bounds, not throughput requirements.

Current ingestion makes roughly three JDBC calls per fact: a million facts means roughly three million round trips and may take hours. Budget a conservative 12 GiB free Docker disk and 1 GiB host temporary space; these are planning allowances, not measured requirements. JSONB, generated columns, evidence, indexes, and WAL make storage larger than input bytes.

## Measurements and Acceptance

Stdout prints the report path, 30-second phase/heap heartbeats, and each chunk's bytes and generation/ingestion/query times. Progress counts only committed and verified facts, not the active transaction's uncommitted rows.

JSON is retained at `backend/platform/target/output/ingestion-benchmark/<timestamp>-<uuid>/metrics.json`, refreshed before ingestion and after each chunk. It includes the actual `SELECT version()` server string as `postgresServerVersion`, per-chunk and total counts, exact NDJSON/source bytes, maximum line bytes, separate generation/ingestion-plus-commit/verification/storage/setup times, sampled peak heap, GC counters before/after, and database/relation/index bytes. Heap sampling is every second plus boundary samples, not an instantaneous peak or RSS. Subtract matching GC counters for deltas; `-1` means unavailable. Relation totals include TOAST and overlap their heap/index components.

Assertions require exact fact/evidence counts, unique fact/stable IDs, one evidenced fact per fact, expected execution count, no quarantine, and verified source/hash/range/revision/analyzer provenance. Only a successful full count sets `millionFactAcceptancePassed: true`; smoke or incomplete runs do not. Throughput is reported without an invented SLA gate.

This deliberately cache-friendly workload measures streaming/schema/evidence/persistence, not native parsing, distinct-file or bind-mount performance, graph building, HTTP, or UI latency. Record host/storage contention when comparing runs.

## Cleanup

Normal success/failure closes only the owned container and deletes only its uniquely named temporary workspace/shard; metrics remain in `target/output`. No existing database, image, volume, or application container is changed or removed. Forced termination/OOM can prevent cleanup and final reporting; inspect incomplete artifacts and rerun rather than claiming acceptance.
