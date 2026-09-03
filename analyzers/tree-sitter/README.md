# Tree-sitter analyzer 0.1.0

Real Python Tree-sitter bindings parse Java, TypeScript, TSX, MTS and CTS source.
The registry loads only the grammar required by each discovered extension and
caches parsers in-process. Pinned grammar wheels are installed at image build time.

Extracted facts include files, packages/modules, classes/interfaces/records,
methods/functions, parameters, imports, annotations/decorators, conditions, loops,
assignments, literals and calls, with source ranges. All facts use
`STATIC_SYNTAX` and confidence `0.8`. Generic calls explicitly carry
`targetResolution: UNRESOLVED`; they never become resolved `CALLS` edges.

Local commands from the repository root:

```sh
python -m pip install -r analyzers/tree-sitter/requirements.txt
python analyzers/tree-sitter/semantic-analyzer describe
python analyzers/tree-sitter/semantic-analyzer analyze --request /input/request.json --output /output
python -m unittest discover -s analyzers/tree-sitter/tests -v
```

Set `SEMANTIC_WORKSPACE` to a local source root when `/workspace` is not mounted.
The Docker build context, output contract, bounds, schema paths and OCI tests are
documented in `../shared/README.md`.

Only Java and TypeScript grammar families are included. Type resolution, Java
framework semantics, comment interpretation, cross-language resolution and runtime
dispatch are outside this analyzer. Malformed syntax produces partial coverage
and error diagnostics while retaining recoverable syntax facts. Structural ordinal
keys survive threshold and whitespace changes, but declaration reordering and
renaming can change identities. No incremental analysis is claimed.

Parser API reference: https://github.com/tree-sitter/py-tree-sitter
