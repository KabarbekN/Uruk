# Java/Spring Static Analyzer

Java 21 CLI using the OpenRewrite 8.87.7 lossless source model. The analyzer reads
source snapshots; it never invokes the target repository's build, resolves its
dependencies, evaluates SpEL, runs annotation processors from that repository,
executes application code, or connects to its database.

## Build And Run

From the repository root, with JDK 21 and Maven:

~~~powershell
mvn -pl analyzers/java-spring -am verify
java -jar analyzers/java-spring/target/analyzer-java-spring.jar describe
$env:SEMANTIC_WORKSPACE = (Resolve-Path fixtures/spring-order-service).Path
java -jar analyzers/java-spring/target/analyzer-java-spring.jar analyze --request fixtures/spring-order-service/request.json --output analyzers/java-spring/target/golden
~~~

Quote Maven system properties in PowerShell, for example '-DskipTests'.
The default workspace is /workspace. SEMANTIC_WORKSPACE enables local analysis.
The request component root is relative to the workspace; evidence paths remain
relative to the workspace, including the component prefix. Output must be outside
the source workspace.

The generated executable is target/analyzer-java-spring.jar. The result directory
contains manifest.json, facts.ndjson, diagnostics.ndjson, coverage.json, and
statistics.json. Exit 10 means partial coverage with useful facts, not a failed
invocation. The golden fixtures intentionally return 10 because external
dependencies and runtime behavior remain unresolved.

Exit codes: 0 succeeded, 10 partial, 20 invalid request/CLI, 30 unsupported
component, 40 analysis failure, 50 policy limit or forbidden path/policy,
60 internal failure. Extremely small output budgets can prevent writing even a
failure envelope; stderr and the nonzero exit code remain authoritative.

## Extraction

Separate internal extractors share one source/symbol index and fact model:

- SymbolExtractor and TypeHierarchyExtractor: packages, types, fields, methods,
  source inheritance, and declared Spring Data repository entity types.
- SpringEndpointExtractor: class plus method mappings, HTTP methods,
  consumes/produces, body/parameter/response types, status and annotation evidence.
  Feign declarations are not inbound MVC endpoints.
- ValidationExtractor and AuthorizationExtractor: Jakarta constraints, custom
  validator declarations/links, roles, and a parsed SpEL expression tree.
- JpaMappingExtractor and RepositoryQueryExtractor: ORM entities/columns,
  relationships, IDs, generation, enum settings, column definitions, callbacks,
  query declarations and ORM reads/writes. No invented executed SQL.
- TransactionExtractor and ScheduledJobExtractor: declared annotation boundaries.
- ConstantResolver, NormalizedConditionExtractor and ExceptionFlowExtractor:
  source constants, expression trees, if/ternary/switch branches and local effects.
- MethodCallExtractor, ExternalCallExtractor and TestLinkExtractor: unambiguous
  source calls, declared Feign/RestTemplate/WebClient/event effects, JUnit source
  and direct production call links.

FrameworkExtractors.java groups the annotation extractors. MethodCallExtractor.java
groups source calls, external calls, and test links. NormalizedConditionExtractor.java
contains the shared expression IR and branch-effect extractor. These classes
all emit through AnalysisModel and do not maintain competing syntax models.

All Java nodes carry properties.name and properties.ownerKey. RELATION facts
carry sourceKey, targetKey, edgeKind, and evidence. The shared v1 schema accepts
extensible properties from other analyzers without requiring Java hierarchy
fields. Supported analyzer origins are STATIC_EXACT, STATIC_TYPED, STATIC_SYNTAX,
FRAMEWORK_DERIVED, DATABASE_DERIVED, RUNTIME_OBSERVED, and HEURISTIC.

## Evidence And Identity

The lossless OpenRewrite printer records node offsets against the exact input
snapshot. A source that fails round-trip comparison is diagnosed and excluded.
Positions are one-based; endColumn is exclusive. Hashes are sha256: followed by
lowercase hexadecimal SHA-256 of the complete start-through-end source lines,
joined with LF and no trailing newline. CRLF input is normalized only for this
line-slice hash. Repeated source text is located by tree position, not substring
search.

Method keys use declaring type, method name and parameter types. Condition keys
use method identity, control kind, and preorder slot within that method. Literal
values, line numbers, parser UUIDs and threshold constants never enter condition
keys. Inserting or reordering earlier conditions can shift slots; cross-method
moves/renames require platform matching. Fact IDs are deterministic SHA-256 of
stable keys. Facts are sorted by key and repeated relation evidence is aggregated.

## Coverage Limits

describe and coverage.json list the capability limits. Requested capabilities
may include supporting symbol and ORM mapping facts. changedFiles is accepted,
but this version always analyzes the complete component snapshot.

No repository classpath is downloaded. Reflective, ambiguous, inherited external
and runtime-dispatched calls produce UNRESOLVED_METHOD_TARGET diagnostics with no
fabricated CALLS target. Record accessors without source declarations can remain
unresolved. Dynamic routes, custom composed MVC annotations, request-matcher
security, complete custom validation semantics, naming strategies, JPA property
access/embedded mappings, full derived-query binding and interprocedural control
flow are partial. SpEL bean calls are syntax, not authorization proofs.

Switch branches record labels and possible fall-through; the analyzer does not
claim complete switch path evaluation. Nested conditions retain their guards.
Test links record static call evidence, not executed tests or complete rule
coverage. Mock/endpoint/exception assertion linking is not implemented.

JPA columnDefinition and ColumnDefault are declarations. Applied PostgreSQL
defaults, constraints, functions and triggers are owned by the PostgreSQL analyzer.
The fixtures include Flyway SQL and configuration for that integration.

