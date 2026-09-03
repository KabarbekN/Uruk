CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE TABLE organization (
 id UUID PRIMARY KEY, name TEXT NOT NULL, ai_mode TEXT NOT NULL DEFAULT 'DISABLED',
 remote_allowed BOOLEAN NOT NULL DEFAULT false, retention_days INT NOT NULL DEFAULT 7 CHECK(retention_days BETWEEN 1 AND 365),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE user_account (id UUID PRIMARY KEY, subject TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE organization_member (organization_id UUID NOT NULL REFERENCES organization(id), user_id UUID NOT NULL REFERENCES user_account(id), role TEXT NOT NULL, PRIMARY KEY(organization_id,user_id));
CREATE TABLE project (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organization(id), name VARCHAR(120) NOT NULL,
 description VARCHAR(2000) NOT NULL DEFAULT '', repository_path TEXT NOT NULL DEFAULT '',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id)
);
CREATE INDEX project_org_idx ON project(organization_id,created_at DESC);
CREATE TABLE project_member (organization_id UUID NOT NULL,project_id UUID NOT NULL,user_id UUID NOT NULL REFERENCES user_account(id),role TEXT NOT NULL,PRIMARY KEY(project_id,user_id),FOREIGN KEY(organization_id,project_id) REFERENCES project(organization_id,id) ON DELETE CASCADE);
CREATE TABLE audit_event (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organization(id), project_id UUID,
 user_id UUID, action TEXT NOT NULL, metadata JSONB NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_org_idx ON audit_event(organization_id,created_at DESC);
