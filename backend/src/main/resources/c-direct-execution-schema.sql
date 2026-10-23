-- New direct-only metadata; no financial balances, raw transaction or relayer nonce reservation.
CREATE TABLE tms_c_execution.direct_execution_claims (
 deployment_id CHAR(64) NOT NULL,
 operation_id CHAR(66) NOT NULL,
 user_id BIGINT NOT NULL,
 owner CHAR(42) NOT NULL,
 intent_digest CHAR(66) NOT NULL,
 claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(deployment_id,operation_id),
 UNIQUE(deployment_id,operation_id,user_id,owner,intent_digest),
 FOREIGN KEY(deployment_id,operation_id,user_id,owner,intent_digest)
  REFERENCES tms_c_execution.drafts(deployment_id,operation_id,user_id,owner,intent_digest)
);
CREATE TABLE tms_c_execution.direct_executions (
 deployment_id CHAR(64) NOT NULL,
 operation_id CHAR(66) NOT NULL,
 user_id BIGINT NOT NULL,
 owner CHAR(42) NOT NULL,
 intent_digest CHAR(66) NOT NULL,
 execution_json VARCHAR(32768) NOT NULL,
 execution_hash CHAR(64) NOT NULL CHECK (execution_hash ~ '^[0-9a-f]{64}$'),
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(deployment_id,operation_id),
 FOREIGN KEY(deployment_id,operation_id,user_id,owner,intent_digest)
  REFERENCES tms_c_execution.direct_execution_claims(deployment_id,operation_id,user_id,owner,intent_digest)
);
-- PostgreSQL append-only enforcement: H2 uses only the exact table prefix above.
CREATE FUNCTION tms_c_execution.reject_direct_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'C_DIRECT_APPEND_ONLY' USING ERRCODE='55000'; END;
$$;
CREATE TRIGGER c_direct_claim_append_only BEFORE UPDATE OR DELETE ON tms_c_execution.direct_execution_claims
 FOR EACH ROW EXECUTE FUNCTION tms_c_execution.reject_direct_mutation();
CREATE TRIGGER c_direct_execution_append_only BEFORE UPDATE OR DELETE ON tms_c_execution.direct_executions
 FOR EACH ROW EXECUTE FUNCTION tms_c_execution.reject_direct_mutation();
