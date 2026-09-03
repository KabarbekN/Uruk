# Contributing

Use Java 21 and Maven Wrapper. Keep feature ownership boundaries explicit and
run `./mvnw verify` before proposing changes. Frontend API types are generated
from `docs/api-contract.yaml`; update that contract when endpoints change.

Analyzer changes need source fixtures, deterministic golden expectations,
schema validation and exact evidence-range tests. New facts must not claim
resolution beyond the parser's evidence. Breaking contract changes require a
new major schema version and an ADR.

Keep source facts immutable. Add review decisions and enrichment as separate
records. All application lookups must enforce both tenant and project access.
Never run repository build scripts in SAFE_STATIC analysis.
