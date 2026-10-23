-- C read/wallet identity only. Explicit install in a NEW owned DB; no financial writes.
CREATE SCHEMA IF NOT EXISTS tms_c_identity;
CREATE TABLE IF NOT EXISTS tms_c_identity.wallet_challenges (
 id VARCHAR(36) PRIMARY KEY,
 deployment_id CHAR(64) NOT NULL CHECK (deployment_id ~ '^[0-9a-f]{64}$'),
 user_id BIGINT NOT NULL REFERENCES public.users(id),
 wallet CHAR(42) NOT NULL CHECK (wallet ~ '^0x[0-9a-f]{40}$'),
 origin VARCHAR(256) NOT NULL,
 message VARCHAR(2048) NOT NULL,
 message_hash CHAR(64) NOT NULL CHECK (message_hash ~ '^[0-9a-f]{64}$'),
 random_nonce CHAR(64) NOT NULL CHECK (random_nonce ~ '^[0-9a-f]{64}$'),
 issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL CHECK (expires_at > issued_at),
 consumed BOOLEAN NOT NULL DEFAULT FALSE,
 proof_signature VARCHAR(132),
 CHECK (proof_signature IS NULL OR proof_signature ~ '^0x[0-9a-fA-F]{130}$'),
 UNIQUE (id,deployment_id,user_id,wallet)
);
CREATE INDEX IF NOT EXISTS c_wallet_challenge_owner ON tms_c_identity.wallet_challenges(deployment_id,user_id);
CREATE TABLE IF NOT EXISTS tms_c_identity.wallet_links (
 deployment_id CHAR(64) NOT NULL CHECK (deployment_id ~ '^[0-9a-f]{64}$'),
 user_id BIGINT NOT NULL REFERENCES public.users(id),
 wallet CHAR(42) NOT NULL CHECK (wallet ~ '^0x[0-9a-f]{40}$'),
 challenge_id VARCHAR(36) NOT NULL,
 proof_hash CHAR(64) NOT NULL CHECK (proof_hash ~ '^[0-9a-f]{64}$'),
 linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY (deployment_id,user_id),
 UNIQUE (deployment_id,wallet),
 FOREIGN KEY (challenge_id,deployment_id,user_id,wallet)
   REFERENCES tms_c_identity.wallet_challenges(id,deployment_id,user_id,wallet)
);
