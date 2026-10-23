-- Additive baseline identities. No copy/seed/update of legacy futures_audit_baselines(id=1).
-- PostgreSQL append-only UPDATE/DELETE/TRUNCATE guard is installed by FuturesSchema.
CREATE TABLE IF NOT EXISTS futures_audit_deployments (
    deployment_key VARCHAR(66) PRIMARY KEY,
    chain_id BIGINT NOT NULL CHECK (chain_id > 0),
    controller_address VARCHAR(42) NOT NULL,
    rules_address VARCHAR(42) NOT NULL,
    route VARCHAR(10) NOT NULL CHECK (route IN ('LEGACY','B_V1','B_V2')),
    payload TEXT NOT NULL,
    actor_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(chain_id,controller_address),
    UNIQUE(chain_id,rules_address)
);
