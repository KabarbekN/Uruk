# Analyzer changes

## Unreleased - 2026-09-05

- Java source discovery now prunes excluded directories before traversal, applies
  bounded workspace ignore patterns, and enforces entry/depth/read-byte limits
  before creating the parser model. Invalid UTF-8 and binary files retain per-file
  diagnostics without discarding valid source files.
- Python and Node relation facts preserve repeated evidence instead of silently
  dropping every location after the first. Java, Python and Node keep at most
  32 relation evidence locations with explicit partial coverage, matching the
  platform's ingestion limit. Added evidence consumes the output byte budget.
- Python and Node reject unsupported build/dependency execution requests instead
  of accepting them while silently performing a different SAFE_STATIC policy.
- Python canonicalizes the result directory after link checks, preventing `..`
  from disguising a path that would write output inside the source workspace.
- Added parser and transport regressions for repeated calls, evidence limits,
  execution policy, directory pruning, ignore patterns and malformed source.

## 0.1.0 - 2026-09-02

- Implemented runnable Tree-sitter, PostgreSQL and native TypeScript/NestJS CLIs.
- Added repository-root OCI Dockerfiles, non-root users, pinned parser libraries,
  locked Node dependencies, and canonical v1 schema loading.
- Added bounded read-only source access, gitignore-style exclusions, symlink
  rejection, output limits, deterministic facts and full-line evidence hashes.
- Added database ownership and ORM-compatible table/column stable keys.
- Added honest partial coverage for malformed syntax, unresolved calls, procedural
  SQL, unsupported changelog interpretation and dynamic framework behavior.
- Added parser fixtures, contract/security tests and offline OCI smoke verification.
- Extended PostgreSQL with original-source Liquibase XML/YAML/JSON parsing,
  bounded includes, supported declarative changes and honest unsupported-change
  diagnostics; added native PL/pgSQL branches/loops/assignments and query facts.
- Added original-range tests for entities, escaped JSON, CDATA, multiline bodies,
  same-line procedural statements, nested joins and cyclic/unsafe includes.
- Fixed Linux smoke output mount permissions only for fresh ephemeral outputs;
  source permissions and production digest variables remain unchanged.
- Refreshed official Python 3.12 and Node 22 LTS Bookworm base images to current
  patch releases, pinned their image-index digests, and applied OS package
  upgrades during builds without changing parser dependency versions.
