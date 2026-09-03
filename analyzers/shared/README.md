# Non-Java analyzer contract and runtime

The three analyzers are independent OCI CLIs with real parsers. All Docker builds
use the repository root as context, and all analyzer versions are `0.1.0`.

Runtime bases stay on Python 3.12 and Node 22 LTS with Debian Bookworm. Dockerfiles
pin the official current patch image indexes by digest and upgrade installed OS
packages from the configured Debian repositories during builds. Parser dependency
versions and protocol capabilities are unchanged. See `VERIFICATION.md` for the
base references, build verification and final analyzer image IDs.

```sh
docker build -f analyzers/tree-sitter/Dockerfile -t semanticmap/tree-sitter:0.1.0 .
docker build -f analyzers/postgresql/Dockerfile -t semanticmap/postgresql:0.1.0 .
docker build -f analyzers/typescript-node/Dockerfile -t semanticmap/typescript-node:0.1.0 .
```

Every image runs as UID/GID `65532:65532` and supports:

```sh
semantic-analyzer describe
semantic-analyzer analyze --request /input/request.json --output /output
```

Mount source at `/workspace` read-only, request files at `/input` read-only, and a
writable results directory at `/output`. Run with `--network none --read-only
--cap-drop ALL --security-opt no-new-privileges`, container memory/CPU/PID limits,
and a runner-enforced timeout. The analyzers never install project dependencies,
run builds, execute source code, connect to databases, or load repository plugins.

`SEMANTIC_WORKSPACE` overrides `/workspace` for local CLI tests. The output must
remain outside that source directory. `SEMANTIC_IMAGE_DIGEST` optionally supplies
the actual image digest from the runner; otherwise the manifest reports `null`.
No image digest is invented.

## Contract integration

Canonical schemas are read from `analyzer-contract/schemas/v1/`. Dockerfiles copy
that exact directory to `/opt/analyzer-contract/schemas/v1/`. Request validation
uses the canonical JSON Schema, with additional implementation resource limits.
Tests validate all result files and `describe` against the same directory.

`describe` contains `contractVersion`, `id`, `version`, `languages`, `frameworks`,
`capabilities`, `executionMode: SAFE_STATIC`, and `metadata`. Incremental support,
dependency requirements, and network policy are in `metadata`.

`manifest.json` contains:

```json
{
  "contractVersion": "1.0",
  "analyzer": {"id": "tree-sitter", "version": "0.1.0", "imageDigest": null},
  "status": "SUCCEEDED",
  "capabilitiesCompleted": ["SYMBOLS"],
  "capabilitiesPartial": [],
  "capabilitiesFailed": [],
  "factCount": 0,
  "diagnosticCount": 0,
  "startedAt": "2026-09-02T00:00:00Z",
  "finishedAt": "2026-09-02T00:00:01Z"
}
```

The example describes the envelope only; counts and timestamps are generated from
the actual run. No example facts are used by production code.

`coverage.json` has the following envelope; unknowns and extra counters stay in
the extensible `metadata` field:

```json
{
  "contractVersion": "1.0",
  "filesDiscovered": 1,
  "filesParsed": 1,
  "filesFailed": 0,
  "capabilities": [{"name": "CALL_GRAPH", "status": "PARTIAL", "limitations": ["UNRESOLVED_CALL_TARGETS"]}],
  "metadata": {"status": "PARTIAL", "unknown": ["UNRESOLVED_CALL_TARGETS"], "filesSkipped": 0, "sourceBytes": 100, "entriesVisited": 1}
}
```

`statistics.json` contains `contractVersion`, `factCount`, `diagnosticCount`,
`factsByKind`, and extensible `metadata` with file/byte/walk counters.

`facts.ndjson` uses the v1 fact envelope including `subject`, `properties`,
`origin`, `confidence`, and nonempty `evidence`. Generic facts include `name` and
`ownerKey`; root ownership is an empty string. Edges are `RELATION` facts with
`sourceKey`, `targetKey`, and `edgeKind` inside `properties`.

Evidence paths are repository-relative, even when analyzing a nested component.
Lines and columns are one-based; end columns are exclusive Unicode code-point
columns. A hash is `sha256:` followed by SHA-256 of complete UTF-8 source lines
from `startLine` through `endLine`, joined by LF with no final LF. CRLF is
normalized as line separators; a source BOM is retained. PostgreSQL statements
and embedded SQL can intentionally have broader evidence than one expression.

