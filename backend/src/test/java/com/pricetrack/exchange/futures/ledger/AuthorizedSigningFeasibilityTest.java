package com.pricetrack.exchange.futures.ledger;

import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.model.*;
import java.math.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled clock + actual market gate/service/store; real local EOA crypto, H2, fake RPC.
 * No wallet UI, wall-clock latency claim, PostgreSQL, Anvil or external API. */
class AuthorizedSigningFeasibilityTest {
    AuthorizedFuturesStoreTest f;
    MarketPriceService prices;
    AtomicReference<TradingPriceContext> tick;
    @BeforeEach void setup() throws Exception {
        f=new AuthorizedFuturesStoreTest();f.setup();prices=mock(MarketPriceService.class);tick=new AtomicReference<>();
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return f.now.get();}};
        f.market=new FuturesMarketGate(prices,clock);
        when(prices.currentForTrading()).thenAnswer(x->tick.get());publish("75000","SIMULATED",1,true);
        var access=new AuthorizedFuturesService.ChainAccess(){public void verify(){}public boolean eoa(String w){return true;}public BigInteger nonce(String w){return BigInteger.ZERO;}};
        f.service=new AuthorizedFuturesService(f.store,f.ledger,f.market,AuthorizedFuturesStoreTest.CHAIN,AuthorizedFuturesStoreTest.PRICE,access,f.wake::incrementAndGet,AuthorizedFuturesStoreTest.ORIGIN);
        f.link(1,AuthorizedFuturesStoreTest.USER);
    }
    @AfterEach void close(){f.close();}
    void publish(String price,String provider,long generation,boolean allowed){
        tick.set(new TradingPriceContext(new MarketPriceSnapshot("mSEC",new BigDecimal(price),new BigDecimal("75000"),BigDecimal.ZERO,BigDecimal.ZERO,MarketStatus.OPEN,PriceStatus.SIMULATED,provider,f.now.get()),allowed,"controlled",generation));
    }
    AuthorizedFuturesService.DraftView draft(String key){return f.service.draft(1,key,f.request());}
    void noReservation(){assertThat(f.jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isZero();assertThat(f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();}
    @Test void fiveSecondInclusiveBoundaryAcceptsSamePrice(){
        String k=f.key();var d=draft(k);String signature=f.signature(d);
        f.now.set(f.now.get().plusSeconds(5));publish("75000","SIMULATED",1,true);
        assertThat(f.service.submit(1,d.id(),k,signature).state()).isEqualTo("PREPARED");
    }
    @Test void freshSamePriceTickDoesNotRefreshOriginalDraftAge(){
        String k=f.key();var d=draft(k);String signature=f.signature(d);
        f.now.set(f.now.get().plusMillis(5001));publish("75000","SIMULATED",1,true);
        assertThatThrownBy(()->f.service.submit(1,d.id(),k,signature)).hasMessage("MARKET_UNAVAILABLE");noReservation();
        assertThat(f.store.draft(1,d.id(),false).state()).isEqualTo("DRAFT");
    }
    @Test void priceChangingTickBlocksBeforeFiveSecondsButDoesNotPersistentlyCancelDraft(){
        String k=f.key();var d=draft(k);String signature=f.signature(d);f.now.set(f.now.get().plusSeconds(1));publish("75001","SIMULATED",1,true);
        assertThatThrownBy(()->f.service.submit(1,d.id(),k,signature)).hasMessage("PRICE_CHANGED");noReservation();
        publish("75000","SIMULATED",1,true);
        assertThat(f.service.submit(1,d.id(),k,signature).state()).isEqualTo("PREPARED");
    }
    @Test void samePriceNewTimestampAndUnchangedConnectionAllowsOriginalSignature(){
        String k=f.key();var d=draft(k);String signature=f.signature(d);f.now.set(f.now.get().plusSeconds(3));publish("75000","SIMULATED",1,true);
        var op=f.service.submit(1,d.id(),k,signature);
        assertThat(op.observedAt()).isEqualTo(1000); // NOT silently rebound to the new tick.
        assertThat(f.store.operation(op.id()).userSignature()).isEqualTo(signature);
    }
    @Test void generationProviderAndDisconnectedSourceRejectEvenIfSamePrice(){
        String k=f.key();var d=draft(k);String signature=f.signature(d);
        publish("75000","SIMULATED",2,true);assertThatThrownBy(()->f.service.submit(1,d.id(),k,signature)).hasMessage("MARKET_CONNECTION_CHANGED");
        publish("75000","OTHER",1,true);assertThatThrownBy(()->f.service.submit(1,d.id(),k,signature)).hasMessage("MARKET_CONNECTION_CHANGED");
        publish("75000","SIMULATED",1,false);assertThatThrownBy(()->f.service.submit(1,d.id(),k,signature)).hasMessage("MARKET_UNAVAILABLE");noReservation();
    }
    @Test void thirtySecondDraftExpiryIsSeparateAndMarketGateRunsFirst(){
        String k=f.key();var d=draft(k);String signature=f.signature(d);f.now.set(f.now.get().plusSeconds(30));publish("75000","SIMULATED",1,true);
        assertThatThrownBy(()->f.service.submit(1,d.id(),k,signature)).hasMessage("MARKET_UNAVAILABLE");
        assertThatThrownBy(()->f.store.accept(1,d.id(),k,signature,f.now.get())).hasMessage("AUTHORIZED_DRAFT_EXPIRED");noReservation();
    }
    @Test void tickChangesDuringGasEstimateRejectBeforeRawSigningAndReleaseUnsignedReservation()throws Exception{
        String k=f.key();var d=draft(k);var op=f.service.submit(1,d.id(),k,f.signature(d));
        when(f.rpc.estimate(anyString(),any(),any(),anyString(),anyString())).thenAnswer(x->{publish("75001","SIMULATED",1,true);return BigInteger.valueOf(500000);});
        var execution=new FuturesExecution(f.ledger,new AuthorizedIntentStore(f.context.getBean(javax.sql.DataSource.class),f.ledger,f.store),f.market,AuthorizedFuturesStoreTest.CHAIN,AuthorizedFuturesStoreTest.PRICE,f.txs,f.sender);
        assertThatThrownBy(()->execution.submitPrepared(op.id())).hasMessage("PRICE_CHANGED");
        assertThat(f.txs.find(op.id())).isEmpty();assertThat(f.ledger.operation(op.id()).state()).isEqualTo("REJECTED");
        assertThat(f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();verify(f.rpc,never()).broadcast(anyString());
    }
    @Test void successfulShortDelaySignsOneDurableRawButDoesNotClaimMining()throws Exception{
        String k=f.key();var d=draft(k);String signature=f.signature(d);f.now.set(f.now.get().plusSeconds(2));publish("75000","SIMULATED",1,true);
        var op=f.service.submit(1,d.id(),k,signature);
        var execution=new FuturesExecution(f.ledger,new AuthorizedIntentStore(f.context.getBean(javax.sql.DataSource.class),f.ledger,f.store),f.market,AuthorizedFuturesStoreTest.CHAIN,AuthorizedFuturesStoreTest.PRICE,f.txs,f.sender);
        execution.submitPrepared(op.id());var tx=f.txs.find(op.id()).orElseThrow();
        assertThat(tx.status()).isEqualTo("SIGNED"); // Fake transport response loss, no canonical receipt.
        assertThat(tx.report().observedAt()).isEqualTo(BigInteger.valueOf(1000));assertThat(tx.report().validUntil()).isEqualTo(BigInteger.valueOf(1030));
        assertThat(tx.authorization().userSignature()).isEqualTo(signature);verify(f.rpc,times(1)).broadcast(tx.raw());
    }
    @Test void unusableFiveSecondDraftStillBlocksReplacementUntilThirtySecondExpiry(){
        String k=f.key();var d=draft(k);String signature=f.signature(d);f.now.set(f.now.get().plusSeconds(6));publish("75001","SIMULATED",1,true);
        assertThatThrownBy(()->f.service.submit(1,d.id(),k,signature)).hasMessage("MARKET_UNAVAILABLE");
        assertThatThrownBy(()->draft(f.key())).hasMessage("WALLET_DRAFT_PENDING");noReservation();
        f.now.set(Instant.ofEpochSecond(1031));publish("75001","SIMULATED",1,true);
        String nextKey=f.key();var next=draft(nextKey);assertThat(next.id()).isNotEqualTo(d.id());
        assertThatThrownBy(()->f.service.submit(1,next.id(),nextKey,signature)).hasMessage("USER_SIGNATURE_INVALID");noReservation();
    }
    @Test void fixedMarginLeverageAndQuantityCannotTrackVariableOpenFillPrice(){
        var d=draft(f.key());var r=f.store.draft(1,d.id(),false).report();
        var changedPrice=new BigDecimal("75001").movePointRight(8).toBigIntegerExact();
        var changedQuantity=com.pricetrack.exchange.futures.math.FuturesMath.quantity(r.collateralAfter(),r.leverage(),changedPrice);
        assertThat(changedQuantity).isNotEqualTo(r.quantity());
        assertThat(r.entryPriceE8()).isEqualTo(r.priceE8());
    }
    @Test void laterStalePriceDoesNotCancelOrResignAnAlreadyDurableRaw()throws Exception{
        String k=f.key();var d=draft(k);var op=f.service.submit(1,d.id(),k,f.signature(d));
        var execution=new FuturesExecution(f.ledger,new AuthorizedIntentStore(f.context.getBean(javax.sql.DataSource.class),f.ledger,f.store),f.market,AuthorizedFuturesStoreTest.CHAIN,AuthorizedFuturesStoreTest.PRICE,f.txs,f.sender);
        execution.submitPrepared(op.id());var original=f.txs.find(op.id()).orElseThrow();
        f.now.set(Instant.ofEpochSecond(1031));publish("70000","SIMULATED",2,false);execution.submitPrepared(op.id());
        var recovered=f.txs.find(op.id()).orElseThrow();assertThat(recovered.raw()).isEqualTo(original.raw());assertThat(recovered.hash()).isEqualTo(original.hash());assertThat(recovered.nonce()).isEqualTo(original.nonce());
        assertThat(recovered.status()).isEqualTo("SIGNED");verify(f.rpc,times(2)).broadcast(original.raw());verify(f.rpc,times(1)).pendingNonce(anyString());
        assertThat(f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isEqualByComparingTo("100000");
    }
}
