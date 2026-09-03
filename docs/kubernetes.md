# Kubernetes Analyzer Execution

## Status

This is a real `kubectl` process adapter, selected only by `semantic.runner.mode=kubernetes`. It creates Jobs, reads actual Pod termination status and logs, validates analyzer output with the shared `AnalyzerOutputValidator`, and deletes its resources. It does not simulate a successful analyzer. Constructors do not contact Kubernetes. With another runner mode, none of the Kubernetes components are registered.

No cluster was configured, contacted, or deployed during this implementation session. Local verification uses deterministic fake process/API responses, generated manifests, the real output validator, and Spring conditional-context tests. Live scheduling, CSI mounts, registry pulls, admission behavior, network enforcement, and node isolation remain unverified.

The targeted Java 21 Maven run completed with 37 tests, zero failures, zero errors, and zero skips. Production and test compilation passed. This preceded the coordinated Maven freeze; the final full clean reactor verification belongs to the parent integration window. Deployment templates were not submitted to Kubernetes or validated by a live API server.

Historical database verification, 2026-09-02: V4 was restored to its originally applied Flyway checksum `-1280182665`; `estimated_cost_usd` and `trust_status` were moved to forward migration `V7__enrichment_cost_and_trust.sql`. An isolated PostgreSQL test migrated to V4, checked its checksum, upgraded through V7, and passed Flyway validation. Fresh enrichment/runtime integration tests also passed. No Flyway history repair or validation bypass was used. This adapter introduces no schema migration.

## Prerequisites

- Use a maintained Linux Kubernetes cluster and a `kubectl` version compatible with that cluster. The worker image must include Java 21, the existing platform JAR, Git, and `kubectl`.
- Provision a namespace dedicated to this installation, a working NetworkPolicy-enforcing CNI, and the namespace-wide ingress/egress deny policy in `network-policy.json`. Validate CNI behavior operationally before executing untrusted analyzers. Kubernetes NetworkPolicy does not constitute a complete host isolation boundary; keep nodes/control plane trusted.
- Enforce restricted Pod Security admission. Disable sidecar/token/volume injection for analyzer Jobs. The adapter rejects incompatible admitted pod specifications, but this check is after admission, not a substitute for admission policy.
- Set kubelet `podPidsLimit` and suitable node reservations on every eligible analyzer node. There is deliberately no fictitious Job/Pod PID-limit field. Configure kubelet container log rotation, CSI/PVC storage quotas and monitoring, and runtime memory enforcement. Output polling can detect an excess after it happens; it is not a kernel per-directory disk quota.
- Use a CSI-backed shared `ReadWriteMany` PVC accessible to the worker and analyzer Pods in the same namespace. Supply the actual StorageClass in `storage.json`. A `ReadWriteOnce` volume requires carefully managed same-node scheduling and is not the supplied multi-node configuration.
- The trusted worker must run as UID/GID 65532 and create all attempt files on this PVC. Configure storage ownership for that identity; the example worker uses `fsGroup: 65532`. Analyzer Pods deliberately do not apply `fsGroup` recursively to the shared volume. Test CSI ownership behavior, root squashing, and subPath support in the target environment.
- Keep database credentials, kubeconfig, registry credentials, and the worker's service-account token off the artifact PVC. The analyzer gets no service-account token, database settings, kubeconfig, host socket, or Secret volume. Registry pull credentials, when needed, belong to the node or an administrator-managed analyzer service account, not the artifact volume.

