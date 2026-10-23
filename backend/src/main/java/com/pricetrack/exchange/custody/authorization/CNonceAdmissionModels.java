package com.pricetrack.exchange.custody.authorization;

import com.pricetrack.exchange.custody.runtime.CCustodyRpcReader.AdmissionPrestate;
import com.pricetrack.exchange.custody.runtime.CCustodyRpcReader.Observation;
import java.time.Instant;

/** Durable nonce selection only; not a transaction, reservation or executable approval. */
public final class CNonceAdmissionModels {
    private CNonceAdmissionModels() {}
    public static final String SCOPE="C_NONCE_ADMITTED_NOT_PREPARED_OR_EXECUTABLE";
    public record Admission(String deploymentId,long userId,String operationId,String owner,String userNonce,
                            String intentDigest,Observation<AdmissionPrestate> prestate,Instant recordedAt) {
        @com.fasterxml.jackson.annotation.JsonProperty("scope") public String scope(){return SCOPE;}
        @Override public String toString(){return "CNonceAdmission[operationId="+operationId+"]";}
    }
}
