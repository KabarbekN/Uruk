# Architecture Decisions

## Context

The supplied specification describes an evidence-first modular monolith with
separately executed language analyzers. The user selected Maven and delegated
routine implementation decisions.

## Decisions

1. Maven reactor: analyzer-contract (pure protocol), analyzers/java-spring (CLI),
   backend/platform (Spring Boot with API and worker profiles). Spring Modulith
   verifies feature packages; deployment boundaries do not imply one jar per feature.
2. PostgreSQL stores typed tenant-scoped records, JSONB extension properties,
   graph edges, append-only evidence and revision results. JPA owns project CRUD;
   JDBC owns queue and graph queries. Flyway owns schema changes.
3. Analysis has immutable snapshots and a persisted dependency DAG. Tasks use
   leases, owner-fenced completion, heartbeats, bounded retries and cancellation.
4. Production analyzer execution requires immutable Docker image digests.
   Local development images are resolved to their immutable image IDs before use.
   Source is mounted read-only with no network or build execution.
5. Stable keys identify symbols and structural rule locations. Threshold values
   are excluded from identity so changes can be compared. Ambiguous reorderings
   are reported, never silently treated as exact matches.
6. Minimal verified source snippets are retained with evidence. Raw workspaces
   have bounded retention. Expired source retrieval is explicit, not fabricated.
7. AI is disabled by default. Remote transfer requires organization permission;
   enrichment never modifies canonical facts or their confidence.
8. Design consistency is enforced by shared CSS tokens and reusable React
   components: neutral surfaces, teal interaction, semantic green/amber/red,
   compact tables and graph workspace. Figma can mirror this component system.
9. Dev authentication is loopback-only and explicit. Production uses JWT/OIDC
   with trusted tenant claims and role checks. Client-supplied tenant headers
   cannot select arbitrary organizations.

## Alternatives and Consequences

Separate microservices, graph databases, and one build module per feature add
operational cost before scale measurements justify them. Full component analysis
is initially preferred to unsound incremental results. Static Java analysis
without dependencies reports unresolved types; it never executes project builds.

## Primary References

- https://docs.spring.io/spring-boot/3.5/system-requirements.html
- https://docs.spring.io/spring-modulith/reference/
- https://docs.openrewrite.org/authoring-recipes/recipe-development-environment
