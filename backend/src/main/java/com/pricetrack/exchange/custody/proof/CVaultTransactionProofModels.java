package com.pricetrack.exchange.custody.proof;

import com.pricetrack.exchange.custody.model.CCustodyModels;
import com.pricetrack.exchange.custody.token.CTokenLedgerModels;
import java.math.BigInteger;

/** Assertions for the exact transaction prestate; none are authenticated RPC capabilities. */
public final class CVaultTransactionProofModels {
    private CVaultTransactionProofModels() {}
    public enum Decision { VERIFIED_IN_SCOPE, INSUFFICIENT, UNSUPPORTED, REJECTED }
    public record AllowanceObservation(CAuthorizationModels.ObservationPoint point,String owner,String spender,BigInteger value) {}
    public record Context(CAuthorizationModels.ObservationPoint point,CCustodyModels.State vault,
        CTokenLedgerModels.State token,String parentBlockHash,AllowanceObservation allowance) {}
    public record Input(CReceiptEnvelopeModels.Input envelope,Context context) {
        @Override public String toString(){return "CVaultTransactionProofInput[raw/signatures/evidence redacted]";}
    }
    public record Verified(CCustodyModels.State vault,CTokenLedgerModels.State token,boolean reverted) {}
    public record Result(Decision decision,String reason,Verified verified) {
        public boolean completeCoverage(){return false;}
        public boolean currentAllowanceVerified(){return false;}
        public String verificationScope(){return "PREVALIDATED_CONTEXT_ONLY; Vault cash/FIFO and token linkage, not actual code/canonical/RPC, current allowance, DB or full audit proof";}
        @Override public String toString(){return "CVaultTransactionProofResult[decision="+decision+", reason="+reason+", evidence redacted]";}
    }
}
