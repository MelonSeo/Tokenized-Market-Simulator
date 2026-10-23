package com.pricetrack.exchange.custody.proof;

import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import java.math.BigInteger;

/** Assertion-bearing inputs. These records are not authenticated RPC observations or capabilities. */
public final class CAuthorizationModels {
    private CAuthorizationModels() {}
    public enum Mode { DIRECT, RELAYED }
    public enum FailureKind { INSUFFICIENT_CONTEXT, REJECTED }
    public record ObservationPoint(BigInteger blockNumber,String blockHash,String transactionHash,BigInteger transactionIndex) {}
    public record Context(BigInteger chainId,String controller,String rules,String relayer,String oracleSigner,
            String symbolHash,String policyHash,Mode mode,String actualSender,BigInteger ethereumTransactionNonce,
            BigInteger blockTimestamp,ObservationPoint point) {}
    public record Prestate(ObservationPoint point,String nonceOwnerKey,BigInteger userNonce,
            String positionOwnerKey,String positionOwner,String rulesPositionKey,CRulesTransitionModels.Position rulesPosition) {}
    /** null code is missing evidence; only observed exact empty "0x" represents a no-code assertion. */
    public record CodeObservation(ObservationPoint point,String ownerKey,String code) {}
    public record Input(Context context,Intent intent,Report report,String userSignature,
            String priceSignature,Prestate prestate,CodeObservation ownerCode) {
        @Override public String toString() {return "CAuthorizationInput[contextPresent="+(context!=null)
            +", intentPresent="+(intent!=null)+", reportPresent="+(report!=null)+", signatures=REDACTED]";}
    }
    public record Result(Mode mode,String owner,String accountRef,String intentDigest,String reportDigest,
            BigInteger consumedUserNonce,BigInteger nextUserNonce,BigInteger ethereumTransactionNonce,ObservationPoint point) {
        public String verificationScope() {return "USER_ORACLE_AUTHORIZATION_PREVALIDATED_CONTEXT_ONLY; no actual sender/code/prestate RPC truth, "
            +"Report TTL/risk/rules transition, Vault/pause/unpaid, Receipt, canonicality, DB or full audit proof";}
        public boolean completeCoverage() {return false;}
    }
}
