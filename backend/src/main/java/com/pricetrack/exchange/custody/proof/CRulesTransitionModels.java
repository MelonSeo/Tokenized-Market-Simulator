package com.pricetrack.exchange.custody.proof;

import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventModels;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;

/** Full rules state, unlike the accounting reducer's intentionally reduced Vault lock projection. */
public final class CRulesTransitionModels {
    private CRulesTransitionModels() {}
    public static final String ZERO_HASH="0x"+"0".repeat(64);
    public static final String SCOPE="RULES_TRANSITION_PREVALIDATED_CONTEXT_ONLY; no Oracle/user signature, "
        +"EOA/code identity, caller, user/transaction nonce, Receipt, canonicality or full audit proof";
    public enum Action {
        OPEN, TOP_UP, START_CALL, CLEAR_CALL, CLOSE, LIQUIDATE;
        public BigInteger code() {return BigInteger.valueOf(ordinal());}
    }
    public enum Status { NONE, ACTIVE, MARGIN_CALL, CLOSED, LIQUIDATED }
    public record Terms(String executor,String symbolHash,String policyHash) {
        public Terms {executor=CAbiModels.address(executor);symbolHash=CAbiModels.hash(symbolHash);policyHash=CAbiModels.hash(policyHash);}
    }
    public record Position(String accountRef,BigInteger side,BigInteger leverage,BigInteger quantity,
        BigInteger entryPriceE8,BigInteger collateral,Status status,BigInteger version,
        BigInteger callEpoch,BigInteger deadline,BigInteger lastObservedAt) {
        public Position {accountRef=CAbiModels.hash(accountRef);}
        public static Position empty() {return new Position(ZERO_HASH,BigInteger.ZERO,BigInteger.ZERO,
            BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,Status.NONE,BigInteger.ZERO,
            BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO);}
    }
    /** Keys of the asserted position / active-position / operation mapping reads, not a chain query proof. */
    public record PrestateKeys(String positionId,String accountRef,String operationId) {
        public PrestateKeys {positionId=CAbiModels.hash(positionId);accountRef=CAbiModels.hash(accountRef);operationId=CAbiModels.hash(operationId);}
    }
    public record Input(Action expectedAction,CAbiModels.Report report,Terms terms,Position before,
        BigInteger blockTimestamp,boolean operationUsed,String activePositionForAccount,PrestateKeys prestateKeys) {
        public Input {activePositionForAccount=CAbiModels.hash(activePositionForAccount);}
    }
    public record Result(Position after,String activePositionForAccount,FuturesMath.Mark mark,
        CAbiEventModels.OperationApplied operationApplied,CAbiEventModels.FuturesReportConsumed reportConsumed,
        boolean operationConsumed) {
        public String verificationScope() {return SCOPE;}
        public boolean completeCoverage() {return false;}
    }
}
