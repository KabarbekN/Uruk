# Native TypeScript/NestJS analyzer 0.1.0

The pinned TypeScript compiler API creates a real Program and TypeChecker with an
in-memory host. The host sees only bounded, validated source files. It resolves
relative imports within that set and never reads repository config/plugins,
loads project dependencies, or executes application code.

The analyzer emits symbols, imports, conditions, generic calls and local declared
call targets; NestJS routes and guard bindings; and class-validator constraints.
Aliases and namespace imports are traced to the package supplying each decorator.
Locally defined lookalike decorators do not create framework facts.

Local commands:

```sh
cd analyzers/typescript-node
npm ci --ignore-scripts --no-audit --no-fund
node semantic-analyzer.mjs describe
node semantic-analyzer.mjs analyze --request /input/request.json --output /output
npm test
```

Set `SEMANTIC_WORKSPACE` for local execution. Docker builds must still use the
repository root as context. See `../shared/README.md` for the exact contract and
canonical JSON Schema paths.

## Container runtime

The Dockerfile uses the digest-pinned Alpine 3.24.1 base and Alpine's packaged
Node 24 LTS (`nodejs=24.18.1-r0`). `libssl3` and `libcrypto3` are pinned to
`3.5.8-r0`. Both the dependency stage and final runtime use this shared-library
Node build; no upstream statically linked Node binary is copied into the image.
The build verifies `process.versions.openssl`, `node_shared_openssl`, and the
loaded `libssl.so.3`/`libcrypto.so.3` objects, failing on an unexpected version or
static build. Alpine's package build explicitly removes bundled OpenSSL and
uses `--shared-openssl`.

Only the dependency stage installs npm (`11.12.1-r0`); the runtime retains the
locked analyzer dependencies and the checksummed upstream Node license, but no
npm or build compiler. Alpine's APK package manager remains installed. Parser
versions and the OCI protocol are unchanged.
Existing local/CI Node 22 tests remain compatibility coverage, not evidence of
the container's cryptographic library version. See `../shared/VERIFICATION.md`
for the current candidate/delivery status and actual runtime probe results.

Sources: [Alpine Node package build](https://raw.githubusercontent.com/alpinelinux/aports/3.24-stable/main/nodejs/APKBUILD),
[Alpine OpenSSL security fixes](https://raw.githubusercontent.com/alpinelinux/aports/3.24-stable/main/openssl/APKBUILD),
[Node runtime OpenSSL version probe](https://raw.githubusercontent.com/nodejs/node/v24.18.1/src/node_metadata.cc).

Syntax facts use `STATIC_SYNTAX`; compiler-resolved local declaration targets use
`STATIC_TYPED`; framework bindings use `FRAMEWORK_DERIVED`. A declared signature
is not a runtime dispatch guarantee. Missing dependencies, unresolved calls,
dynamic routes and non-literal validation arguments produce partial coverage.

Endpoint paths are explicitly `CONTROLLER_RELATIVE`, with `globalPrefix: UNKNOWN`.
Application bootstrapping, global prefixes, host/version routing, middleware,
module-wide guards, DTO transformation, custom validation behavior, runtime
authorization decisions, aliases from tsconfig, and database library semantics
are not resolved. `UseGuards` captures a guard binding with unknown behavior, not
proof of authorization. Classes and methods are parsed without NestJS installed.

The compiler host does not include ambient standard libraries. Exact local
signature resolution is useful, but full-project typecheck success is not claimed.

Compiler API reference: https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API
