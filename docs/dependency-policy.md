# Dependency Policy

Maven dependency management and the frontend lockfile pin reproducible builds.
Node lifecycle scripts are not enabled for analyzed repositories. Analyzer
containers never install dependencies from their input source.

CI runs the native parser tests, Maven verification, frontend checks, real OCI
acceptance and an image/library vulnerability gate. Grype findings at HIGH or
CRITICAL block release; no blanket ignore file is provided. Operator review is
required for any future risk exception. A workflow being present does not mean
its remote run has passed.

Commons Lang is explicitly updated from the inherited 3.17.0 to 3.20.0. Apache
documents the long-input ClassUtils denial-of-service issue and its correction
in the [release notes](https://commons.apache.org/proper/commons-lang/changes.html).
The image-scanning configuration follows the
[Anchore action interface](https://github.com/anchore/scan-action).

## Runtime Verification

Scan the exact delivery image, not only its base tag or Maven/npm manifest.
Keep the image configuration ID, advisory database build date and unfiltered
JSON report with the verification record. HIGH/CRITICAL is a release gate;
passing it does not mean no lower-severity findings or complete binary coverage.

Inspect statically bundled runtimes separately. During this implementation,
package scanning alone did not account for Node's embedded OpenSSL. Node images
must also verify the actual `process.versions.openssl`, shared-library build flag
and loaded library objects. Removing npm from a runtime is valid attack-surface
reduction, but removing package metadata to hide findings is not.

The platform rebuilds Docker CLI 29.7.2 from its checksummed upstream source and
vendored dependencies with Go 1.26.8; its upstream binary embedded older Go.
The build has network-disabled compilation and retains compiler/module build
information and binary SHA-256. Daemon compatibility and the resulting platform
image are checked independently. No ignore rule substitutes for a runtime fix.

Official base references are digest-pinned. Alpine security refreshes install
patched packages, so updates must rebuild with a refreshed package index and
rescan rather than assuming the original digest remains sufficient. Preserve
the resulting SBOM/report and pin reviewed registry digests when publishing.

Before production, pin all CI actions to reviewed full commit SHAs, image
references to registry digests, and configure dependency update review. Local
Docker image configuration IDs are not registry manifest digests. No local
build or unit test is a substitute for an up-to-date advisory scan.
