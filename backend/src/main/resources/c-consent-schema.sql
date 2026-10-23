-- New C consent metadata only; explicit owned-fixture installation, no financial tables.
CREATE SCHEMA IF NOT EXISTS tms_c_execution;
CREATE TABLE IF NOT EXISTS tms_c_execution.drafts (
 deployment_id CHAR(64) NOT NULL CHECK (deployment_id ~ '^[0-9a-f]{64}$'),
 operation_id CHAR(66) NOT NULL CHECK (operation_id ~ '^0x[0-9a-f]{64}$'),
 user_id BIGINT NOT NULL,
 owner CHAR(42) NOT NULL CHECK (owner ~ '^0x[0-9a-f]{40}$'),
 key_hash CHAR(64) NOT NULL CHECK (key_hash ~ '^[0-9a-f]{64}$'),
 request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
 intent_digest CHAR(66) NOT NULL CHECK (intent_digest ~ '^0x[0-9a-f]{64}$'),
 intent_json VARCHAR(8192) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(deployment_id,operation_id),
 UNIQUE(deployment_id,user_id,key_hash),
 UNIQUE(deployment_id,operation_id,user_id,owner,intent_digest),
 FOREIGN KEY(deployment_id,user_id) REFERENCES tms_c_identity.wallet_links(deployment_id,user_id)
);
CREATE TABLE IF NOT EXISTS tms_c_execution.consents (
 deployment_id CHAR(64) NOT NULL,
 operation_id CHAR(66) NOT NULL,
 user_id BIGINT NOT NULL,
 owner CHAR(42) NOT NULL,
 intent_digest CHAR(66) NOT NULL,
 user_signature VARCHAR(132) NOT NULL CHECK (user_signature ~ '^0x[0-9a-f]{130}$'),
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(deployment_id,operation_id),
 FOREIGN KEY(deployment_id,operation_id,user_id,owner,intent_digest)
  REFERENCES tms_c_execution.drafts(deployment_id,operation_id,user_id,owner,intent_digest)
);
-- PostgreSQL append-only enforcement: actual PG Gate required; not covered by H2 prefix tests.
CREATE OR REPLACE FUNCTION tms_c_execution.reject_consent_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'C_CONSENT_APPEND_ONLY' USING ERRCODE='55000'; END;
$$;
CREATE TRIGGER c_draft_append_only BEFORE UPDATE OR DELETE ON tms_c_execution.drafts
 FOR EACH ROW EXECUTE FUNCTION tms_c_execution.reject_consent_mutation();
CREATE TRIGGER c_consent_append_only BEFORE UPDATE OR DELETE ON tms_c_execution.consents
 FOR EACH ROW EXECUTE FUNCTION tms_c_execution.reject_consent_mutation();
