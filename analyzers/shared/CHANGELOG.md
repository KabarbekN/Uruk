# Analyzer changes

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
