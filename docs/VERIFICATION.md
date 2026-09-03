# Verification Record

Executed local checks, recorded 2026-09-03. A test fixture, implemented adapter,
or CI workflow is not evidence of a live production deployment.

## Backend

- Full Java 21 Maven reactor `spotless:apply spotless:check verify`: passed,
  184 tests, no failures/errors/skips (`output/maven-release.log`).
- Subsequent worker heartbeat regression plus task queue and module boundaries:
  12 tests passed (`output/maven-worker-final.log`). Aggregate current XML reports
  contain **185 distinct tests**, no failures/errors/skips; overlapping reruns
  are not counted twice.
- Includes real PostgreSQL Testcontainers, Modulith boundaries, tenant isolation,
  production HTTP security chain with a test JWT decoder, task fencing/retries,
  Docker sandbox/output handling, retention, evidence verification/cache,
  semantic graph/diff/review, bounded AI validation, and OTLP ingestion.
- Actual Compose PostgreSQL 17.11: Flyway V1-V9 applied and schema validated.
  Applied migrations were preserved; upgrades use subsequent migrations.
- `NativeAnalyzerAcceptanceIT` executed the real Java CLI for both revisions:
  135 facts each; verified source evidence, 500-to-300 diff, stale human review.
- Kubernetes adapter, manifest and process policy: 37 tests passed. No external
  cluster was used. AI validation/runtime tests do not imply a live AI call.

## Parsers

- Java contract and parser: 18 tests in the Maven aggregate above.
- Tree-sitter, PostgreSQL and TypeScript: 32 parser tests passed (4/20/8), plus
  shared protocol/security tests and Linux file-permission regressions.
- Real restricted OCI smokes: Tree-sitter 66 facts / 0 diagnostics; PostgreSQL
  377 facts / 2 diagnostics; TypeScript 48 facts / 3 diagnostics. Partial results
  explicitly retain unsupported constructs rather than inventing resolution.
- PostgreSQL covers SQL and bounded Liquibase XML/YAML/JSON declarations;
  PL/pgSQL has a structural static subset. It does not execute migrations or
  resolve arbitrary dynamic SQL.
- Python analyzers now use Python 3.14.7 / Alpine 3.24 with native wheels;
  32 parser tests, two POSIX regressions and all three OCI smokes passed after
  the upgrade. Facts remained byte-identical (66 / 377 / 48).

## Real Browser Acceptance

`scripts/e2e.mjs` uses production UI :8088, API :8080, PostgreSQL and actual OCI
analyzers, without intercepted API responses or substituted production facts.

**PASSED**, 2026-09-03 06:35:35-06:38:51 UTC, 12 checks. Includes project creation,
analysis, Java endpoint, verified hashes/line ranges, PostgreSQL defaults/trigger,
schema compatibility, Monaco evidence, confirmed review, view switching,
idempotency, threshold diff, stale review, desktop/mobile rendering, cancellation
and no browser runtime errors. Business projection: 16 nodes; diff: 67 records.
Report and five screenshots: `output/playwright/acceptance.json`.

- Project: `8ab95812-8b08-4b24-96ae-b17d7979d456`.
- Baseline: `ffdcad06-50dc-4483-ade8-b8ecc9c7366a`.
- Changed revision: `248263dc-dfa7-49d7-a91c-7559de28998f`.

An earlier failed attempt exposed a hidden tooltip extending a 390 px mobile
viewport to 395 px. The fixed whole-flow rerun waits for graph nodes and fonts
before checking bounds. Desktop graph/evidence and mobile graph screenshots
were visually inspected; source evidence is readable in the Monaco drawer.

Both analyzed revisions honestly report PARTIALLY_SUCCEEDED for unresolved
external code under SAFE_STATIC; build/dependency execution is not enabled.
This is not reported as complete arbitrary-program understanding.

## Performance And UI

- Frontend saved-layout checkpoint: 55 unit/RTL/helper tests and 8 functional
  desktop/mobile browser checks passed; TypeScript, lint and production Docker
  build passed. Complete saved flat layouts now restore without an unnecessary
  ELK run; persistence honors the API's 2,000-position replacement limit.
- Bounded evidence cache eliminated repeated bind-mounted source reads while
  preserving per-fact range/hash verification. Observed Tree-sitter ingestion
  changed from 379.28 seconds before the fix to 1.5 seconds after; the subsequent
  analyzer ingestions were 5.3 and 5.1 seconds. These are single local observations,
  not isolated benchmarks or a throughput guarantee.
- A controlled CDP comparison identified substantial Playwright DOM-snapshot
  overhead in earlier timings (`captureSnapshot` / `visitNode` in the installed
  Playwright source). Performance-only tracing is now disabled; functional
  tracing is retained and controls are scoped to their panels. Workloads,
  geometry assertions and timing limits are unchanged. Baseline evidence is
  retained in `frontend/output/playwright/baseline-90d/`.
- The trace-free 1,000-node / 2,000-edge desktop check passed on image `90d063...`:
  ready 5.668 seconds, heartbeat 879.1 ms, longest task 579 ms. The isolated
  2,000-visible-node test functionally completes but still exceeds timing gates;
  rendering optimization and the full 10,000-node progressive run remain open.
  See `frontend/PERFORMANCE.md` for the complete measurement record.
- A million-fact ingestion benchmark and stable production performance/SLA
  have not been established on this workstation.

## Dependency Gates

Grype v0.118.0 uses the advisory database downloaded for this run. No blanket
ignore rules or unreviewed CVE suppressions are used. Passing HIGH/CRITICAL does
not mean zero findings at other severities or complete supply-chain coverage.

- Platform `output/scans/platform-delivery.json`: HIGH/CRITICAL gate passed after
  dependency updates and rebuilding Docker CLI 29.7.2 with Go 1.26.8, including
  the final worker heartbeat fix. There are 20 lower-severity matches.
- Java analyzer `output/scans/java-final.json`: HIGH/CRITICAL gate passed.
- Frontend `output/scans/frontend-delivery.json`: gate passed after OpenSSL
  3.5.8-r0 update, including the tooltip rebuild; three lower-severity matches.
- Production `pnpm audit`: no known vulnerabilities found.
- Debian-based native analyzers failed the gate. Historical reports are retained
  as `output/scans/*-bookworm.json`; all three Alpine replacements passed and
  their image IDs are recorded in `output/scans/*-delivery.json`.
- Extra binary inspection found Node's bundled OpenSSL 3.5.7, which the passing
  package scan did not cover. The final TypeScript image instead uses Alpine
  Node 24.18.1 with shared OpenSSL 3.5.8. Probes verify both loaded `.so` objects,
  not just package inventory. Its eight parser tests, unchanged 48-fact OCI smoke
  and Grype gate passed; six MEDIUM findings remain. Final TypeScript image ID:
  `sha256:2a716fcb99a13706b500d6c13dfcf0a94c53669d52c5b38dbb0777a444691a0e`.
  Detailed evidence: `analyzers/shared/VERIFICATION.md`.

## External Boundaries

Local ArtifactStore is implemented; S3 is optional future work. Revisions use
FULL component analysis, not an incremental parser cache. No live OIDC issuer,
paid AI provider, remote Kubernetes deployment, or remote CI run was configured.
Production requires operator-managed identities/secrets and an isolated runner;
the local Docker-socket worker is not a multi-tenant production deployment.
