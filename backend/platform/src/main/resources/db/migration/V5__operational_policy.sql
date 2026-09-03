ALTER TABLE project ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE evidence ADD COLUMN source_available BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE evidence ADD COLUMN expired_at TIMESTAMPTZ;
CREATE INDEX evidence_retention ON evidence(analysis_run_id) WHERE source_available;
