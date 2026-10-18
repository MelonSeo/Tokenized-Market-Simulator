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
CREATE TABLE IF NOT EXISTS futures_transactions (
 id VARCHAR(36) PRIMARY KEY, operation_id VARCHAR(36) NOT NULL UNIQUE REFERENCES futures_operations(id),
 sender VARCHAR(42) NOT NULL, nonce BIGINT NOT NULL, chain_id NUMERIC(78,0) NOT NULL,
 contract_address VARCHAR(42) NOT NULL, raw_transaction TEXT NOT NULL, tx_hash VARCHAR(66) NOT NULL UNIQUE,
 status VARCHAR(24) NOT NULL, block_number BIGINT, block_hash VARCHAR(66),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, submitted_at TIMESTAMP WITH TIME ZONE, confirmed_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT uk_futures_sender_nonce UNIQUE (sender,nonce)
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
