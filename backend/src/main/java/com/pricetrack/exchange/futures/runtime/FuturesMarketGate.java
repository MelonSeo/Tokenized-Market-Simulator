package com.pricetrack.exchange.futures.runtime;

import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.TradingPriceContext;
import java.math.BigInteger;
import java.time.*;
import java.util.Objects;

/** The same eligibility boundary is used by user previews, risk evaluation and the final signing check. */
public final class FuturesMarketGate {
    public record Mark(BigInteger price,Instant observedAt,String provider,Long generation) {}
    private final MarketPriceService market;
    private final Clock clock;
    public FuturesMarketGate(MarketPriceService market,Clock clock) { this.market=market;this.clock=clock; }
    public Instant now() { return clock.instant(); }
    public Mark capture() {
        TradingPriceContext c;
        try { c=market.currentForTrading(); }
        catch(RuntimeException unavailable) { throw new Failure("MARKET_UNAVAILABLE"); }
        if(!c.allowed() || !c.freshAt(now()) || c.snapshot().marketStatus()==com.pricetrack.exchange.market.model.MarketStatus.CLOSED
            || c.snapshot().priceStatus()==com.pricetrack.exchange.market.model.PriceStatus.STALE) throw new Failure("MARKET_UNAVAILABLE");
        BigInteger p;
        try { p=c.snapshot().price().movePointRight(8).toBigIntegerExact(); }
        catch(RuntimeException invalid) { throw new Failure("INVALID_MARK_PRICE"); }
        if(p.signum()<=0 || p.compareTo(FuturesMath.PRICE_LIMIT)>=0) throw new Failure("INVALID_MARK_PRICE");
        return new Mark(p,c.snapshot().observedAt(),c.snapshot().provider(),c.generation());
    }
    public void validate(Mark fixed) {
        Mark current=capture();
        Duration age=Duration.between(fixed.observedAt(),now());
        if(age.compareTo(Duration.ofSeconds(5))>0 || age.compareTo(Duration.ofSeconds(-2))<0)
            throw new Failure("MARKET_UNAVAILABLE");
        if(!fixed.provider().equalsIgnoreCase(current.provider()) || !Objects.equals(fixed.generation(),current.generation()))
            throw new Failure("MARKET_CONNECTION_CHANGED");
        if(!fixed.price().equals(current.price())) throw new Failure("PRICE_CHANGED");
    }
}
