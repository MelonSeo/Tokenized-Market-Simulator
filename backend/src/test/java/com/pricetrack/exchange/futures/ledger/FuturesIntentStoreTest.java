package com.pricetrack.exchange.futures.ledger;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import java.math.*;
import java.time.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real Spring transactions on H2; PostgreSQL lock behavior is tested separately. No RPC or forged receipt claims. */
class FuturesIntentStoreTest {
    @Configuration @Import(FuturesLedgerTest.Fixture.class) static class Config {
        @Bean FuturesIntentStore intents(DataSource ds,FuturesLedger ledger){return new FuturesIntentStore(ds,ledger);}
    }
    AnnotationConfigApplicationContext context;FuturesIntentStore intents;FuturesLedger ledger;JdbcTemplate jdbc;
    final Instant now=Instant.parse("2026-10-19T00:00:00Z");final BigInteger c=new BigInteger("100000000000000000000000");
    final FuturesMarketGate.Mark mark=new FuturesMarketGate.Mark(new BigInteger("7500000000000"),now,"SIMULATED",null);
    @BeforeEach void setup() {context=new AnnotationConfigApplicationContext(Config.class);intents=context.getBean(FuturesIntentStore.class);ledger=context.getBean(FuturesLedger.class);jdbc=new JdbcTemplate(context.getBean(DataSource.class));ledger.initializeEmptyFixture();for(long uid:new long[]{1,2}){jdbc.update("INSERT INTO users VALUES(?)",uid);jdbc.update("INSERT INTO user_balances VALUES(?,'mKRW',1000000,0)",uid);}}
    @AfterEach void close(){context.close();}
    FuturesIntentStore.Preview preview(){return intents.preview(1,Action.OPEN,null,0,Side.LONG,10,c,mark,now);}
    @Test void consumeAndReplayAreOneOperationAndOneReservationEvenAfterExpiry() {
        var p=preview();String key=UUID.randomUUID().toString();var op=intents.consume(1,p.id(),key,Action.OPEN,null,0,now);
        assertThat(intents.consume(1,p.id(),key,Action.OPEN,null,0,now.plusSeconds(100)).id()).isEqualTo(op.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isEqualByComparingTo("100000");
        assertThat(intents.evidence(op.id())).isEqualTo(mark);
    }
    @Test void crossUserAndCrossActionAndChangedKeyAreRejected() {
        var p=preview();String key=UUID.randomUUID().toString();
        assertThatThrownBy(()->intents.consume(2,p.id(),key,Action.OPEN,null,0,now)).hasMessage("PREVIEW_NOT_FOUND");
        assertThatThrownBy(()->intents.consume(1,p.id(),key,Action.CLOSE,"p",0,now)).hasMessage("PREVIEW_ACTION_CONFLICT");
        intents.consume(1,p.id(),key,Action.OPEN,null,0,now);
        assertThatThrownBy(()->intents.consume(1,p.id(),UUID.randomUUID().toString(),Action.OPEN,null,0,now)).hasMessage("PREVIEW_ALREADY_CONSUMED");
    }
    @Test void expiredPreviewAndPrepareFailureLeavePreviewUnconsumedAndNoLocks() {
        var p=preview();String key=UUID.randomUUID().toString();
        assertThatThrownBy(()->intents.consume(1,p.id(),key,Action.OPEN,null,0,now.plusSeconds(30))).hasMessage("PREVIEW_EXPIRED");
        jdbc.update("UPDATE user_balances SET locked_amount=999999 WHERE user_id=1");
        assertThatThrownBy(()->intents.consume(1,p.id(),key,Action.OPEN,null,0,now)).hasMessage("INSUFFICIENT_AVAILABLE_MARGIN");
        assertThat(intents.find(1,p.id(),false).operationId()).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_user_guards",Long.class)).isZero();
    }
    @Test void definitivePreviewRejectionSealsTheSameKeyAgainstDelayedExecution() {
        var p=preview();String key=UUID.randomUUID().toString();
        assertThat(intents.rejectPreview(1,p.id(),key,Action.OPEN,null,0)).isEmpty();
        assertThatThrownBy(()->intents.consume(1,p.id(),key,Action.OPEN,null,0,now)).hasMessage("FUTURES_REQUEST_REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();
        var fresh=preview();assertThat(intents.consume(1,fresh.id(),UUID.randomUUID().toString(),Action.OPEN,null,0,now).state()).isEqualTo("PREPARED");
    }
    @Test void definitiveRejectionCannotHideAnAlreadyAcceptedRequestOrReuseKeyWithDifferentContent() {
        var p=preview();String key=UUID.randomUUID().toString();var op=intents.consume(1,p.id(),key,Action.OPEN,null,0,now);
        assertThat(intents.rejectPreview(1,p.id(),key,Action.OPEN,null,0)).contains(op);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_request_rejections",Long.class)).isZero();
        var p2=preview();assertThatThrownBy(()->intents.rejectPreview(1,p2.id(),key,Action.OPEN,null,0)).hasMessage("IDEMPOTENCY_CONFLICT");
    }
}
