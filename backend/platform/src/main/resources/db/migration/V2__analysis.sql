CREATE UNIQUE INDEX project_tenant_key ON project (organization_id, id);

CREATE TABLE repository_connection (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    url text NOT NULL,
    branch text NOT NULL DEFAULT 'HEAD',
    credentials_reference varchar(100),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, project_id, id),
    UNIQUE (organization_id, project_id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id) ON DELETE CASCADE
);

CREATE TABLE revision (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    commit_sha text NOT NULL,
    branch text NOT NULL,
    fingerprint text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, project_id, id),
    UNIQUE (organization_id, project_id, commit_sha, fingerprint),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id) ON DELETE CASCADE
);

CREATE TABLE revision_parent (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    parent_commit_sha text NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    PRIMARY KEY (organization_id, project_id, revision_id, ordinal),
    FOREIGN KEY (organization_id, project_id, revision_id) REFERENCES revision(organization_id, project_id, id) ON DELETE CASCADE
);

CREATE TABLE analysis_run (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision_id uuid,
    baseline_run_id uuid,
    status varchar(30) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELLED')),
    revision text NOT NULL DEFAULT 'HEAD',
    requested_ref text NOT NULL DEFAULT 'HEAD',
    repository_url text NOT NULL,
    credentials_reference varchar(100),
    requested_by uuid NOT NULL,
    idempotency_key varchar(200),
    contract_version varchar(20) NOT NULL DEFAULT '1.0',
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    progress integer NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    current_stage varchar(100) NOT NULL DEFAULT 'QUEUED',
    workspace_path text,
    config_hash text NOT NULL,
    failure_code varchar(100),
    failure_message text,
    cancellation_requested_at timestamptz,
    artifacts_deleted_at timestamptz,
    UNIQUE (organization_id, project_id, id),
    UNIQUE (organization_id, project_id, revision_id, id),
    UNIQUE (organization_id, project_id, idempotency_key),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, project_id, revision_id) REFERENCES revision(organization_id, project_id, id),
    FOREIGN KEY (organization_id, project_id, baseline_run_id) REFERENCES analysis_run(organization_id, project_id, id),
    CHECK (baseline_run_id IS DISTINCT FROM id)
);
CREATE INDEX analysis_run_project_created ON analysis_run(organization_id, project_id, created_at DESC);

CREATE TABLE revision_changed_file (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    file_path text NOT NULL,
    change_type varchar(10) NOT NULL CHECK (change_type IN ('ADDED','MODIFIED','DELETED')),
    PRIMARY KEY (organization_id, project_id, analysis_run_id, file_path),
    FOREIGN KEY (organization_id, project_id, revision_id, analysis_run_id) REFERENCES analysis_run(organization_id, project_id, revision_id, id) ON DELETE CASCADE
);

CREATE TABLE analysis_task (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    task_type varchar(100) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}',
    status varchar(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED_PERMANENTLY','CANCELLED','SKIPPED')),
    priority integer NOT NULL DEFAULT 100,
    idempotency_key varchar(300) NOT NULL,
    attempt integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    accept_failed_dependencies boolean NOT NULL DEFAULT false,
    available_at timestamptz NOT NULL DEFAULT now(),
    locked_by varchar(200),
    lease_token uuid,
    locked_until timestamptz,
    heartbeat_at timestamptz,
    progress_percent integer NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    progress_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    error_code varchar(100),
    error_message text,
    UNIQUE (organization_id, project_id, analysis_run_id, id),
    UNIQUE (analysis_run_id, idempotency_key),
    FOREIGN KEY (organization_id, project_id, analysis_run_id) REFERENCES analysis_run(organization_id, project_id, id) ON DELETE CASCADE
);
CREATE INDEX analysis_task_ready ON analysis_task(status, available_at, priority);
CREATE INDEX analysis_task_run ON analysis_task(organization_id, project_id, analysis_run_id);

CREATE TABLE analysis_task_dependency (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    task_id uuid NOT NULL,
    depends_on_task_id uuid NOT NULL,
    PRIMARY KEY (task_id, depends_on_task_id),
    CHECK (task_id <> depends_on_task_id),
    FOREIGN KEY (organization_id, project_id, analysis_run_id, task_id) REFERENCES analysis_task(organization_id, project_id, analysis_run_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, project_id, analysis_run_id, depends_on_task_id) REFERENCES analysis_task(organization_id, project_id, analysis_run_id, id) ON DELETE CASCADE
);

