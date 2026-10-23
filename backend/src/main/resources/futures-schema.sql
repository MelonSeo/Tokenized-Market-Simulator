-- Opt-in futures module only. No existing balance, order, grant or chain row is migrated.
CREATE TABLE IF NOT EXISTS futures_policy (
 id INT PRIMARY KEY CHECK (id=1), policy_hash VARCHAR(66) NOT NULL,
 initial_capital NUMERIC(78,0) NOT NULL CHECK (initial_capital=10000000000000000000000000),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS futures_house (
 id INT PRIMARY KEY CHECK (id=1), amount NUMERIC(78,0) NOT NULL CHECK (amount>=0),
 reserved NUMERIC(78,0) NOT NULL DEFAULT 0 CHECK (reserved>=0 AND reserved<=amount)
);
CREATE TABLE IF NOT EXISTS futures_user_guards (
 user_id BIGINT PRIMARY KEY REFERENCES users(id), account_ref VARCHAR(66) NOT NULL UNIQUE,
 active_position VARCHAR(36)
);
CREATE TABLE IF NOT EXISTS futures_positions (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), account_ref VARCHAR(66) NOT NULL,
 side VARCHAR(5) NOT NULL CHECK (side IN ('LONG','SHORT')), leverage INT NOT NULL CHECK (leverage IN (2,5,10)),
 quantity NUMERIC(78,0) NOT NULL CHECK (quantity>0), entry_price NUMERIC(78,0) NOT NULL CHECK (entry_price>0),
 collateral NUMERIC(78,0) NOT NULL CHECK (collateral>0),
 status VARCHAR(24) NOT NULL, risk_status VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 call_epoch BIGINT NOT NULL DEFAULT 0, deadline BIGINT NOT NULL DEFAULT 0,
 last_observed_at BIGINT NOT NULL DEFAULT 0, pending_operation VARCHAR(36), block_reason VARCHAR(80),
 realized NUMERIC(78,0), bad_debt NUMERIC(78,0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_futures_positions_user ON futures_positions(user_id,created_at);
CREATE TABLE IF NOT EXISTS futures_operations (
 id VARCHAR(36) PRIMARY KEY, position_id VARCHAR(36) NOT NULL REFERENCES futures_positions(id),
 active_position_id VARCHAR(36) UNIQUE REFERENCES futures_positions(id),
 user_id BIGINT NOT NULL REFERENCES users(id), action VARCHAR(12) NOT NULL,
 expected_version BIGINT NOT NULL, expected_epoch BIGINT NOT NULL,
 key_hash VARCHAR(64) NOT NULL, request_hash VARCHAR(66) NOT NULL,
 price NUMERIC(78,0) NOT NULL CHECK (price>0), observed_at BIGINT NOT NULL,
 collateral_before NUMERIC(78,0) NOT NULL, collateral_after NUMERIC(78,0) NOT NULL,
 reserved_margin NUMERIC(78,0) NOT NULL CHECK (reserved_margin>=0),
 reserved_house NUMERIC(78,0) NOT NULL CHECK (reserved_house>=0),
 expected_realized NUMERIC(78,0) NOT NULL, expected_bad_debt NUMERIC(78,0) NOT NULL,
 state VARCHAR(24) NOT NULL, reason VARCHAR(80), report_json TEXT, signature TEXT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT uk_futures_user_key UNIQUE (user_id,key_hash)
);
CREATE INDEX IF NOT EXISTS ix_futures_operations_position ON futures_operations(position_id);
CREATE TABLE IF NOT EXISTS futures_previews (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), action VARCHAR(12) NOT NULL,
 position_id VARCHAR(36), expected_version BIGINT NOT NULL, side VARCHAR(5) NOT NULL, leverage INT NOT NULL,
 margin NUMERIC(78,0) NOT NULL, price NUMERIC(78,0) NOT NULL, observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 provider VARCHAR(32) NOT NULL, generation BIGINT, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 operation_id VARCHAR(36) UNIQUE REFERENCES futures_operations(id), key_hash VARCHAR(64),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
-- Internal fixed-price evidence. No raw reports, signatures, credentials or client-supplied market state.
CREATE TABLE IF NOT EXISTS futures_market_evidence (
 operation_id VARCHAR(36) PRIMARY KEY REFERENCES futures_operations(id),
 price NUMERIC(78,0) NOT NULL, observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 provider VARCHAR(32) NOT NULL, generation BIGINT
);
-- Negative outcome is serialized with prepare; a delayed same-key request cannot execute after definitive rejection.
CREATE TABLE IF NOT EXISTS futures_request_rejections (
 user_id BIGINT NOT NULL REFERENCES users(id), key_hash VARCHAR(64) NOT NULL, request_hash VARCHAR(66) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY(user_id,key_hash)
);
CREATE TABLE IF NOT EXISTS futures_transactions (
 id VARCHAR(36) PRIMARY KEY, operation_id VARCHAR(36) NOT NULL UNIQUE REFERENCES futures_operations(id),
 sender VARCHAR(42) NOT NULL, nonce BIGINT NOT NULL, chain_id NUMERIC(78,0) NOT NULL,
 contract_address VARCHAR(42) NOT NULL, raw_transaction TEXT NOT NULL, tx_hash VARCHAR(66) NOT NULL UNIQUE,
 status VARCHAR(24) NOT NULL, block_number BIGINT, block_hash VARCHAR(66),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, submitted_at TIMESTAMP WITH TIME ZONE, confirmed_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT uk_futures_sender_nonce UNIQUE (sender,nonce)
);
-- Serializes sign admission only; it is not a distributed nonce allocator.
CREATE TABLE IF NOT EXISTS futures_sender_guards (
 sender VARCHAR(42) PRIMARY KEY, chain_id NUMERIC(78,0) NOT NULL, contract_address VARCHAR(42) NOT NULL
);
CREATE TABLE IF NOT EXISTS futures_journal (
 id VARCHAR(36) PRIMARY KEY, operation_id VARCHAR(36) NOT NULL REFERENCES futures_operations(id),
 position_id VARCHAR(36) NOT NULL REFERENCES futures_positions(id), user_id BIGINT NOT NULL,
 entry_type VARCHAR(24) NOT NULL, wallet_delta NUMERIC(78,0) NOT NULL,
 locked_delta NUMERIC(78,0) NOT NULL, house_delta NUMERIC(78,0) NOT NULL,
 bad_debt NUMERIC(78,0) NOT NULL DEFAULT 0 CHECK (bad_debt>=0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT uk_futures_journal_entry UNIQUE (operation_id,entry_type),
 CHECK (wallet_delta+house_delta=0)
);

-- Independent futures-domain provenance; never rewrites the spot reserve baseline.
CREATE TABLE IF NOT EXISTS futures_audit_baselines (
 id INT PRIMARY KEY CHECK (id=1), payload TEXT NOT NULL, actor_id BIGINT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS futures_audit_runs (
 id VARCHAR(36) PRIMARY KEY, payload TEXT NOT NULL, actor_id BIGINT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- B/v1 additive metadata. User keys are never accepted or stored. Private signatures: no public DTO/log access.
CREATE TABLE IF NOT EXISTS futures_wallet_links (
 user_id BIGINT PRIMARY KEY REFERENCES users(id), wallet VARCHAR(42) NOT NULL UNIQUE,
 inflight_draft VARCHAR(36), linked_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS futures_wallet_challenges (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), wallet VARCHAR(42) NOT NULL,
 message TEXT NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, consumed BOOLEAN NOT NULL DEFAULT FALSE, proof_signature TEXT
);
CREATE TABLE IF NOT EXISTS futures_authorized_positions (
 position_id VARCHAR(36) PRIMARY KEY REFERENCES futures_positions(id), user_id BIGINT NOT NULL REFERENCES users(id),
 metadata_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS futures_authorized_drafts (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), position_id VARCHAR(36) NOT NULL,
 operation_id VARCHAR(36) NOT NULL UNIQUE, request_key VARCHAR(64) NOT NULL,
 report_json TEXT NOT NULL, price_signature TEXT NOT NULL, metadata_json TEXT NOT NULL,
 price NUMERIC(78,0) NOT NULL, observed_at TIMESTAMP WITH TIME ZONE NOT NULL, provider VARCHAR(32) NOT NULL, generation BIGINT,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL, state VARCHAR(16) NOT NULL,
 UNIQUE(user_id,request_key)
);
CREATE TABLE IF NOT EXISTS futures_authorized_operations (
 operation_id VARCHAR(36) PRIMARY KEY REFERENCES futures_operations(id), metadata_json TEXT NOT NULL,
 report_json TEXT, price_signature TEXT
);
-- V2 supplemental routing: never add positional columns to existing V1 evidence tables.
CREATE TABLE IF NOT EXISTS futures_authorized_v2_drafts (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id),
 position_id VARCHAR(36) NOT NULL, operation_id VARCHAR(36) NOT NULL UNIQUE,
 request_key VARCHAR(64) NOT NULL, intent_json TEXT NOT NULL, metadata_json TEXT NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL, state VARCHAR(16) NOT NULL,
 UNIQUE(user_id,request_key)
);
CREATE TABLE IF NOT EXISTS futures_authorized_v2_positions (
 position_id VARCHAR(36) PRIMARY KEY REFERENCES futures_authorized_positions(position_id),
 controller VARCHAR(42) NOT NULL
);
CREATE TABLE IF NOT EXISTS futures_authorized_v2_operations (
 operation_id VARCHAR(36) PRIMARY KEY REFERENCES futures_authorized_operations(operation_id),
 controller VARCHAR(42) NOT NULL
);

-- B-v2 user-action executions only. Original approval/report rows remain immutable.
CREATE TABLE IF NOT EXISTS futures_v2_execution_revisions (
 operation_id VARCHAR(36) NOT NULL REFERENCES futures_authorized_v2_operations(operation_id),
 revision INT NOT NULL CHECK (revision IN (0,1)), report_json TEXT NOT NULL, price_signature TEXT NOT NULL,
 price NUMERIC(78,0) NOT NULL, observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 provider VARCHAR(32) NOT NULL, generation BIGINT, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(operation_id,revision)
);
CREATE TABLE IF NOT EXISTS futures_v2_execution_heads (
 operation_id VARCHAR(36) PRIMARY KEY REFERENCES futures_authorized_v2_operations(operation_id),
 active_revision INT NOT NULL CHECK (active_revision IN (0,1)),
 FOREIGN KEY(operation_id,active_revision) REFERENCES futures_v2_execution_revisions(operation_id,revision)
);
