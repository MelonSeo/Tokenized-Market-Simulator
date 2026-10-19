package com.pricetrack.exchange.futures.runtime;

import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import org.web3j.crypto.Credentials;

/** Invoked only outside prepare transactions. Signed intent is resolved, never cancelled due to a later price change. */
public final class FuturesExecution {
    private final FuturesLedger ledger;
    private final FuturesIntentStore intents;
    private final FuturesMarketGate market;
    private final FuturesChainIdentity chain;
    private final Credentials signer;
    private final FuturesTransactionStore store;
    private final FuturesTransactionSender sender;
    public FuturesExecution(FuturesLedger ledger,FuturesIntentStore intents,FuturesMarketGate market,FuturesChainIdentity chain,
        Credentials signer,FuturesTransactionStore store,FuturesTransactionSender sender) {
        this.ledger=ledger;this.intents=intents;this.market=market;this.chain=chain;this.signer=signer;this.store=store;this.sender=sender;
    }
    public void submitPrepared(String id) {
        if(store.find(id).isPresent()) { sender.recover(id);return; }
        var op=ledger.operation(id);if(!op.state().equals("PREPARED"))return;
        try {
            var mark=intents.evidence(id);market.validate(mark);
            if(op.action()==Action.LIQUIDATE && op.observedAt()>market.now().getEpochSecond())throw new Failure("INVALID_LIQUIDATION_TIME");
            var report=FuturesReport.from(op,ledger.position(op.positionId(),op.userId()),chain.executor());
            String signature=FuturesReportCodec.sign(report,chain.chainId(),chain.engine(),signer.getEcKeyPair());
            sender.submit(id,report,signature,()->market.validate(mark));
        } catch(Failure failure) {
            // The database rechecks under locks. An uncertain signed COMMIT must never release collateral.
            if(store.find(id).isEmpty() && ledger.operation(id).state().equals("PREPARED"))ledger.rejectUnsigned(id,failure.code);
            else if(store.find(id).isPresent())return;
            throw failure;
        }
    }
}
