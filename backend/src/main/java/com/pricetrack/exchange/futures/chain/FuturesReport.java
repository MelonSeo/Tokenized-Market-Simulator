package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.futures.ledger.FuturesRecords.Operation;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.Position;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import java.math.BigInteger;
import org.web3j.crypto.Hash;

/** Internal signed intent, never accepted from a public client or returned in an AI DTO. */
public record FuturesReport(String operationId, String positionId, String accountRef, String symbolHash,
        int action, BigInteger expectedVersion, BigInteger expectedCallEpoch, int side, int leverage,
        BigInteger quantity, BigInteger entryPriceE8, BigInteger collateralBefore, BigInteger collateralAfter,
        BigInteger priceE8, BigInteger observedAt, BigInteger validUntil, String policyHash, String executor) {
    public static final String SYMBOL_HASH = Hash.sha3String("mSEC");

    public static FuturesReport from(Operation op, Position p, String executor) {
        if (!op.positionId().equals(p.id()) || op.userId() != p.userId())
            throw new IllegalArgumentException("REPORT_POSITION_CONFLICT");
        return new FuturesReport(Hash.sha3String(op.id()), Hash.sha3String(p.id()), p.accountRef(), SYMBOL_HASH,
            op.action().ordinal(), BigInteger.valueOf(op.expectedVersion()), BigInteger.valueOf(op.expectedEpoch()),
            p.side().ordinal(), p.leverage(), p.quantity(), p.entryPrice(), op.collateralBefore(), op.collateralAfter(),
            op.price(), BigInteger.valueOf(op.observedAt()), BigInteger.valueOf(Math.addExact(op.observedAt(), 30)),
            FuturesLedger.POLICY_HASH, executor);
    }
}
