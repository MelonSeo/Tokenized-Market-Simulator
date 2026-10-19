package com.pricetrack.exchange.futures.runtime;

import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.math.BigInteger;

/** User commands prepare durably then wake the worker. Requests do not wait for mining or hold locks across RPC. */
public final class FuturesService {
    private final FuturesIntentStore intents;private final FuturesMarketGate market;private final FuturesWorker worker;private final FuturesLedger ledger;
    public FuturesService(FuturesIntentStore intents,FuturesMarketGate market,FuturesWorker worker,FuturesLedger ledger) {
        this.intents=intents;this.market=market;this.worker=worker;this.ledger=ledger;
    }
    public FuturesIntentStore.Preview preview(long uid,Action action,String pid,long version,Side side,int leverage,BigInteger margin) {
        var mark=market.capture();var preview=intents.preview(uid,action,pid,version,side,leverage,margin,mark,market.now());
        market.validate(mark);return preview;
    }
    public Operation consume(long uid,String preview,String key,Action action,String pid,long version) {
        var v=intents.find(uid,preview,false);
        if(v.operationId()==null)market.validate(v.mark());
        var op=intents.consume(uid,preview,key,action,pid,version,market.now());worker.signal();return op;
    }
    public Operation margin(long uid,String pid,String key,long version,BigInteger added) {
        var replay=intents.marginReplay(uid,pid,key,version,added);if(replay.isPresent())return replay.get();
        var mark=market.capture();market.validate(mark);
        try {var op=intents.prepare(uid,pid,key,Action.TOP_UP,version,added,mark);worker.signal();return op;}
        catch(Failure blocked){if(blocked.code.equals("POOL_INSUFFICIENT"))ledger.recordBlocked(uid,pid,version,blocked.code);throw blocked;}
    }
}
