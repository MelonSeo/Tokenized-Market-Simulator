package com.pricetrack.exchange.futures.read;

import java.time.Instant;

/** Allowlisted DTO shared by future REST and read-only AI adapters; never contains raw tx or signatures. */
public record FuturesEvidenceView(String positionId,String operationId,String action,String operationState,
    String transactionState,String txHash,Long blockNumber,String blockHash,boolean canonicalConfirmed,
    String price,String collateral,String realized,String badDebt,Long callEpoch,Long deadline,
    String reasonCode,Instant retrievedAt) {
    public boolean settledFact() { return canonicalConfirmed && "CONFIRMED".equals(operationState); }
}
