-- C-only evidence storage. Explicit installation; no legacy/B/user financial writes.
CREATE SCHEMA IF NOT EXISTS tms_c_custody;

CREATE TABLE IF NOT EXISTS tms_c_custody.deployments (
 deployment_id VARCHAR(128) PRIMARY KEY CHECK (length(deployment_id)>0),
 codec_version INTEGER NOT NULL CHECK (codec_version=1),
 payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 4194304),
 payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);
CREATE TABLE IF NOT EXISTS tms_c_custody.canonical_epochs (
 deployment_id VARCHAR(128) NOT NULL REFERENCES tms_c_custody.deployments(deployment_id),
 epoch BIGINT NOT NULL CHECK (epoch=1),
 anchor_number BIGINT NOT NULL CHECK (anchor_number>=0),
 anchor_hash CHAR(66) NOT NULL CHECK (anchor_hash ~ '^0x[0-9a-f]{64}$'),
 anchor_parent_hash CHAR(66) NOT NULL CHECK (anchor_parent_hash ~ '^0x[0-9a-f]{64}$'),
 seed_state_hash CHAR(64) NOT NULL CHECK (seed_state_hash ~ '^[0-9a-f]{64}$'),
 PRIMARY KEY (deployment_id,epoch)
);
CREATE TABLE IF NOT EXISTS tms_c_custody.chain_observations (
 deployment_id VARCHAR(128) NOT NULL REFERENCES tms_c_custody.deployments(deployment_id),
 block_hash CHAR(66) NOT NULL CHECK (block_hash ~ '^0x[0-9a-f]{64}$'),
 tx_hash CHAR(66) NOT NULL CHECK (tx_hash ~ '^0x[0-9a-f]{64}$'),
 block_number BIGINT NOT NULL CHECK (block_number>=0),
 tx_index INTEGER NOT NULL CHECK (tx_index>=0),
 codec_version INTEGER NOT NULL CHECK (codec_version=1),
 payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 4194304),
 payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
 PRIMARY KEY (deployment_id,block_hash,tx_hash)
);
CREATE TABLE IF NOT EXISTS tms_c_custody.projection_snapshots (
 deployment_id VARCHAR(128) NOT NULL,
 epoch BIGINT NOT NULL,
 revision BIGINT NOT NULL CHECK (revision>=0),
 block_number BIGINT NOT NULL CHECK (block_number>=0),
 block_hash CHAR(66) NOT NULL CHECK (block_hash ~ '^0x[0-9a-f]{64}$'),
 tx_index INTEGER NOT NULL CHECK (tx_index>=-1),
 codec_version INTEGER NOT NULL CHECK (codec_version=1),
 payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 4194304),
 state_hash CHAR(64) NOT NULL CHECK (state_hash ~ '^[0-9a-f]{64}$'),
 PRIMARY KEY (deployment_id,epoch,revision),
 UNIQUE (deployment_id,epoch,revision,state_hash),
 FOREIGN KEY (deployment_id,epoch) REFERENCES tms_c_custody.canonical_epochs(deployment_id,epoch)
);
CREATE TABLE IF NOT EXISTS tms_c_custody.projection_heads (
 deployment_id VARCHAR(128) PRIMARY KEY REFERENCES tms_c_custody.deployments(deployment_id),
 epoch BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 state_hash CHAR(64) NOT NULL,
 FOREIGN KEY (deployment_id,epoch,revision,state_hash)
   REFERENCES tms_c_custody.projection_snapshots(deployment_id,epoch,revision,state_hash)
);

CREATE OR REPLACE FUNCTION tms_c_custody.reject_evidence_mutation()
 RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='C immutable evidence';
END;
$$;
DO $$
DECLARE target TEXT;
BEGIN
 FOREACH target IN ARRAY ARRAY['deployments','canonical_epochs','chain_observations','projection_snapshots']
 LOOP
  IF NOT EXISTS (SELECT 1 FROM pg_trigger
    WHERE tgname=target||'_immutable' AND tgrelid=('tms_c_custody.'||target)::regclass)
  THEN
   EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE OR TRUNCATE ON tms_c_custody.%I FOR EACH STATEMENT EXECUTE FUNCTION tms_c_custody.reject_evidence_mutation()',target||'_immutable',target);
  END IF;
 END LOOP;
END;
$$;
