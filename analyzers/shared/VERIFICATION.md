# Non-Java analyzer verification

## Current workspace regression check — 2026-09-05

Pinned dependencies were restored into the ignored local `.deps` and
`node_modules` directories. This check used the bundled Windows Python 3.12 and
Node runtimes, not rebuilt OCI images.

- TypeScript: `node --test tests/parser.test.mjs`, **12 passed**. Includes real
  compiler call resolution with repeated evidence, the 32-location ingestion
  bound, output-byte accounting, rejected build/dependency execution policies,
  schema validation and a Windows directory-junction regression.
- Tree-sitter: `python -m unittest discover -s analyzers/tree-sitter/tests -v`,
  **4 passed** with original-source evidence validation.
- PostgreSQL: `python -m unittest discover -s analyzers/postgresql/tests -v`,
  **20 passed, 1 skipped**. Cyclic includes and traversal execute independently
  of the file-symlink case.
- Shared transport: `python -m unittest discover -s analyzers/shared/tests
  -p test_runtime.py -v`, **10 passed, 2 skipped**. Includes output-path
  canonicalization, relation evidence retention/bounds and SAFE_STATIC policy
  rejection. The file/output byte budgets run independently of symlink cases.

The three skips are limited to actual symlink creation: Windows returned
`WinError 1314` because this account lacks the required privilege. Unexpected
symlink errors still fail the tests; these cases remain enabled on Linux or a
Windows account with that privilege. The separate Node junction test passed.
An initial attempt exposed those missing test prerequisites and has not been
reported as a passing run.

No OCI, vulnerability, or live-database acceptance was performed for these
changes: Docker Desktop's Linux engine cannot start while host virtualization
is unavailable. Earlier image evidence below is historical and does not verify
the modified analyzer sources.

## Historical verification

Verified on 2026-09-02 in the supplied Windows workspace, using the bundled
Python 3.12 and Node runtimes and Docker Desktop's Linux engine.

## Base image refresh

The 2026-09-02 bounded refresh keeps Python on 3.12, Node on 22 LTS, and both on
Debian Bookworm. Application/parser dependency versions, output schemas and
describe capabilities did not change. The official image indexes were resolved
with `docker buildx imagetools inspect` and pinned in all three Dockerfiles:

```text
python:3.12.14-slim-bookworm@sha256:782412e85d0f0984994c290652577d4018aff08145c85b262bb63dc0c7522254
node:22.23.2-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5
```

