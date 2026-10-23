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
    private final java.util.function.Consumer<String> beforeAuthorizedSigning;
    @FunctionalInterface public interface Repricing {boolean apply(String id,FuturesReport report,FuturesMarketGate.Mark mark);}
    private final Repricing repricing;
    public FuturesExecution(FuturesLedger ledger,FuturesIntentStore intents,FuturesMarketGate market,FuturesChainIdentity chain,
        Credentials signer,FuturesTransactionStore store,FuturesTransactionSender sender) {
        this(ledger,intents,market,chain,signer,store,sender,id->{});
    }
    public FuturesExecution(FuturesLedger ledger,FuturesIntentStore intents,FuturesMarketGate market,FuturesChainIdentity chain,Credentials signer,FuturesTransactionStore store,FuturesTransactionSender sender,java.util.function.Consumer<String> check) {
        this(ledger,intents,market,chain,signer,store,sender,check,(id,r,m)->false);
    }
    public FuturesExecution(FuturesLedger ledger,FuturesIntentStore intents,FuturesMarketGate market,FuturesChainIdentity chain,Credentials signer,FuturesTransactionStore store,FuturesTransactionSender sender,java.util.function.Consumer<String> check,Repricing repricing) {
        this.ledger=ledger;this.intents=intents;this.market=market;this.chain=chain;this.signer=signer;this.store=store;this.sender=sender;this.beforeAuthorizedSigning=check;
        this.repricing=repricing;
    }
    public void submitPrepared(String id) {
        FuturesDiagnostics.work(FuturesDiagnostics.Stage.WORKER_EXECUTION,()->{submitPreparedInternal(id);return null;});
    }
    private void submitPreparedInternal(String id) {
      for(int attempt=0;attempt<2;attempt++) {
        if(store.find(id).isPresent()) { sender.recover(id);return; }
        var op=ledger.operation(id);if(!op.state().equals("PREPARED"))return;
        var report=FuturesReport.from(op,ledger.position(op.positionId(),op.userId()),chain.reportExecutor());
        FuturesMarketGate.Mark mark=null;
        try {
            mark=intents.evidence(id);final var fixedMark=mark;FuturesDiagnostics.work(FuturesDiagnostics.Stage.MARKET_RECHECK,()->{market.validate(fixedMark);return null;});
            if(op.action()==Action.LIQUIDATE && op.observedAt()>market.now().getEpochSecond())throw new Failure("INVALID_LIQUIDATION_TIME");
            String signature;
            if(chain.authorized()&&com.pricetrack.exchange.futures.authorization.UserIntentCodec.userAction(report.action())){
                var fixed=store.fixedAuthorizedReport(id).orElseThrow(()->new Failure("USER_SIGNATURE_REQUIRED"));
                if(!fixed.getKey().equals(report))throw new Failure("AUTHORIZED_REPORT_CONFLICT");signature=fixed.getValue();
            }else signature=FuturesReportCodec.sign(report,chain.chainId(),chain.priceContract(),signer.getEcKeyPair());
            sender.submit(id,report,signature,()->FuturesDiagnostics.work(FuturesDiagnostics.Stage.PRE_SIGN_RECHECK,()->{market.validate(fixedMark);if(chain.authorized()){beforeAuthorizedSigning.accept(id);market.validate(fixedMark);}return null;}));
            return;
        } catch(Failure failure) {
            if(attempt==0 && failure.code.equals("PRICE_CHANGED") && mark!=null && chain.v2()) {
                try{if(repricing.apply(id,report,mark))continue;}
                catch(Failure denied){failure=denied;}
            }
            if(failure.code.equals("REVISION_CONFLICT"))return; // another worker won; never reject its revision
            // The database rechecks under locks. An uncertain signed COMMIT must never release collateral.
            if(store.find(id).isEmpty() && ledger.operation(id).state().equals("PREPARED")) {
                if(chain.v2())ledger.rejectUnsignedAtReport(id,failure.code,report);else ledger.rejectUnsigned(id,failure.code);
            }
            else if(store.find(id).isPresent())return;
            throw failure;
        }
      }
    }
}
