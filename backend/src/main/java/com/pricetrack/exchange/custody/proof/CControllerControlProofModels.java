package com.pricetrack.exchange.custody.proof;

import com.pricetrack.exchange.custody.model.CCustodyModels;
import com.pricetrack.exchange.custody.token.CTokenLedgerModels;
import java.math.BigInteger;

/** Exact pretransaction observations are caller assertions, not RPC-authenticated capabilities. */
public final class CControllerControlProofModels {
    private CControllerControlProofModels() {}
    public enum Decision { VERIFIED_IN_SCOPE, INSUFFICIENT, UNSUPPORTED, REJECTED }
    public record NonceObservation(CAuthorizationModels.ObservationPoint point,String owner,BigInteger nonce) {}
    public record Context(CAuthorizationModels.ObservationPoint point,CCustodyModels.State vault,
        CTokenLedgerModels.State token,String parentBlockHash,NonceObservation nonce,CAuthorizationModels.CodeObservation ownerCode) {}
    public record Input(CReceiptEnvelopeModels.Input envelope,Context context) {
        @Override public String toString(){return "CControllerControlProofInput[raw/signatures/evidence redacted]";}
    }
    public record Verified(CCustodyModels.State vault,CTokenLedgerModels.State token,boolean reverted) {}
    public record Result(Decision decision,String reason,Verified verified) {
        public boolean completeCoverage(){return false;}
        public String verificationScope(){return "PREVALIDATED_CONTEXT_ONLY; controller cancel/pause linkage, not actual getter/code/RPC/canonical truth, DB or full audit proof";}
        @Override public String toString(){return "CControllerControlProofResult[decision="+decision+", reason="+reason+", evidence redacted]";}
    }
}