These replace Python 3.12.10 and Node 22.15.0 bases. Official current-series tags
were checked against the [Python image library](https://raw.githubusercontent.com/docker-library/official-images/master/library/python)
and [Node image library](https://raw.githubusercontent.com/docker-library/official-images/master/library/node).
Builds used `--pull`; `apt-get update` and `apt-get upgrade` completed successfully
and reported zero packages pending upgrade on the refreshed bases. Future security
refreshes should invalidate build cache (`--no-cache`) before rescanning.

Actual in-container probes returned Python 3.12.14 in both Python analyzers and
Node v22.23.2 in TypeScript. All 32 parser tests were rerun inside the updated
non-root, network-denied, read-only images: Tree-sitter 4, PostgreSQL 20 and
TypeScript 8. Only ephemeral `.test-output`/`/tmp` tmpfs mounts were writable.
Both Linux smoke permission tests passed again. All three OCI smoke helpers
passed against the new image IDs below, with unchanged fact/diagnostic counts.

No Maven or vulnerability scanner was run during this refresh. The parent owns
the final six-image Grype scan; no zero-advisory claim is made here.

## Executed acceptance tests

| Suite | Result |
| --- | --- |
| `python -m unittest discover -s analyzers/tree-sitter/tests -v` | 4 passed |
| `python -m unittest discover -s analyzers/postgresql/tests -v` | 20 passed |
| `python -m unittest discover -s analyzers/shared/tests -v` | 9 passed |
| `node --test analyzers/typescript-node/tests/parser.test.mjs` | 8 passed |

All 41 tests passed after the PostgreSQL extension. The two shared smoke-output
permission tests also passed inside the final Linux image as UID/GID 65532, with
network denied, a read-only root and an ephemeral `/tmp` tmpfs. They verify POSIX
mode `0777` only on fresh output directories, unchanged source permissions, and
refusal to chmod pre-existing output directories. Production digest environment
handling is unchanged.

The local Python test commands used
`PYTHONPATH=C:/Users/Nurgissa/Desktop/Uruk/analyzers/shared/.deps` for the pinned,
workspace-local wheels. Dependencies were installed with pip `--target` and npm
`--ignore-scripts`; project source was never built or executed by an analyzer.

Fixtures test actual AST extraction, misleading source comments/strings,
malformed inputs, aliased decorators, resolved local calls, database defaults,
generated columns, keys/checks/indexes, policies, triggers, static trigger-body
writes, queries/CTEs, and partial coverage. Boundary tests cover component path
escapes, symlinks/junctions, excluded directories, ignore patterns, binary/oversize
source, request schemas, output budgets and output symlinks. Evidence tests cover
complete source lines, CRLF, Unicode/BOM and code-point columns.

The PostgreSQL extension tests additionally cover XML/YAML/JSON declarations,
ordered includes/includeAll/sqlFile, disconnected cycles, traversal/symlink
rejection, dbms exclusions, ignored rollback changes, malformed documents,
duplicate keys, YAML aliases, XML DTDs and foreign namespaces. Native PL/pgSQL
tests cover assignments, initializers, branches, loops, static SQL, dynamic SQL
unknowns, unsupported utility statements and native body parse failures. Evidence
checks cover escaped JSON, XML entities/CDATAs/self-closing tags, multiline
headers/declarations, same-line procedural statements and nested SELECT joins.

## Executed OCI tests

All images built successfully with repository-root contexts:

```sh
docker build -f analyzers/tree-sitter/Dockerfile -t semanticmap/tree-sitter:0.1.0 .
docker build -f analyzers/postgresql/Dockerfile -t semanticmap/postgresql:0.1.0 .
docker build -f analyzers/typescript-node/Dockerfile -t semanticmap/typescript-node:0.1.0 .
```

Each passed `python analyzers/shared/container_smoke.py <analyzer>` twice:

| Image | Facts | Diagnostics | Correct reported status |
| --- | ---: | ---: | --- |
| `semanticmap/tree-sitter:0.1.0` | 66 | 0 | SUCCEEDED |
| `semanticmap/postgresql:0.1.0` | 377 | 2 | PARTIAL |
| `semanticmap/typescript-node:0.1.0` | 48 | 3 | PARTIAL |

The smoke helper verified all five output filenames, canonical v1 schemas,
full-source-line hashes, deterministic fact output, source immutability, and image
identity. Actual containers used UID/GID 65532, no network, a read-only root and
workspace, all Linux capabilities dropped, no-new-privileges, and CPU/PID/memory
limits. PostgreSQL's partial result records dynamic SQL and omitted rollback;
TypeScript records external types, unresolved calls and unknown guard behavior.
These are expected coverage gaps, not failed tests.

Immutable local image configuration IDs at verification time:

```text
tree-sitter     sha256:525fc48de9806d0836bf76703be7ea87d1eb63d7d18622fc42fb53b23c72c66a
postgresql      sha256:a9ee6f72f85454001b9d14d1bb9c77844bca424984931b6965083fd4a651e724
typescript-node sha256:f85c62e43baefdcb98417d25925d3696fd09497bbca276908d5bf900340d6a59
```

These are Docker local image IDs, not claimed registry manifest digests. No image
was pushed to a registry. Rebuilding after documentation/source changes can
produce different IDs; deployment must resolve the current tag or registry digest.

## Integration handoff

- Images expose `semantic-analyzer describe` and
  `semantic-analyzer analyze --request /input/request.json --output /output`.
- Required mounts are `/workspace` read-only, `/input` read-only and `/output`
  writable. Local mode supports `SEMANTIC_WORKSPACE`.
- `manifest.json` uses the canonical v1 envelope and `analyzer.id`,
  `analyzer.version`, and nullable `analyzer.imageDigest`.
- `coverage.json.capabilities` is an array of `{name,status,limitations}` objects.
  Unknowns, status and additional walk counters are inside `metadata`.
- `statistics.json` has counts, `factsByKind`, and extensible `metadata`.
- All schemas are loaded from `analyzer-contract/schemas/v1/`; no root schemas or
  global fixtures were modified by this work.
- SQL physical identities align with Java for explicit schemas:
  `db:table:public.orders`, `db:column:public.orders.created_at`. SQL ownership
  facts contain `sourceLayer: DATABASE` and `ownership`; all edges are `RELATION`
  with `sourceKey`, `targetKey`, and `edgeKind` in properties.
- The analyzers report unsupported capabilities and unresolved source behavior.
  Consumers must retain `PARTIAL` and exit code 10, not convert them to success.

Remaining limits: no complete Liquibase runtime interpretation (properties,
contexts/preconditions, rollback, custom changes/delimiters/encodings), migration
catalog replay, dynamic SQL target or function overload resolution, complete
procedural control flow (including CASE/cursors), full dependency or runtime
dispatch resolution, global NestJS route/guard configuration, or incremental
analysis. Unsupported behavior remains explicit in diagnostics/coverage. The
individual READMEs describe the implemented subsets and each boundary.

## 2026-09-03 Alpine delivery addendum

The Bookworm image IDs and base-refresh results above are historical. This
addendum supersedes their delivery status without rewriting the earlier record.

Tree-sitter and PostgreSQL were rebuilt on the official, digest-pinned base:

```text
python:3.14.7-alpine3.24@sha256:c6ead215bfd31f1e433d968853b7a769989117115b728874824e6c0a27cb96fc
```

The builds ran `apk upgrade --no-cache`; Python dependencies were installed only
from wheels, with no compiler toolchain added. Compatibility/security changes
were `rpds-py=0.27.1`, `PyYAML=6.0.3`, and `pglast=7.11`. The pglast 7.11 wheel
moved setuptools out of its runtime requirements, allowing removal of the
vulnerable vendored build tooling. `pip check` passed. Actual probes returned
Python 3.14.7 and Expat 2.8.2.

Frozen Python analyzer delivery image IDs:

```text
tree-sitter sha256:6c5310613ba9303dd5c34595ffe831a6867a04f75e2c262173ef6fe484d1d53d
postgresql  sha256:f83416dcfdb956b483874fce547baf721520f16bb11c430324a4c5bab084ae6d
```

Their final Linux parser suites passed (4 and 20 tests respectively), as did the
two POSIX output-permission tests. Both OCI smoke runs validated the canonical
schemas, immutable source files, evidence hashes, non-root/read-only execution,
and deterministic facts. The complete 66/377 fact outputs were byte-identical
to the Bookworm baselines. No Python analyzer was changed during the subsequent
Node-only remediation below.

Grype 0.118.0, using database built `2026-09-02T06:35:12Z`, returned exit 0 for
both images with `--fail-on high`: zero HIGH/CRITICAL, eight MEDIUM and one LOW
each, no ignored matches. Complete verified reports were copied to
`output/scans/tree-sitter-delivery.json` and
`output/scans/postgresql-delivery.json`; their source image IDs matched the
promoted `0.1.0` tags. No CVE suppressions were added or scanner thresholds lowered.

### Rejected static-OpenSSL Node delivery

The initial Alpine Node delivery was:

```text
typescript-node sha256:db54654018cdba4424f40b9a0e58840947c355b5b0313dd3477655fbdc478a09
```

Its eight parser tests and OCI smoke passed, all 48 facts matched the Bookworm
baseline byte-for-byte, and Grype reported zero HIGH/CRITICAL with four MEDIUM.
Nevertheless, the runtime probe showed embedded OpenSSL 3.5.7 in the copied
official Node 22.23.2 binary. Updating Alpine's separate libraries could not fix
that binary. This image is therefore not accepted as the final OpenSSL
remediation; its passing scan alone is insufficient evidence. The old report
and image remain historical evidence until a verified replacement is promoted.

### Shared-OpenSSL Node candidate: verification pending

Official Alpine 3.24 x86_64 APKINDEX metadata was inspected on 2026-09-03:

| Package | Pinned version | Role |
| --- | --- | --- |
| `nodejs` | `24.18.1-r0` | Node 24 LTS runtime |
| `libssl3` | `3.5.8-r0` | Shared TLS library |
| `libcrypto3` | `3.5.8-r0` | Shared cryptographic library |
| `npm` | `11.12.1-r0` | Dependency stage only |

The runtime and dependency stages use:

```text
alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b
```

Alpine's [Node APKBUILD](https://raw.githubusercontent.com/alpinelinux/aports/3.24-stable/main/nodejs/APKBUILD)
removes `deps/openssl` and uses `--shared-openssl`. The package index declares
dependencies on `so:libssl.so.3` and `so:libcrypto.so.3`. The
[OpenSSL APKBUILD](https://raw.githubusercontent.com/alpinelinux/aports/3.24-stable/main/openssl/APKBUILD)
records the August security fixes in `3.5.8-r0`. Node's
[version probe implementation](https://raw.githubusercontent.com/nodejs/node/v24.18.1/src/node_metadata.cc)
calls `OpenSSL_version`, so `process.versions.openssl` reflects the loaded library.

The candidate Dockerfile checks that runtime version, shared-linking flag and
loaded shared objects. It installs no compiler and never copies an upstream
static Node binary. The Node license is pinned by SHA-256. Only `runtime.mjs`
is copied from the shared directory into this image; verification documentation
is not part of the TypeScript runtime payload.

At the time of this entry, builds, scans and OCI runs are intentionally deferred
until the parent explicitly ends the frontend quiet-performance window. The
Dockerfile and README are prepared, but no Node 24 candidate has been executed
or promoted. Required remaining gates: actual linker/version probes, eight
parser tests, pre-promotion OCI smoke and byte-identical facts, and a complete
Grype HIGH-gate scan with matching nonempty source image ID. Final results will
be appended below; Node 22 CI compatibility tests do not replace these gates.

### Shared-OpenSSL Node final delivery: all gates passed

After the parent reopened the Docker window on 2026-09-03, the candidate was
built, verified and promoted to `semanticmap/typescript-node:0.1.0`:

```text
sha256:2a716fcb99a13706b500d6c13dfcf0a94c53669d52c5b38dbb0777a444691a0e
```

This is the local immutable image configuration ID, not a registry manifest
digest. No registry push was performed. The earlier verification-pending entry
and rejected static-OpenSSL image above remain historical records.

The independent probe ran as UID/GID 65532 with no network, a read-only root,
dropped capabilities and no-new-privileges. It verified these actual values:

| Probe | Result |
| --- | --- |
| Node executable/version | `/usr/bin/node`, `24.18.1` |
| Loaded OpenSSL version | `3.5.8` |
| `node_shared_openssl` | `true` |
| Loaded shared objects | `/usr/lib/libssl.so.3`, `/usr/lib/libcrypto.so.3` |
| Offline APK inventory | `nodejs-24.18.1-r0`, `libssl3-3.5.8-r0`, `libcrypto3-3.5.8-r0` |
| `ldd /usr/bin/node` | Resolves both shared OpenSSL libraries |
| Runtime tooling | APK remains; npm, npx, gcc, g++ and make are absent |
| Node license SHA-256 | `148eacf7863ef4329224a29398623077200a27194aa075569faf4a0a85566ca5` |

An initial non-root probe caught inaccessible parent directories created by the
checksummed license ADD. Only the image's license directories were changed to
0755; the license remains 0444. The rebuilt image passed the full probe. No host
source permissions were changed. The rejected Node 22 image was also tested as
a negative control: it returned OpenSSL 3.5.7, shared linking false and no loaded
OpenSSL shared objects, and correctly failed the same OpenSSL assertion.

All eight `node --test tests/parser.test.mjs` tests passed in the final image
(31.747 seconds, zero failures or skips). Tests retained the canonical schema,
POST `/orders`, `Min(500)`, parser-error, bounded-walk, symlink-rejection and
Unicode evidence checks. Parser dependencies and fixtures were not changed.

The existing OCI smoke helper ran before promotion through an in-memory adapter
that substituted only the initial image-inspect tag with
`semanticmap/typescript-node:0.1.0-node24-candidate`. Every container then ran the
resolved immutable ID; the helper's parsing and assertions were unchanged.
Both analysis runs passed all five output checks, canonical v1 schemas, digest
identity, original evidence hashes, source immutability and deterministic facts.
Results were 48 facts, three expected diagnostics and status PARTIAL. Outputs:
`analyzers/shared/.test-output/container-typescript-node-8ny16a0w/`.

Both complete `facts.ndjson` files were byte-identical to the preserved Bookworm
and earlier Alpine baselines, with SHA-256:

```text
ce7fbda72d3d64be03d0e167693db2b1601fbc4e7d3a50b609c6e5c83958fd30
```

Grype 0.118.0 completed with exit 0 and `--fail-on high`, using the valid cached
database built `2026-09-02T06:35:12Z`: zero HIGH/CRITICAL, six MEDIUM, no ignored
matches. No CVE suppressions or threshold changes were introduced. The nonempty
report source image ID matched the verified candidate and promoted tag. The
complete report was copied byte-for-byte to
`output/scans/typescript-node-delivery.json`, SHA-256:

```text
abac16a982ad0f9da4baec8e7132911e51bd474b0310a90103063d28ec2069f1
```

The original is preserved at
`analyzers/shared/.test-output/scans/typescript-node-node24-shared-openssl.json`.
Six MEDIUM findings remain in that report; scanner success is not a claim that
the image has no vulnerabilities. The runtime/linker proof above independently
establishes removal of the known embedded OpenSSL 3.5.7 boundary.

The dedicated BuildKit builder, parser suite, probes and scanner were limited to
two CPUs and 2 GiB memory, with no additional swap allowance. The unchanged OCI
helper used stricter one-CPU/512 MiB limits. The scanner had no network and used
the cache read-only. All sessions completed and the dedicated builder
`uruk-ts-node24-20260903` was removed. PostgreSQL, Tree-sitter, their delivery
reports, Maven and parent platform/frontend services were not changed.