`diagnostics.ndjson` contains `code`, `severity`, `message`, nullable `filePath`
and `startLine`, and `metadata`. Unsupported capabilities, parse failures,
unresolved behavior and skipped relevant source prevent a fully successful run.
Capability coverage is conservative: a file-level gap marks requested supported
capabilities partial. Fact order and IDs are deterministic; run timestamps are not.

Exit codes: `0` complete, `10` partial, `20` invalid request, `30` no supported
source, `40` reserved analysis failure, `50` resource/path policy violation, and
`60` internal failure. An unwritable/unsafe output path or a budget too small for
the required envelopes can prevent output generation. Consumers must check exit
status and manifest before ingestion, including when a prior output exists.

## Bounds and exclusions

Both runtimes bound the walk to 100,000 entries, 10,000 matching files, directory
depth 64, 2 MiB per source file, and 64 MiB of source. Request size is bounded at
1 MiB, `.semanticmapignore` at 64 KiB, and diagnostics are capped. Duration is
clamped to 600 seconds and output to 256 MiB. At least 256 KiB of output budget is
required. Source parsing also needs the OCI runner's hard memory and wall limits:
an individual native parser call cannot always be interrupted by a cooperative
Python/JavaScript deadline check.

`.git`, `target`, `node_modules`, `.deps`, and `__pycache__` are excluded.
`dist`, `build`, `generated`, and `generated-sources` are excluded unless generated
source is requested. Test directories and `.test.`/`.spec.` files obey the request
policy. `.semanticmapignore` at the workspace root follows gitignore semantics,
including negation; an excluded directory is not traversed. Symlinks/junctions
are rejected, never followed. Source must be regular UTF-8 text without NUL bytes.
`changedFiles` requests receive a full component analysis and an explicit notice.

## Decisions and known limits

The Python and Node implementations share contract behavior and acceptance tests,
not a process bridge. This keeps the native compiler independent of Python.
Tree-sitter uses wheel-shipped grammar binaries and an in-process lazy parser
cache, without grammar downloads at runtime. PostgreSQL uses pglast's actual AST
and deparser. TypeScript uses an in-memory compiler host restricted to already
validated files. Repository `tsconfig`, package installation, and plugins cannot
expand its filesystem access.

SQL keys align with Java ORM mappings for explicit schema names:
`db:table:public.orders` and `db:column:public.orders.created_at`. Unqualified SQL
names stay unqualified and carry `schemaResolution: UNQUALIFIED`; no search path
or runtime catalog is assumed. SQL properties include `sourceLayer: DATABASE`
and ownership classifications so ownership facts are discoverable in graph views.

See each analyzer README for parser-specific coverage. Full migration-state
replay, Liquibase runtime execution, runtime dispatch, complete procedural control
flow, dependency resolution and incremental analysis remain unimplemented. The
PostgreSQL analyzer statically interprets a documented XML/YAML/JSON Liquibase
subset and native PL/pgSQL structural statements; unsupported semantics are
reported rather than synthesized.

## Verification

From the repository root with the pinned dependencies installed:

```sh
python -m unittest discover -s analyzers/shared/tests -v
python -m unittest discover -s analyzers/tree-sitter/tests -v
python -m unittest discover -s analyzers/postgresql/tests -v
node --test analyzers/typescript-node/tests/parser.test.mjs
python analyzers/shared/container_smoke.py tree-sitter
python analyzers/shared/container_smoke.py postgresql
python analyzers/shared/container_smoke.py typescript-node
```

The smoke helper runs each image twice with read-only source and root filesystems,
network denied, and non-root image users. It validates canonical schemas, complete
line evidence hashes, file counts, image identity, source immutability, and
deterministic facts. All test-generated files are inside owned `.test-output`
directories or temporary permission-test directories excluded from source control
and Docker contexts. On POSIX, only each newly created smoke output mount is
chmodded to `0777` so container UID 65532 can write it even when CI owns the host
directory. Source and input permissions are never changed. The production runtime
and its `SEMANTIC_IMAGE_DIGEST` environment variable are unchanged.
