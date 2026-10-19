package com.pricetrack.exchange.futures.ledger;

import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.math.BigInteger;
import java.time.Instant;

/** Internal exact records. Public/AI views are separate so raw/signature fields cannot leak by serialization. */
public final class FuturesRecords {
    private FuturesRecords() {}
    public enum Action { OPEN, TOP_UP, START_CALL, CLEAR_CALL, CLOSE, LIQUIDATE }
    public record Position(String id,long userId,String accountRef,Side side,int leverage,BigInteger quantity,
        BigInteger entryPrice,BigInteger collateral,String status,String riskStatus,long version,long callEpoch,
        long deadline,long lastObservedAt,String pendingOperation,String blockReason,BigInteger realized,BigInteger badDebt,Instant createdAt) {}
    public record Operation(String id,String positionId,long userId,Action action,long expectedVersion,long expectedEpoch,
        BigInteger price,long observedAt,BigInteger collateralBefore,BigInteger collateralAfter,
        BigInteger reservedMargin,BigInteger reservedHouse,BigInteger expectedRealized,BigInteger expectedBadDebt,String state) {}
    /** Only supplied after canonical receipt and report/event validation by the chain boundary. */
    public record ConfirmedEvent(String operationId,String txHash,long blockNumber,String blockHash,long blockTimestamp,
        long version,long callEpoch,long deadline,BigInteger price,BigInteger collateral,
        BigInteger realized,BigInteger badDebt) {}
}
