-- Additive C nonce selection metadata only. Existing consent and wallet schemas are unchanged.
CREATE TABLE tms_c_execution.nonce_admissions (
 deployment_id CHAR(64) NOT NULL,
 operation_id CHAR(66) NOT NULL,
 user_id BIGINT NOT NULL,
 owner CHAR(42) NOT NULL,
 user_nonce NUMERIC(78,0) NOT NULL CHECK (user_nonce >= 0 AND user_nonce < 115792089237316195423570985008687907853269984665640564039457584007913129639935),
 intent_digest CHAR(66) NOT NULL,
 observation_json VARCHAR(32768) NOT NULL,
 observation_hash CHAR(64) NOT NULL CHECK (observation_hash ~ '^[0-9a-f]{64}$'),
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(deployment_id,operation_id),
 UNIQUE(deployment_id,owner,user_nonce),
 FOREIGN KEY(deployment_id,operation_id) REFERENCES tms_c_execution.consents(deployment_id,operation_id),
 FOREIGN KEY(deployment_id,operation_id,user_id,owner,intent_digest)
  REFERENCES tms_c_execution.drafts(deployment_id,operation_id,user_id,owner,intent_digest)
);
-- PostgreSQL append-only enforcement: H2 tests use only the unchanged prefix above.
CREATE FUNCTION tms_c_execution.reject_nonce_admission_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'C_NONCE_ADMISSION_APPEND_ONLY' USING ERRCODE='55000'; END;
$$;
CREATE TRIGGER c_nonce_admission_append_only BEFORE UPDATE OR DELETE ON tms_c_execution.nonce_admissions
 FOR EACH ROW EXECUTE FUNCTION tms_c_execution.reject_nonce_admission_mutation();
