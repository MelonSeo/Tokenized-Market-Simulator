package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import java.io.IOException;
import java.util.Set;

/** No surrounding DB transaction: all RPC work precedes short atomic settlement. Never fabricates a failed receipt. */
public final class FuturesReconciler {
    public enum Result { WAITING, CONFIRMED, REVERTED, REVIEW_REQUIRED, ALREADY_FINAL, RPC_UNAVAILABLE }
    private final FuturesChainIdentity chain;
    private final FuturesRpcGateway rpc;
    private final FuturesTransactionStore store;
    private final FuturesTransactionSender sender;
    private final FuturesLedger ledger;
    private final int confirmations;
    public FuturesReconciler(FuturesChainIdentity chain,FuturesRpcGateway rpc,FuturesTransactionStore store,
                              FuturesTransactionSender sender,FuturesLedger ledger,int confirmations) {
        if(confirmations<1 || confirmations>64) throw new IllegalArgumentException("INVALID_CONFIRMATIONS");
        this.chain=chain;this.rpc=rpc;this.store=store;this.sender=sender;this.ledger=ledger;this.confirmations=confirmations;
    }
    public Result reconcile(String id) {
        var s=store.find(id).orElseThrow();
        if(!Set.of("SIGNED","SUBMITTED").contains(s.status())) return Result.ALREADY_FINAL;
        try {
            rpc.verifyDeployment(chain);
            FuturesTransactionStore.validateRaw(chain,s.report(),s.signature(),s.raw(),s.hash(),s.nonce());
            var op=ledger.operation(id); var p=ledger.position(op.positionId(),op.userId());
            if(!s.report().equals(FuturesReport.from(op,p,chain.executor())) || !FuturesReportCodec.signedBy(s.report(),chain.chainId(),chain.engine(),s.signature(),chain.priceSigner())) throw new FuturesLedger.Failure("STORED_REPORT_CONFLICT");
            var optional=rpc.receipt(s.hash());
            if(optional.isEmpty()) { if(s.status().equals("SIGNED")) sender.recover(id); return Result.WAITING; }
            var receipt=optional.get(); var number=receipt.getBlockNumber();
            if(number==null || rpc.height().compareTo(number.add(java.math.BigInteger.valueOf(confirmations-1)))<0) return Result.WAITING;
            var block=rpc.block(number);
            var tx=rpc.transaction(s.hash()).orElseThrow(()->new IOException("RECEIPT_TRANSACTION_UNAVAILABLE"));
            // Reorg/lag is inconclusive; do not classify a non-canonical receipt as a successful or failed execution.
            if(!block.getHash().equalsIgnoreCase(receipt.getBlockHash())) return Result.WAITING;
            FuturesReceiptVerifier.transaction(s,receipt,tx,block);
            boolean failed="0x0".equalsIgnoreCase(receipt.getStatus());
            if(failed && (receipt.getLogs()==null || !receipt.getLogs().isEmpty())) throw new FuturesLedger.Failure("FAILED_RECEIPT_WITH_LOGS");
            var event=failed?null:FuturesReceiptVerifier.success(s,p,receipt,tx,block,rpc.position(p.id(),number));
            if(!block.getHash().equalsIgnoreCase(rpc.block(number).getHash())) return Result.WAITING;
            if(failed) { ledger.settleReverted(id,s.hash(),number.longValueExact(),block.getHash()); return Result.REVERTED; }
            ledger.settle(event); return Result.CONFIRMED;
        } catch(IOException unavailable) { return Result.RPC_UNAVAILABLE; }
        catch(FuturesLedger.Failure mismatch) { ledger.markReview(id); return Result.REVIEW_REQUIRED; }
    }
}