References: [NetworkPolicy semantics and limitations](https://kubernetes.io/docs/concepts/services-networking/network-policies/), [node PID limiting](https://kubernetes.io/docs/concepts/policy/pid-limiting/), [Job behavior](https://kubernetes.io/docs/concepts/workloads/controllers/job/), [restricted Pod Security](https://kubernetes.io/docs/concepts/security/pod-security-standards/).

## Configuration

These are Spring environment properties; uppercase underscore environment names are accepted. Required settings are checked only when Kubernetes mode is selected.

| Property | Value or default |
| --- | --- |
| `semantic.runner.mode` | `kubernetes` to select this adapter |
| `semantic.artifact-root` | Worker's absolute PVC mount path, example `/artifacts` |
| `semantic.runner.kubernetes.namespace` | Required namespace |
| `semantic.runner.kubernetes.pvc` | Required claim in that namespace |
| `semantic.runner.kubernetes.network-policy` | Required existing namespace-wide deny policy name |
| `semantic.runner.kubernetes.allowed-images` | Required comma-separated exact `repository@sha256:<64 lowercase hex>` references |
| `semantic.runner.kubernetes.installation` | `semanticmap`; stable recovery scope, unique per installation |
| `semantic.runner.kubernetes.analyzer-service-account` | `semanticmap-analyzer`, with no RBAC binding |
| `semantic.runner.kubernetes.pvc-sub-path` | Empty; optional prefix when artifactRoot is itself a PVC subPath |
| `semantic.runner.kubernetes.runtime-class` | Empty; optional administrator-installed sandbox RuntimeClass |
| `semantic.runner.kubernetes.kubectl` | `kubectl`; trusted executable path, not a shell command |
| `semantic.runner.kubernetes.context` | Empty; optionally select an existing trusted kubeconfig context |
| `semantic.runner.kubernetes.kubeconfig` | Empty; normally use in-cluster worker credentials |
| `semantic.runner.kubernetes.cpu-millis` | `2000`, allowed 100..16000 |
| `semantic.runner.kubernetes.memory-mib` | `1024`, allowed 64..16384 |
| `semantic.runner.kubernetes.ttl-seconds` | `600`, allowed 120..86400 |
| `semantic.runner.kubernetes.command-timeout-seconds` | `15`, allowed 1..60 |
| `semantic.runner.kubernetes.poll-millis` | `1000`, allowed 100..5000 |
| `semantic.runner.timeout-seconds` | `600`, allowed 1..3600; effective deadline is min(request, runner) |
| `semantic.runner.max-output-bytes` | `134217728`, allowed 1..1073741824; effective bound is min(request, runner) |

The analyzer registry's `imageReference` must exactly match an allowed immutable reference. A tag alone is rejected even outside production. The platform API and worker must agree on the artifact layout and use the same storage where they create/read artifacts. Do not expose this namespace, worker credentials, or artifact root to unrelated workloads. Changing the installation label prevents discovery of old resources; drain the old installation first.

For example, a worker-local `/artifacts/<run>/source` becomes the analyzer's read-only `/workspace` using PVC subPath `<run>/source`. Its sibling `executions/<execution>/<attempt>/input` becomes read-only `/input`; only the attempt's `output` directory is writable at `/output`. These are separate mounts, not a whole-PVC analyzer mount. Symlinks and paths outside artifactRoot are rejected. If the worker mount uses PVC subPath `team-a`, configure `pvc-sub-path=team-a` as well.

## Deployment

The JSON files are ordinary Kubernetes manifests; `kubectl apply -f` accepts them. They are operator templates, not an already-deployed environment. Replace the example registry references and all-zero digests with images you built, reviewed, pushed, and resolved to immutable digests. Replace the example RWX StorageClass. Provision the `semanticmap-platform` Secret through your secret-management workflow with `database-url`, `database-user`, `database-password`, and `oidc-issuer-uri` keys. No credentials are checked in here.

`Dockerfile.worker` adds the official kubectl image's `/bin/kubectl` to the existing platform image and keeps its Java entrypoint. Supply pinned, architecture-compatible images for both build arguments; no default image or fabricated digest is provided. See the [upstream kubectl image definition](https://github.com/kubernetes/kubernetes/blob/master/build/server-image/kubectl/Dockerfile).

Example operator commands, not executed during verification:

```sh
docker build -f deploy/kubernetes/Dockerfile.worker \
  --build-arg PLATFORM_IMAGE="$PINNED_PLATFORM_IMAGE" \
  --build-arg KUBECTL_IMAGE="$PINNED_KUBECTL_IMAGE" \
  -t "$WORKER_IMAGE_TAG" .
docker push "$WORKER_IMAGE_TAG"
# Resolve the pushed worker digest and update worker.json before applying.
kubectl apply -f deploy/kubernetes/namespace.json
kubectl apply -f deploy/kubernetes/network-policy.json
kubectl apply -f deploy/kubernetes/rbac.json
kubectl apply -f deploy/kubernetes/storage.json
# Provision the platform Secret, wait for the PVC to bind, and check ownership.
kubectl apply -f deploy/kubernetes/worker.json
```

The Role grants only Job get/list/create/delete, Pod get/list/delete, Pod logs get, and NetworkPolicy get/list in this namespace. Pod deletion is needed for orphan recovery. There is no exec, attach, Secret read, RBAC mutation, namespace mutation, or NetworkPolicy mutation permission. Bootstrap manifests require a separate administrator. A Job-create credential is trusted: RBAC cannot restrict every Job field or label, so enforce admission controls and isolate this namespace.

The worker-egress policy is intentionally separate from analyzer policy. It permits the trusted worker TCP 443/6443/5432 and kube-system DNS. Restrict destinations to your API server, database, and source-control endpoints, and adjust actual ports/DNS placement. Some CNIs treat API-service address translation differently; verify this in your cluster. No egress exception selects the analyzer. Registry pulls are performed by the node, not by opening analyzer egress.

## Execution And Recovery

Each execution creates a deterministic Job name and labels for installation, execution ID, and attempt ID. `backoffLimit=0`, `restartPolicy=Never`, parallelism/completions 1, active deadline, and TTL are explicit. Kubernetes Jobs do not provide universal exactly-once execution; more than one observed attempt Pod is rejected.

The adapter verifies the configured namespace-wide deny policy before creating a Job, then verifies all standard NetworkPolicies against actual Pod labels while polling. Because allow policies are additive, any matching ingress/egress allow rule fails the check. Missing, malformed, incomplete, or oversized policy responses fail closed. Policy consistency is observed at polling times, not an atomic guarantee against a privileged administrator changing networking concurrently. CNI-specific policy CRDs are outside this verifier.

Analyzer Pods use UID/GID 65532, no privilege escalation, a read-only root filesystem, dropped ALL capabilities, RuntimeDefault seccomp, optional RuntimeClass, no token, no host namespaces, fixed resource requests/limits, and a bounded memory-backed `/tmp`. Source/request are read-only. The adapter rejects changed security settings, extra containers/volumes, altered image/arguments/environment, broadened mounts, or changed resource limits in returned Pod specifications.

The actual terminated container exit code is required. Exit 0 can succeed only after output validation; exit 10 remains `PARTIALLY_SUCCEEDED`, even when Kubernetes calls the Job failed. Other exit codes, missing output, malformed manifests, image identity errors, and unavailable exit evidence fail. No fallback success is synthesized.

The immutable requested image digest is returned to the existing execution persistence path and supplied through the existing analyzer digest environment variables. `kubernetes-execution.json`, outside the analyzer mount, records requested image/digest, actual runtime imageID, Job/Pod UID, exit code, and verified policy name using create-new semantics. Multi-architecture index and platform-image digests can differ; the runtime imageID is preserved separately, not falsely asserted equal to the pinned index digest.

API JSON is capped at 1 MiB with strict duplicate/trailing-token parsing, lists at 1000 entries, process stderr at 256 KiB, analyzer log download at 4 MiB/10000 lines, and output traversal at 64 entries plus the configured byte limit. Only generic command errors are surfaced; restricted artifact logs may contain source text and require normal retention/access controls. Kubernetes log rotation and actual PVC quotas still need node/storage configuration.

Every potentially submitted Job enters `finally` cleanup, including an ambiguous create timeout. Cleanup uses foreground Job deletion and individual Pod deletion, waits for observed disappearance, retries command failures once, and has a separate 40-second budget. A cleanup failure cannot produce success. Remote deletion cannot be guaranteed during an API outage: the error is retained, Job deadline/TTL remain, and the existing runner recovery process can discover Jobs and orphan Pods by labels through `running()` and call `cancelAttempt()`. `cancel(executionId)` also interrupts local polling and deletes discovered attempts. Keep recovery enabled and alert on cleanup failures.

## Local Tests

From the repository root with Java 21 and Maven available:

```sh
./mvnw -pl backend/platform -am test -Dtest='Kubernetes*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

Tests never spawn kubectl or contact a cluster. They exercise the process argument boundary with deterministic responses, real analyzer schema validation, success/partial/failure exits, cancellation, timeout, ambiguous create, cleanup failure and orphan recovery, exact label scope, output/log bounds, additive NetworkPolicy rules, generated Job security/resource/mount checks, and conditional bean registration. Running these tests is not evidence of live Kubernetes compatibility or isolation.
