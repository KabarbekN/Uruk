# PostgreSQL analyzer 0.1.0

Pinned pglast parses SQL into PostgreSQL AST nodes. The analyzer does not connect
to a database, split SQL with regular expressions, or execute migrations.

It extracts schemas, tables, columns and types; defaults, identity and generated
values; primary/foreign/unique/check/not-null constraints; indexes and predicates;
triggers and their event/timing/function bindings; RLS enablement and policies;
views/materialized views; functions/procedures; and SELECT/INSERT/UPDATE/DELETE/
MERGE operations, filters, joins, grouping, sorting and expression transformations.
CTE aliases are kept distinct from physical tables.

Database behavior has `DATABASE_DERIVED` origin and confidence `0.98`. Ownership
properties distinguish `DATABASE_DEFAULT`, `DATABASE_GENERATED`,
`DATABASE_CONSTRAINT`, `DATABASE_TRIGGER`, and `DATABASE_POLICY`. `OWNED_BY`,
`READS`, `WRITES`, `TRIGGERS`, `CALLS`, `AUTHORIZED_BY` and structural relations
link these facts. Table/column keys match the Java analyzer for explicit schemas.
Policy presence is not a claim that RLS is active for every runtime role.

Local commands from the repository root:

```sh
python -m pip install -r analyzers/postgresql/requirements.txt
python analyzers/postgresql/semantic-analyzer describe
python analyzers/postgresql/semantic-analyzer analyze --request /input/request.json --output /output
python -m unittest discover -s analyzers/postgresql/tests -v
```

Set `SEMANTIC_WORKSPACE` to the local source root. See `../shared/README.md` for
the exact OCI/output contract and canonical schema locations.

## Liquibase subset

XML uses Expat, and YAML/JSON use bounded PyYAML node trees with original source
marks (JSON is also validated as JSON). Supported declarations include changeSets,
createTable/addColumn, defaults and identity, primary/unique/check/foreign/not-null
constraints, indexes, views, inline SQL and inline createProcedure bodies.
Declarative changes are `FRAMEWORK_DERIVED` at confidence `0.95`, marked `DECLARED`
and `LIQUIBASE_XML`, `LIQUIBASE_YAML` or `LIQUIBASE_JSON`. No synthetic SQL is
generated for these declarations. Actual SQL payloads go through pglast.

Literal `include.file`, `includeAll.path` and `sqlFile.path` references are resolved
only against the already admitted source inventory, respecting the component,
ignore rules and symlink rejection. `relativeToChangelogFile` defaults to false;
the workspace root is the only search root. Includes follow declaration order;
includeAll is lexical, with bounded depth and literal suffix filtering. Includes
are capped at depth 32 and 2,048 visited files; cycles are diagnosed. Each physical
file is analyzed once, not replayed in every changeset or database context.

PostgreSQL dbms selection is honored. Properties, contexts, labels, preconditions,
modifySql, rollbacks, custom changes, custom delimiters/encodings/comparators and
unsupported change types remain explicitly partial. Rollback-only SQL files and
dbms-excluded includes are not silently interpreted as forward changes. YAML
aliases, duplicate keys, arbitrary tags and XML DTD/entities are rejected. XML
and YAML parse depth is capped at 64 and node count at 100,000.

Every SQL fact retains the original changelog or referenced SQL file evidence.
Contiguous XML/CDATAs and dollar-quoted bodies permit exact token spans. Decoded
entities, escaped strings and YAML/JSON scalar folding use the exact enclosing
original element/literal span, never reconstructed SQL line numbers. All hashes
are computed from full original source lines. Native SQL handed off by another
analyzer must currently be staged as SQL source files; arbitrary external
evidence references are not accepted.

## Procedural and query subset

SQL-language bodies use the SQL parser. PL/pgSQL functions/procedures use
`pglast.parse_plpgsql` on the original definition. Native statement line numbers
and scanner tokens locate declarations/initializers, assignments, IF/ELSIF/ELSE,
basic loops, static executable SQL, PERFORM, RETURN/RETURN QUERY, RAISE, EXIT and
CONTINUE. Facts include structural branches, guards, lexical statement order,
returns, transformations and static database reads/writes. Exception handlers
lack native source positions, so their evidence is the enclosing original body.

Dynamic EXECUTE and dynamic query loops/returns are unknown: no table targets are
invented. CASE, cursors, diagnostics statements and other unhandled native nodes
receive explicit diagnostics, without guessing their nested effects. This is a
structural subset, not an interprocedural control-flow or runtime execution model.
Function overloads, runtime parameters and transitive trigger effects are not
resolved. Malformed native procedural bodies are errors and count as failed files.

SELECT filters, joins, grouping, sorting, projected expressions/aliases and native
aggregate syntax produce deterministic facts. DISTINCT, star, FILTER and ordered
aggregate forms are recognized without assuming a catalog; ambiguous plain
function calls remain transformations with unresolved targets. Nested expressions
use a valid enclosing statement span when no precise native range is available.

Facts describe migration statements, not a replayed final catalog. Unsupported
statements are retained as parsed SQL with diagnostics. Unqualified schema names
are not silently resolved using an assumed search path. A syntax error rejects
the affected SQL payload, while analysis continues on other files/changeSets.

References: [pglast parser](https://pglast.readthedocs.io/en/v7/parser.html),
[Liquibase sqlFile](https://docs.liquibase.com/change-types/sql-file.html).
