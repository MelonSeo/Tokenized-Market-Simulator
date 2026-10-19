package com.pricetrack.exchange.futures.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.model.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

class FuturesMarketGateTest {
    final Instant now=Instant.parse("2026-10-19T00:00:00Z");
    final MarketPriceService market=mock(MarketPriceService.class);
    final FuturesMarketGate gate=new FuturesMarketGate(market,Clock.fixed(now,ZoneOffset.UTC));
    TradingPriceContext context(String provider,long gen,long age,boolean allowed,MarketStatus status,PriceStatus price,String value) {
        return new TradingPriceContext(new MarketPriceSnapshot("mSEC",new BigDecimal(value),new BigDecimal("75000"),BigDecimal.ZERO,BigDecimal.ZERO,status,price,provider,now.minusSeconds(age)),allowed,"fixture",gen);
    }
    @Test void normalCurrentGenerationAcceptedButUnknownIsNotCalledOpen() {
        when(market.currentForTrading()).thenReturn(context("KIWOOM",3,0,true,MarketStatus.UNKNOWN,PriceStatus.LIVE,"75000"));
        var mark=gate.capture();assertThat(mark.generation()).isEqualTo(3);gate.validate(mark);
    }
    @Test void oldAndTooFuturePricesRejected() {
        for(long age:new long[]{6,-3}) {when(market.currentForTrading()).thenReturn(context("SIMULATED",0,age,true,MarketStatus.OPEN,PriceStatus.SIMULATED,"75000"));assertThatThrownBy(gate::capture).hasMessage("MARKET_UNAVAILABLE");}
    }
    @Test void closedStaleOrLostConnectionMustBeDisallowedByProvider() {
        when(market.currentForTrading()).thenReturn(context("KIWOOM",3,0,false,MarketStatus.CLOSED,PriceStatus.STALE,"75000"));assertThatThrownBy(gate::capture).hasMessage("MARKET_UNAVAILABLE");
    }
    @Test void changedGenerationAfterRpcFailsBeforeSigning() {
        when(market.currentForTrading()).thenReturn(context("KIWOOM",3,0,true,MarketStatus.UNKNOWN,PriceStatus.LIVE,"75000"));var fixed=gate.capture();
        when(market.currentForTrading()).thenReturn(context("KIWOOM",4,0,true,MarketStatus.UNKNOWN,PriceStatus.LIVE,"75000"));assertThatThrownBy(()->gate.validate(fixed)).hasMessage("MARKET_CONNECTION_CHANGED");
    }
    @Test void changedPriceRequiresReconfirmationInsteadOfSilentRepricing() {
        when(market.currentForTrading()).thenReturn(context("SIMULATED",0,0,true,MarketStatus.OPEN,PriceStatus.SIMULATED,"75000"));var fixed=gate.capture();
        when(market.currentForTrading()).thenReturn(context("SIMULATED",0,0,true,MarketStatus.OPEN,PriceStatus.SIMULATED,"75001"));assertThatThrownBy(()->gate.validate(fixed)).hasMessage("PRICE_CHANGED");
    }
}
