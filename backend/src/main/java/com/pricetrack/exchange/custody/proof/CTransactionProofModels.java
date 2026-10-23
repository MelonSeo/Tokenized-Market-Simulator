package com.pricetrack.exchange.custody.proof;

import com.pricetrack.exchange.custody.model.CCustodyModels;
import com.pricetrack.exchange.custody.token.CTokenLedgerModels;

/** Immutable prevalidated context assertions, not RPC/canonical evidence or trusted proof capabilities. */
public final class CTransactionProofModels {
    private CTransactionProofModels() {}
    public enum Decision { VERIFIED_IN_SCOPE, INSUFFICIENT, UNSUPPORTED, REJECTED }
    public record Context(CAuthorizationModels.ObservationPoint point, String symbolHash,
        CAuthorizationModels.Prestate authorizationPrestate, CAuthorizationModels.CodeObservation ownerCode,
        CRulesTransitionModels.PrestateKeys rulesKeys, Boolean operationUsed, String activePosition,
        CCustodyModels.State vault, CTokenLedgerModels.State token, String parentBlockHash) {}
    public record Input(CReceiptEnvelopeModels.Input envelope,Context context) {
        @Override public String toString(){return "CTransactionProofInput[raw/signatures redacted]";}
    }
    public record Verified(CCustodyModels.State vault,CTokenLedgerModels.State token,
        CRulesTransitionModels.Result rules,CAuthorizationModels.Result authorization,boolean reverted) {}
    public record Result(Decision decision,String reason,Verified verified) {
        public String verificationScope(){return "PREVALIDATED_CONTEXT_ONLY; user/keeper transaction linkage/accounting, not actual code/canonical/RPC, market5/provider, current allowance, DB or full audit";}
        public boolean completeCoverage(){return false;}
        @Override public String toString(){return "CTransactionProofResult[decision="+decision+", reason="+reason+", raw/evidence redacted]";}
    }
}
