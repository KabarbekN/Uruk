ALTER TABLE llm_execution
    ADD COLUMN estimated_cost_usd numeric CHECK (estimated_cost_usd >= 0);

ALTER TABLE llm_enrichment
    ADD COLUMN trust_status text NOT NULL DEFAULT 'UNVERIFIED' CHECK (trust_status = 'UNVERIFIED');