Input limits are 2 MiB per Java file, 64 MiB total, and 5000 files, in addition to
the request timeout/output budget. Discovery also limits all visited entries to
100,000 and directory depth to 64, before allocating the parser's source model.
Excluded directories are pruned immediately. Test and generated source inclusion
follow policy flags. The workspace-root `.semanticmapignore` supports anchored
paths, directory patterns, `*`, `**`, `?`, escaped literals and ordered `!`
negation; ignored parent directories are not traversed. Ignore input is limited
to 64 KiB and 1,024 patterns of at most 1,024 characters each. Character classes
in ignore patterns are currently treated as literal characters.
Traversal and source links are rejected, including component links and special
entries. Reads are bounded even if a file grows after its size check. Malformed
UTF-8 and binary files retain diagnostics while valid files can still be parsed.
Source is never modified.

Repeated relation facts aggregate distinct evidence locations. At 32 locations,
the relation remains within platform ingestion limits and further locations
produce `RELATION_EVIDENCE_LIMIT` with partial coverage. The analyzer never
reports exhaustive evidence after this bound is reached.

## Fixtures And Verification

- fixtures/spring-order-service: minimum amount 500.
- fixtures/spring-order-service-revision2: minimum amount 300.

Both contain a POST /api/orders endpoint, record validation, PREMIUM/ownership
authorization, minimum-amount rejection, discount, repository save/read, payment
client, OrderCreated event, JUnit source, PostgreSQL defaults and an audit trigger.
Only the amount threshold and corresponding JUnit boundary/expected values change.

The Maven tests exercise both fixtures, rule identity and resolved constants,
endpoint details, SpEL roles, data effects, test links, every emitted evidence
hash/range, all output schemas, deterministic output, partial coverage, all
cross-analyzer origin values, strict envelope fields, malformed requests and Java,
CRLF/repeated conditions, unresolved calls, policy limits, and test exclusion.

The fixture's own application tests are analysis input and are not run by this CLI.

Verified on 2026-09-02 with the supplied OpenJDK 21.0.1 and Maven 3.9.14:

- mvn -pl analyzers/java-spring -am verify: 15 analyzer tests and 3 contract tests
  passed, with zero failures or skipped tests.
- The configured Spotless formatter applied and checked all 13 analyzer Java
  source/test files. Formatting was followed by a successful package rebuild.
- The shaded JAR describe command exited 0. Both fixture analyze commands exited
  10 with 135 facts and 15 explicit unresolved-call diagnostics each.
- The minimum-amount rule retained its fact ID, changed its resolved threshold
  from 500 to 300, and included both the guard and the changed constant declaration
  as evidence. The Mockito setup call did not become a database write.
- The Docker image built and analyzed the baseline as UID 65532 with network
  disabled, read-only root/source mounts, a temporary filesystem, and a writable
  output mount. It exited 10 and emitted byte-identical facts to the Windows CLI.
- Local integration outputs remain in target/cli-golden, target/cli-revision2,
  and target/container-golden. The local image is semanticmap/java-spring:0.1.0;
  it has not been pushed to a registry.

## Container

~~~sh
docker build -t semanticmap/java-spring:0.1.0 -f analyzers/java-spring/Dockerfile .
docker run --rm --network none --read-only --tmpfs /tmp:rw,noexec,nosuid,size=256m \
  --memory 2g --cpus 2 \
  -v "$SOURCE:/workspace:ro" -v "$INPUT:/input:ro" -v "$OUTPUT:/output" \
  semanticmap/java-spring:0.1.0 analyze --request /input/request.json --output /output
~~~

Run the build from the repository root. A pinned Maven/JDK build stage compiles
the analyzer and shared contract from source, so a clean checkout needs no
prebuilt local JAR. The runtime pins eclipse-temurin:21.0.12_8-jdk-jammy and runs
as UID/GID 65532.
OpenRewrite requires the JDK compiler modules, so a JRE-only image is insufficient.
The output mount must be writable by that UID. Publish the built analyzer image
and register its immutable registry digest. A runner may pass the actual digest
as SEMANTIC_ANALYZER_IMAGE_DIGEST; local runs report null instead of inventing one.

## File Inventory

Authored files are confined to the assigned analyzer, schemas, contract tests and
fixtures. Generated Maven outputs live under their module target directories.

- analyzers/java-spring/pom.xml, Dockerfile, README.md
- analyzers/java-spring/src/main/java/io/semanticmap/analyzer/java/:
  AnalysisEngine.java, AnalysisModel.java, Ast.java, ConstantResolver.java,
  Extractor.java, FrameworkExtractors.java, MethodCallExtractor.java,
  NormalizedConditionExtractor.java, SourceUnit.java, SpringAnalyzerCli.java,
  SymbolExtractor.java, TypeHierarchyExtractor.java
- analyzers/java-spring/src/test/java/io/semanticmap/analyzer/java/SpringAnalyzerTest.java
- analyzer-contract/schemas/v1/: coverage.schema.json, describe.schema.json,
  diagnostic.schema.json, fact.schema.json, manifest.schema.json,
  request.schema.json, statistics.schema.json
- analyzer-contract/src/test/java/io/semanticmap/contract/ProtocolTest.java
- Each fixture directory: pom.xml, README.md, request.json,
  src/main/resources/application.yml, src/main/resources/db/migration/V1__orders.sql
- Each fixture src/main/java/example/orders/: Order.java, OrderController.java,
  OrderCreated.java, OrderRejectedException.java, OrderRepository.java,
  OrderRequest.java, OrderService.java, Ownership.java, PaymentClient.java
- Each fixture src/test/java/example/orders/OrderServiceTest.java
