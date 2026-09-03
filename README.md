# Semantic Business Map

Evidence-first source analysis with a Java 21 / Spring Boot modular backend,
PostgreSQL, isolated OCI analyzers, and a Vite / React Flow workspace.

[Russian quick start](docs/QUICKSTART_RU.md).

Implementation and verification are tracked in [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md).
A feature is not verified merely because an endpoint or screen exists.
Executed results and explicit deployment limits are recorded in
[docs/VERIFICATION.md](docs/VERIFICATION.md). The real local acceptance flow
passed on 2026-09-03, including source evidence, review, the 500-to-300 threshold
change and desktop/mobile rendering. Large-graph and dependency checks are
tracked separately; this is not a claim of production certification.

## Local Stack

Requirements: Docker Desktop with Linux containers. The local Compose stack is
development-only and binds public ports to loopback. Ports: UI 8088, API 8080,
PostgreSQL 55432. No AI account is required.

Windows PowerShell, from the repository root:

```powershell
.\scripts\dev.ps1 -Build
```

Linux/macOS:

```sh
make analyzers
make dev
```

Open http://127.0.0.1:8088. Create a project with repository path
`/repository/fixtures/spring-order-service` when using Compose, or
`fixtures/spring-order-service` when running the backend directly from this root.
Start an analysis. Its outputs, diagnostics and coverage come from real parser
executions. The changed fixture is `fixtures/spring-order-service-revision2`.

`ARTIFACT_HOST_PATH` must be the absolute host path to `.runtime/artifacts` when
the worker runs in a container. The startup script sets this automatically.
This lets sibling analyzer containers mount the exact immutable source snapshot.

## Development

Requirements: Java 21, Node 22+, pnpm, Docker. Maven Wrapper is included; Gradle is
not used. Start PostgreSQL, build the analyzer images, then run API and frontend:

```sh
docker compose up -d postgres
make analyzers
./mvnw -B -ntp package -DskipTests
java -jar backend/platform/target/platform-0.1.0-SNAPSHOT.jar
```

In a second terminal:

```sh
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

On Windows use `mvnw.cmd` in place of `./mvnw`; set `JAVA_HOME` to a Java 21 JDK.
The development UI is http://127.0.0.1:5173. The API's live OpenAPI document is
available at `/v3/api-docs` after authentication.

## Verification

```sh
./mvnw -B -ntp verify
cd frontend
pnpm lint
pnpm test
pnpm build
pnpm test:e2e
```

Testcontainers requires access to the Docker daemon. Integration tests fail
when Docker is unavailable; they are not silently reported as passing.
See analyzer-specific READMEs for parser and contract tests.

## Configuration

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | PostgreSQL connection |
| `SPRING_PROFILES_ACTIVE=dev` | Explicit local identity, development only |
| `SPRING_PROFILES_ACTIVE=production` | JWT/OIDC authentication |
| `OIDC_ISSUER_URI`, `OIDC_AUDIENCE` | Trusted issuer and audience |
| `WORKER_ENABLED` | Run the durable task worker in this process |
| `ARTIFACT_ROOT`, `ARTIFACT_HOST_PATH` | Runtime artifacts and sibling mounts |
| `REPOSITORY_ROOTS` | Allowed local repository roots in development |
| `AI_BASE_URL`, `AI_MODEL`, `AI_API_KEY` | Operator-configured optional provider |

Production JWTs must carry trusted `organization_id`, `user_id` UUID claims and
`roles`. Organization admins can provision their organization through
`POST /api/v1/organization`; project membership restricts other users.
Repository credentials are references to operator-provided secrets, not database values.

## Architecture

- `analyzer-contract`: versioned JSON Schemas and language-neutral protocol records.
- `analyzers`: native parser processes, no direct database access.
- `backend/platform`: feature packages verified with Spring Modulith.
- `frontend`: generated API types, shared design tokens, React Flow, ELK worker, Monaco.
- `fixtures`: source repositories and expected evidence, never production results.
- `docs/adr`: architecture and trust-boundary decisions.

The source of truth is the immutable analyzed revision and its verified facts.
Assertions, optional AI wording, human decisions and visual layout are separate.
Unknown resolutions and incomplete capabilities remain visible.

## Deployment Boundary

The development worker has Docker socket access and therefore host-level Docker
control. Do not expose it or reuse this deployment for untrusted tenants.
Analyzers themselves run as non-root with no network, read-only source/root,
resource limits and timeouts. Production infrastructure must provide an isolated
runner and OIDC/secret policy; see [SECURITY.md](SECURITY.md).
