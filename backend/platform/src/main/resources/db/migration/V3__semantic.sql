CREATE UNIQUE INDEX analysis_run_semantic_scope ON analysis_run(id, organization_id, project_id);
CREATE UNIQUE INDEX execution_semantic_scope ON analyzer_execution(id, organization_id, project_id, revision_id, analysis_run_id);

CREATE TABLE raw_fact (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    analyzer_execution_id uuid NOT NULL,
    fact_id text NOT NULL,
    stable_key text NOT NULL,
    kind text NOT NULL,
    origin text NOT NULL,
    confidence double precision NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    payload jsonb NOT NULL,
    fingerprint text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (analyzer_execution_id, fact_id),
    UNIQUE (id, organization_id, project_id, analysis_run_id),
    FOREIGN KEY (analyzer_execution_id, organization_id, project_id, revision_id, analysis_run_id)
        REFERENCES analyzer_execution(id, organization_id, project_id, revision_id, analysis_run_id) ON DELETE CASCADE
);
CREATE INDEX raw_fact_run ON raw_fact(organization_id, project_id, analysis_run_id, stable_key);

CREATE TABLE fact_quarantine (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    analyzer_execution_id uuid NOT NULL,
    line_number bigint NOT NULL,
    reason text NOT NULL,
    raw_line text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (analyzer_execution_id, line_number, reason),
    FOREIGN KEY (analyzer_execution_id, organization_id, project_id, revision_id, analysis_run_id)
        REFERENCES analyzer_execution(id, organization_id, project_id, revision_id, analysis_run_id) ON DELETE CASCADE
);

CREATE TABLE evidence (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    raw_fact_id uuid NOT NULL,
    analyzer_execution_id uuid NOT NULL,
    file_path text NOT NULL,
    start_line integer NOT NULL CHECK (start_line > 0),
    end_line integer NOT NULL CHECK (end_line >= start_line),
    start_column integer NOT NULL CHECK (start_column > 0),
    end_column integer NOT NULL CHECK (end_column > 0),
    snippet text NOT NULL,
    snippet_hash text NOT NULL,
    analyzer_id text NOT NULL,
    analyzer_version text NOT NULL,
    image_digest text,
    origin text NOT NULL,
    verified boolean NOT NULL DEFAULT true CHECK (verified),
    UNIQUE (id, organization_id, project_id, analysis_run_id),
    FOREIGN KEY (raw_fact_id, organization_id, project_id, analysis_run_id)
        REFERENCES raw_fact(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE,
    FOREIGN KEY (analyzer_execution_id, organization_id, project_id, revision_id, analysis_run_id)
        REFERENCES analyzer_execution(id, organization_id, project_id, revision_id, analysis_run_id) ON DELETE CASCADE
);
CREATE INDEX evidence_fact ON evidence(organization_id, project_id, analysis_run_id, raw_fact_id);

CREATE TABLE semantic_node (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    stable_key text NOT NULL,
    kind text NOT NULL,
    name text NOT NULL,
    label text NOT NULL,
    subtitle text NOT NULL DEFAULT '',
    confidence double precision NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    support_level text NOT NULL,
    properties jsonb NOT NULL DEFAULT '{}',
    source_fact_ids jsonb NOT NULL DEFAULT '[]',
    evidence_ids jsonb NOT NULL DEFAULT '[]',
    fingerprint text NOT NULL,
    structural_fingerprint text NOT NULL,
    evidence_fingerprint text NOT NULL,
    unresolved boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, project_id, analysis_run_id, stable_key),
    UNIQUE (id, organization_id, project_id, analysis_run_id),
    FOREIGN KEY (analysis_run_id, organization_id, project_id) REFERENCES analysis_run(id, organization_id, project_id) ON DELETE CASCADE
);
CREATE INDEX semantic_node_projection ON semantic_node(organization_id, project_id, analysis_run_id, kind, confidence);
CREATE INDEX semantic_node_search ON semantic_node USING gin(to_tsvector('simple', label || ' ' || stable_key));

