# Observability

Authenticated operators can scrape `/actuator/prometheus`; health/readiness and
liveness are public. Production uses ECS JSON logs and per-request correlation
IDs. Worker task logs include organization, project, run, task and, where present,
analyzer execution IDs. Source code is not intentionally logged by the worker.

`semantic.task.duration` times actual task attempts including their fenced commit,
tagged by task type and committed/failed/abandoned outcome. Labels do not contain
tenant IDs or source symbols. `semantic.sse.connections` reflects current streams
on each API instance.

Queue and last-hour analyzer/LLM gauges are database snapshots refreshed every
30 seconds. `semantic.metrics.snapshot_age_seconds` identifies stale snapshots.
Missing latency or provider token/cost values remain NaN; they are not silently
turned into zero. LLM usage gauges sum reported values, not authoritative billing.
The analyzer mean is measured from RUN_ANALYZER task start/end timestamps.

Example alerts: increasing snapshot age, sustained pending tasks without running
tasks, permanent analyzer failures, high request latency, datasource exhaustion,
or approaching the per-instance 128-stream capacity. Configure thresholds from
measured workload; no external telemetry service is required locally.