CREATE TABLE analysis_event (
    sequence_number bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    type varchar(100) NOT NULL,
    message text NOT NULL,
    progress integer NOT NULL CHECK (progress BETWEEN 0 AND 100),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, project_id, analysis_run_id) REFERENCES analysis_run(organization_id, project_id, id) ON DELETE CASCADE
);
CREATE INDEX analysis_event_replay ON analysis_event(analysis_run_id, sequence_number);

CREATE TABLE analyzer_definition (
    id uuid PRIMARY KEY,
    analyzer_key varchar(100) NOT NULL,
    version varchar(50) NOT NULL,
    image_reference text NOT NULL,
    image_digest text,
    contract_version varchar(20) NOT NULL DEFAULT '1.0',
    supported_languages jsonb NOT NULL DEFAULT '[]',
    supported_frameworks jsonb NOT NULL DEFAULT '[]',
    capabilities jsonb NOT NULL DEFAULT '[]',
    supports_incremental boolean NOT NULL DEFAULT false,
    requires_dependency_resolution boolean NOT NULL DEFAULT false,
    network_policy varchar(10) NOT NULL DEFAULT 'DENY' CHECK (network_policy = 'DENY'),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    retired_at timestamptz,
    UNIQUE (analyzer_key, version)
);

CREATE TABLE technology_component (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    root_path text NOT NULL,
    languages jsonb NOT NULL,
    frameworks jsonb NOT NULL,
    build_systems jsonb NOT NULL,
    database_technologies jsonb NOT NULL,
    evidence jsonb NOT NULL,
    fingerprint text NOT NULL,
    diagnostics jsonb NOT NULL DEFAULT '[]',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, project_id, analysis_run_id, id),
    UNIQUE (organization_id, project_id, analysis_run_id, root_path),
    FOREIGN KEY (organization_id, project_id, revision_id, analysis_run_id) REFERENCES analysis_run(organization_id, project_id, revision_id, id) ON DELETE CASCADE
);

CREATE TABLE analyzer_execution (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    component_id uuid NOT NULL,
    analyzer_key varchar(100) NOT NULL,
    version varchar(50) NOT NULL,
    image_reference text NOT NULL,
    image_digest text,
    status varchar(30) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','RUNNING','SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELLED')),
    reason text NOT NULL,
    requested_capabilities jsonb NOT NULL DEFAULT '[]',
    output_path text,
    coverage jsonb NOT NULL DEFAULT '{}',
    diagnostics jsonb NOT NULL DEFAULT '[]',
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (organization_id, project_id, id),
    UNIQUE (organization_id, project_id, analysis_run_id, id),
    UNIQUE (organization_id, project_id, revision_id, analysis_run_id, id),
    UNIQUE (analysis_run_id, component_id, analyzer_key),
    FOREIGN KEY (organization_id, project_id, revision_id, analysis_run_id) REFERENCES analysis_run(organization_id, project_id, revision_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, project_id, analysis_run_id, component_id) REFERENCES technology_component(organization_id, project_id, analysis_run_id, id),
    FOREIGN KEY (analyzer_key, version) REFERENCES analyzer_definition(analyzer_key, version)
);

INSERT INTO analyzer_definition(id, analyzer_key, version, image_reference, supported_languages, supported_frameworks, capabilities) VALUES
('10000000-0000-0000-0000-000000000001','tree-sitter','0.1.0','semanticmap/tree-sitter:0.1.0','["JAVA","TYPESCRIPT","JAVASCRIPT"]','[]','["SYMBOLS","CONDITIONS","CALLS"]'),
('10000000-0000-0000-0000-000000000002','java-spring','0.1.0','semanticmap/java-spring:0.1.0','["JAVA"]','["SPRING_BOOT","SPRING_MVC","JPA"]','["SYMBOLS","ENDPOINTS","CALL_GRAPH","VALIDATIONS","AUTHORIZATION","DATABASE_ACCESS","SIDE_EFFECTS","TEST_LINKS"]'),
('10000000-0000-0000-0000-000000000003','postgresql','0.1.0','semanticmap/postgresql:0.1.0','["SQL"]','[]','["DATABASE_SCHEMA","DEFAULTS","CONSTRAINTS","TRIGGERS"]'),
('10000000-0000-0000-0000-000000000004','typescript-node','0.1.0','semanticmap/typescript-node:0.1.0','["TYPESCRIPT","JAVASCRIPT"]','["NESTJS","EXPRESS","REACT"]','["SYMBOLS","ENDPOINTS","VALIDATIONS","SIDE_EFFECTS"]');
