# Security Boundaries

Source repositories and analyzer output are untrusted. SAFE_STATIC analyzers
have no network, cannot execute builds and receive only a read-only snapshot.
Symlinks, path traversal, oversized files/output and invalid source ranges are
rejected. Invalid facts remain quarantined with explicit diagnostics.

Development authentication is only for loopback/local Docker usage. Never expose
the `dev` profile publicly. Production uses a trusted OIDC issuer and audience,
signed tenant/user claims, role checks and explicit project memberships.
Project deletion archives access while preserving immutable audit history.

Docker socket access gives the worker broad host control. Local Compose is not a
multi-tenant production sandbox. Isolate worker hosts or use a validated remote
job runner, restrict image digests and deny egress at the infrastructure layer.

AI is disabled by default. Remote transfer needs an organization policy and an
operator-configured provider. Source/comment text cannot become instructions;
the model receives a bounded, redacted evidence bundle. Enrichment cannot modify
facts or their confidence. Human review is a separate trust signal.

Do not place real credentials in fixtures, environment examples, Git remotes,
logs, source snapshots or database fields. Configure secret references through
the operator environment. Source snapshots expire according to organization
retention; retained evidence hashes do not imply that expired source is available.

Report reproducible issues privately to the repository owner; no public security
contact or license has been invented for this project.
