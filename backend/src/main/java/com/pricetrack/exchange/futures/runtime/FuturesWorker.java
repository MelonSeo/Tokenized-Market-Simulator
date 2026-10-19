package com.pricetrack.exchange.futures.runtime;

import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.web3j.crypto.Hash;

/** Dedicated single-process worker, NOT a Spring global scheduler or distributed executor. Coalesced wakeups, bounded scans. */
public final class FuturesWorker implements AutoCloseable {
    private final FuturesLedger ledger; private final FuturesIntentStore intents; private final FuturesMarketGate market;
    private final FuturesRpcGateway rpc; private final FuturesReconciler reconciler; private final FuturesExecution execution;
    private final FuturesTransactionStore transactions;
    private final FuturesRiskEvaluator evaluator=new FuturesRiskEvaluator();
    private final AtomicBoolean queued=new AtomicBoolean(),running=new AtomicBoolean();
    private final AtomicLong failures=new AtomicLong();
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"futures-risk-worker");t.setDaemon(true);return t;});
    private String cursor="";
    public FuturesWorker(FuturesLedger ledger,FuturesIntentStore intents,FuturesMarketGate market,FuturesRpcGateway rpc,
        FuturesReconciler reconciler,FuturesExecution execution,FuturesTransactionStore transactions) {
        this.ledger=ledger;this.intents=intents;this.market=market;this.rpc=rpc;this.reconciler=reconciler;this.execution=execution;this.transactions=transactions;
    }
    public void start() { executor.scheduleWithFixedDelay(this::tick,1000,1000,TimeUnit.MILLISECONDS); }
    public void signal() {
        if(executor.isShutdown() || !queued.compareAndSet(false,true))return;
        try { executor.execute(()->{queued.set(false);tick();}); }
        catch(RejectedExecutionException closed) { queued.set(false); }
    }
    public long failureCount() { return failures.get(); }
    /** Also exercised directly by integration tests; no clock manipulation or test-only liquidation path. */
    public void tick() {
        if(!running.compareAndSet(false,true))return;
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        try {
            // Resolve earlier signed intent even while the market is closed or unavailable.
            for(String id:transactions.pending(50)) { guarded(()->reconciler.reconcile(id));if(System.nanoTime()>stop)return; }
            for(String id:intents.prepared()) { guarded(()->execution.submitPrepared(id));if(System.nanoTime()>stop)return; }
            var positions=ledger.riskCandidates(cursor,50);
            if(positions.isEmpty()){cursor="";return;}
            for(var p:positions) {
                cursor=p.id();guarded(()->evaluate(p));if(System.nanoTime()>stop)return;
            }
        } catch(RuntimeException unavailable) { failures.incrementAndGet(); }
        finally { running.set(false); }
    }
    private void evaluate(Position p) {
        FuturesMarketGate.Mark mark;
        try { mark=market.capture(); }
        catch(FuturesLedger.Failure blocked) { ledger.recordBlocked(p.userId(),p.id(),p.version(),"MARKET_UNAVAILABLE");return; }
        try {
            long chainNow=rpc.block(rpc.height()).getTimestamp().longValueExact();
            var action=evaluator.evaluate(p,mark,market.now().getEpochSecond(),chainNow);
            if(action.isEmpty())return;
            String key=Hash.sha3String("risk|"+p.id()+"|"+p.version()+"|"+action.get()+"|"+mark.observedAt());
            var op=intents.prepare(p.userId(),p.id(),key,action.get(),p.version(),BigInteger.ZERO,mark);
            execution.submitPrepared(op.id());
        } catch(java.io.IOException unavailable) { failures.incrementAndGet(); }
        catch(FuturesLedger.Failure denied) {
            if(denied.code.equals("POOL_INSUFFICIENT"))ledger.recordBlocked(p.userId(),p.id(),p.version(),denied.code);
            else failures.incrementAndGet();
        }
    }
    private void guarded(Runnable work) { try { work.run(); }catch(RuntimeException unavailable){failures.incrementAndGet();} }
    @Override public void close() { executor.shutdownNow(); }
}
