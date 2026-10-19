package com.pricetrack.exchange.futures.audit;

import com.pricetrack.exchange.futures.chain.FuturesChainIdentity;
import com.pricetrack.exchange.reserve.ReserveModels.Check;
import java.time.Instant;
import java.util.List;

/** Safe ADMIN evidence only: no raw transaction, report signature, private key or RPC original error. */
public final class FuturesAuditModels {
    private FuturesAuditModels() {}
    public record Block(long number,String hash) {}
    public record Baseline(String id,String executionId,FuturesChainIdentity chain,String codeHash,String policyHash,
            String initialHouse,Block block,Instant dbAt,String dbFingerprint,long actorId) {}
    public record Coverage(int operations,int transactions,int verifiedTransactions,long fromBlock,long toBlock,
            int scannedEvents,int verifiedEvents,boolean complete) {}
    public record Item(String operationId,String positionId,long userId,String action,String dbState,
            String verdict,String reason,String txHash,Long blockNumber,String blockHash) {}
    public record Result(String id,String status,String reason,Instant createdAt,long actorId,Baseline baseline,
            Instant dbAt,Block block,Coverage coverage,long dbCheckCount,long dbMismatchCount,
            List<Check> checks,List<Item> items,String limitation) {}
}
