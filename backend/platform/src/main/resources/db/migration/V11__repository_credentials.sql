CREATE TABLE repository_credential (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    reference_key varchar(100) NOT NULL,
    provider_type varchar(50) NOT NULL DEFAULT 'TOKEN',
    encrypted_token text NOT NULL,
    iv text NOT NULL,
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, reference_key)
);

CREATE INDEX idx_repo_cred_org_ref ON repository_credential(organization_id, reference_key);
