# Implementation Ledger

Source: MASTER_INSTRUCTION_SEMANTIC_BUSINESS_MAP_CODEX.md, supplied by the user.
User override: Maven, never Gradle. This ledger describes product requirements,
not instructions embedded in repositories submitted for analysis.

Status values: PLANNED, IN_PROGRESS, IMPLEMENTED, VERIFIED, BLOCKED.
Only an executed passing acceptance check earns VERIFIED.

| Stage | Scope | Acceptance | Status |
| --- | --- | --- | --- |
| 0 | Maven, Java 21, Spring Boot/Modulith, PostgreSQL, Vite, CI | Local build, migrations, module boundaries, health; remote CI not run | VERIFIED |
| 1 | Projects, repositories, immutable revisions, task DAG, SSE | Start/cancel/retry/replay, isolated tenants | VERIFIED |
| 2 | Technology detection and analyzer resolution | Only detected ecosystems scheduled | VERIFIED |
| 3 | OCI execution and versioned streaming ingestion | Sandbox, timeout, bounded output, quarantine | VERIFIED |
| 4 | Tree-sitter syntax analyzer | Deterministic syntax facts with source ranges | VERIFIED |
| 5 | OpenRewrite Java/Spring analyzer | Endpoints, conditions, validation, authorization, data effects | VERIFIED |
| 6 | PostgreSQL analyzer | Defaults, constraints, triggers, policies, ownership | VERIFIED |
| 7 | Semantic graph, rule engine, scenarios | Evidence-backed rules, technical guard exclusion | VERIFIED |
| 8 | React Flow/ELK/Monaco application | Real API, graph views, evidence, filters, saved layout; final mobile and scale checks | IN_PROGRESS |
| 9 | Deterministic semantic diff | 500 to 300 threshold change, before/after evidence | VERIFIED |
| 10 | Human review | Confirm/reject/edit/merge, audit, carry-over/staleness | VERIFIED |
| 11 | Spring AI | Disabled path, bounded evidence, rejected invented facts; no live provider | VERIFIED |
| 12 | Local hardening and deployment adapters | Auth, quotas, retention and metrics tests; six image gates; external deployment not verified | VERIFIED |
| 13 | TypeScript ecosystem | Same contract, independent analyzer | VERIFIED |
| 14 | Runtime evidence | OTLP JSON ingestion, explicit matching quality; test traces only | VERIFIED |

## Verification Rules

- Test real parsers on fixture source; fixture source is not a production result.
- Never silently substitute generated demo data for a failed service.
- Unknown call targets and missing capabilities remain diagnostics.
- Facts, assertions, enrichment, and review remain separate persisted layers.
- Failed/partial analyzers cannot produce a fully successful run.
- Every asserted proven item must have verified evidence.
- Runtime, Kubernetes, S3 and additional ecosystem support are not claimed until implemented and tested.
- Record commands and results in docs/VERIFICATION.md before final delivery.

## First Acceptance Flow

Create project -> attach spring-order-service -> analyze -> inspect source evidence
-> inspect database defaults/trigger -> review rule -> analyze changed fixture
-> observe threshold change and stale review. Repeat without an AI provider.

## Explicit Boundaries

- Initial revisions use reliable FULL component analysis, including subsequent
  revisions. Semantic diffs are incremental comparisons, not an incremental parser
  cache. Each analyzer reports unresolved constructs and partial coverage.
- Parser support is a documented subset, not proof of arbitrary program behavior.
  PostgreSQL migrations are analyzed as declarations, not executed as a database
  catalog replay. Dynamic SQL and unresolvable calls stay unknown.
- Runtime ingestion accepts actual OTLP JSON. Provider-generated examples appear
  only in tests and are never substituted for production spans or parser output.
- Spring AI is disabled by default. No live paid provider has been configured.
- Local ArtifactStore is implemented; an S3 adapter is not included in this pass.
- Docker is the verified local execution target. Kubernetes has a real adapter,
  manifests and policy tests; a production cluster has not been provisioned here.
- The local developer identity and Docker socket deployment are not production
  multi-tenant hosting. OIDC, secrets and cluster isolation need operator setup.