CREATE TABLE semantic_edge (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    source_id uuid NOT NULL,
    target_id uuid NOT NULL,
    kind text NOT NULL,
    label text NOT NULL,
    confidence double precision NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    evidence_ids jsonb NOT NULL,
    properties jsonb NOT NULL DEFAULT '{}',
    UNIQUE (organization_id, project_id, analysis_run_id, source_id, target_id, kind),
    FOREIGN KEY (source_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE,
    FOREIGN KEY (target_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE
);
CREATE INDEX semantic_edge_source ON semantic_edge(organization_id, project_id, analysis_run_id, source_id);
CREATE INDEX semantic_edge_target ON semantic_edge(organization_id, project_id, analysis_run_id, target_id);

CREATE TABLE assertion (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    node_id uuid NOT NULL,
    category text NOT NULL,
    normalized_condition jsonb NOT NULL,
    true_outcomes jsonb NOT NULL,
    false_outcomes jsonb NOT NULL,
    score_breakdown jsonb NOT NULL,
    model jsonb NOT NULL,
    confidence double precision NOT NULL,
    UNIQUE (id, organization_id, project_id, analysis_run_id),
    UNIQUE (node_id),
    FOREIGN KEY (node_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE
);
CREATE TABLE assertion_evidence (
    assertion_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    PRIMARY KEY (assertion_id, evidence_id),
    FOREIGN KEY (assertion_id, organization_id, project_id, analysis_run_id)
        REFERENCES assertion(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE,
    FOREIGN KEY (evidence_id, organization_id, project_id, analysis_run_id)
        REFERENCES evidence(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE
);

CREATE TABLE business_scenario (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    entry_node_id uuid NOT NULL,
    node_id uuid NOT NULL,
    model jsonb NOT NULL,
    truncated boolean NOT NULL,
    UNIQUE (id, organization_id, project_id, analysis_run_id),
    FOREIGN KEY (node_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE,
    FOREIGN KEY (entry_node_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE
);
CREATE TABLE business_scenario_member (
    scenario_id uuid NOT NULL,
    node_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    depth integer NOT NULL,
    role text NOT NULL,
    PRIMARY KEY (scenario_id, node_id),
    FOREIGN KEY (scenario_id, organization_id, project_id, analysis_run_id)
        REFERENCES business_scenario(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE,
    FOREIGN KEY (node_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE
);

CREATE TABLE semantic_change (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    from_analysis_run_id uuid NOT NULL,
    to_analysis_run_id uuid NOT NULL,
    change_type text NOT NULL,
    subject_stable_key text NOT NULL,
    impact_type text NOT NULL,
    before_state jsonb,
    after_state jsonb,
    before_evidence_ids jsonb NOT NULL,
    after_evidence_ids jsonb NOT NULL,
    confidence double precision NOT NULL,
    UNIQUE (organization_id, project_id, from_analysis_run_id, to_analysis_run_id, subject_stable_key, change_type, impact_type),
    FOREIGN KEY (from_analysis_run_id, organization_id, project_id) REFERENCES analysis_run(id, organization_id, project_id) ON DELETE CASCADE,
    FOREIGN KEY (to_analysis_run_id, organization_id, project_id) REFERENCES analysis_run(id, organization_id, project_id) ON DELETE CASCADE
);

CREATE TABLE review_decision (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    node_id uuid NOT NULL,
    user_id uuid NOT NULL,
    decision text NOT NULL CHECK (decision IN ('CONFIRMED','REJECTED','EDITED','MERGED','MARKED_TECHNICAL','NEEDS_REVIEW','STALE')),
    comment text,
    edited_title text,
    edited_description text,
    merge_target_id uuid,
    fingerprint text NOT NULL,
    carried_from_id uuid REFERENCES review_decision(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (node_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE,
    FOREIGN KEY (merge_target_id, organization_id, project_id, analysis_run_id)
        REFERENCES semantic_node(id, organization_id, project_id, analysis_run_id) ON DELETE CASCADE
);
CREATE INDEX review_decision_latest ON review_decision(organization_id, project_id, node_id, created_at DESC, id);
CREATE UNIQUE INDEX review_carry_once ON review_decision(node_id, carried_from_id) WHERE carried_from_id IS NOT NULL;

CREATE TABLE canvas_layout (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    user_id uuid NOT NULL,
    view_type text NOT NULL,
    semantic_node_stable_key text NOT NULL,
    x double precision NOT NULL,
    y double precision NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, project_id, analysis_run_id, user_id, view_type, semantic_node_stable_key),
    FOREIGN KEY (analysis_run_id, organization_id, project_id) REFERENCES analysis_run(id, organization_id, project_id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, project_id, analysis_run_id, semantic_node_stable_key)
        REFERENCES semantic_node(organization_id, project_id, analysis_run_id, stable_key) ON DELETE CASCADE
);
CREATE TABLE canvas_view_state (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    user_id uuid NOT NULL,
    view_type text NOT NULL,
    viewport jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, project_id, analysis_run_id, user_id, view_type),
    FOREIGN KEY (analysis_run_id, organization_id, project_id) REFERENCES analysis_run(id, organization_id, project_id) ON DELETE CASCADE
);
