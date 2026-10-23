package com.pricetrack.exchange.custody.proof;

import com.pricetrack.exchange.custody.token.CTokenLedgerModels;
import java.math.BigInteger;

/** Token-only context assertions. No Vault State or permission to advance a financial cursor. */
public final class CTokenTransactionProofModels {
    private CTokenTransactionProofModels() {}
    public enum Decision { VERIFIED_IN_SCOPE, INSUFFICIENT, UNSUPPORTED, REJECTED }
    public enum VaultAssetEffect { ZERO_DELTA }
    public record AllowanceObservation(CAuthorizationModels.ObservationPoint point,String owner,String spender,BigInteger value) {}
    public record Context(CAuthorizationModels.ObservationPoint point,CTokenLedgerModels.State token,String parentBlockHash,AllowanceObservation allowance) {}
    public record Input(CReceiptEnvelopeModels.Input envelope,Context context) {
        @Override public String toString(){return "CTokenTransactionProofInput[raw/signatures/evidence redacted]";}
    }
    public record Verified(CTokenLedgerModels.State token,String declaredVault,VaultAssetEffect vaultAssetEffect,boolean reverted) {}
    public record Result(Decision decision,String reason,Verified verified) {
        public boolean completeCoverage(){return false;}
        public boolean globalCoverage(){return false;}
        public boolean currentAllowanceVerified(){return false;}
        public String verificationScope(){return "PREVALIDATED_TOKEN_CONTEXT_ONLY; ZERO_DELTA binds declaredVault only, no actual deployment/code/RPC/canonical/current allowance, Vault projection/cursor, global overlay, DB or full audit proof";}
        @Override public String toString(){return "CTokenTransactionProofResult[decision="+decision+", reason="+reason+", evidence redacted]";}
    }
}
