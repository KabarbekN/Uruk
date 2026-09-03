CREATE TABLE llm_execution (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    node_id uuid NOT NULL,
    provider text NOT NULL,
    model text NOT NULL,
    mode text NOT NULL CHECK (mode IN ('LOCAL_PROVIDER','REMOTE_PROVIDER')),
    prompt_key text NOT NULL,
    prompt_version text NOT NULL,
    request_hash text NOT NULL,
    response_hash text,
    normalized_response_hash text,
    input_tokens bigint,
    output_tokens bigint,
    latency_ms bigint,
    attempts integer NOT NULL DEFAULT 0,
    data_left_controlled_infrastructure boolean NOT NULL,
    status text NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','REJECTED')),
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (id, organization_id, project_id, analysis_run_id),
    FOREIGN KEY (node_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id)
);
CREATE UNIQUE INDEX llm_execution_cache ON llm_execution
    (organization_id, provider, model, prompt_key, prompt_version, request_hash)
    WHERE status IN ('RUNNING','SUCCEEDED');
CREATE INDEX llm_execution_org_created ON llm_execution(organization_id, created_at);

CREATE TABLE llm_enrichment (
    id uuid PRIMARY KEY,
    execution_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    node_id uuid NOT NULL,
    title text NOT NULL,
    description text NOT NULL,
    category text NOT NULL,
    supported_fact_ids jsonb NOT NULL,
    ambiguities jsonb NOT NULL,
    claims jsonb NOT NULL,
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (execution_id),
    FOREIGN KEY (execution_id, organization_id, project_id, analysis_run_id)
        REFERENCES llm_execution(id, organization_id, project_id, analysis_run_id),
    FOREIGN KEY (node_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id)
);
CREATE INDEX llm_enrichment_node ON llm_enrichment(organization_id, project_id, node_id, created_at DESC);

CREATE TABLE llm_daily_quota (
    organization_id uuid NOT NULL REFERENCES organization(id),
    quota_date date NOT NULL,
    reserved_attempts integer NOT NULL CHECK (reserved_attempts > 0),
    PRIMARY KEY (organization_id, quota_date)
);

CREATE TABLE runtime_span (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    trace_id varchar(32) NOT NULL CHECK (trace_id ~ '^[0-9a-f]{32}$' AND trace_id <> repeat('0',32)),
    span_id varchar(16) NOT NULL CHECK (span_id ~ '^[0-9a-f]{16}$' AND span_id <> repeat('0',16)),
    parent_span_id varchar(16) CHECK (parent_span_id ~ '^[0-9a-f]{16}$' AND parent_span_id <> repeat('0',16)),
    name text NOT NULL,
    kind integer NOT NULL CHECK (kind BETWEEN 0 AND 5),
    start_nanos numeric(20,0) NOT NULL CHECK (start_nanos > 0 AND start_nanos <= 18446744073709551615),
    end_nanos numeric(20,0) NOT NULL CHECK (end_nanos >= start_nanos AND end_nanos <= 18446744073709551615),
    attributes jsonb NOT NULL,
    resource_attributes jsonb NOT NULL,
    scope jsonb NOT NULL,
    status jsonb NOT NULL,
    events jsonb NOT NULL,
    links jsonb NOT NULL,
    matched_node_id uuid,
    match_status text NOT NULL CHECK (match_status IN ('MATCHED_STABLE_KEY','MATCHED_SYMBOL','UNRESOLVED','AMBIGUOUS')),
    payload_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((matched_node_id IS NOT NULL) = (match_status IN ('MATCHED_STABLE_KEY','MATCHED_SYMBOL'))),
    UNIQUE (organization_id, project_id, analysis_run_id, trace_id, span_id),
    UNIQUE (id, organization_id, project_id, analysis_run_id),
    FOREIGN KEY (analysis_run_id, organization_id, project_id) REFERENCES analysis_run(id, organization_id, project_id),
    FOREIGN KEY (matched_node_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id)
);
CREATE INDEX runtime_span_run ON runtime_span(organization_id, project_id, analysis_run_id, start_nanos, id);
CREATE INDEX runtime_span_node ON runtime_span(organization_id, project_id, analysis_run_id, matched_node_id);
CREATE UNIQUE INDEX runtime_span_observation_scope ON runtime_span(id, organization_id, project_id, analysis_run_id, matched_node_id);

CREATE TABLE runtime_observation (
    span_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    node_id uuid NOT NULL,
    origin text NOT NULL DEFAULT 'RUNTIME_OBSERVED' CHECK (origin = 'RUNTIME_OBSERVED'),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (span_id, organization_id, project_id, analysis_run_id)
        REFERENCES runtime_span(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE,
    FOREIGN KEY (span_id, organization_id, project_id, analysis_run_id, node_id)
        REFERENCES runtime_span(id, organization_id, project_id, analysis_run_id, matched_node_id) ON DELETE CASCADE,
    FOREIGN KEY (node_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id)
);
CREATE FUNCTION reject_runtime_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Runtime evidence is immutable';
END;
$$;
CREATE TRIGGER runtime_span_immutable BEFORE UPDATE ON runtime_span FOR EACH ROW EXECUTE FUNCTION reject_runtime_update();
CREATE TRIGGER runtime_observation_immutable BEFORE UPDATE ON runtime_observation FOR EACH ROW EXECUTE FUNCTION reject_runtime_update();
