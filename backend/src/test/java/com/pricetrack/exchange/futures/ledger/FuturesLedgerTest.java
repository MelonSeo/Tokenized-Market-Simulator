package com.pricetrack.exchange.futures.ledger;

import static com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import java.math.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.web3j.crypto.Hash;

/** H2 DB-only atomicity fixture. No RPC/receipt/Anvil claims; chain evidence below is deliberately synthetic. */
class FuturesLedgerTest {
    AnnotationConfigApplicationContext context;
    FuturesLedger ledger; JdbcTemplate jdbc;
    static final BigInteger C=new BigInteger("100000000000000000000000");
    static final BigInteger ENTRY=new BigInteger("7500000000000");
    @Configuration @EnableTransactionManagement static class Fixture {
        @Bean DataSource dataSource() {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:futures-ledger-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
            ds.setDriverClassName("org.h2.Driver"); var j=new JdbcTemplate(ds);
            j.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            j.execute("CREATE TABLE user_balances(user_id BIGINT,symbol VARCHAR(20),amount NUMERIC(30,18),locked_amount NUMERIC(30,18),PRIMARY KEY(user_id,symbol))");
            for(var table:new String[]{"faucet_grants","trades","orders"}) j.execute("CREATE TABLE "+table+"(id BIGINT PRIMARY KEY)");
            new ResourceDatabasePopulator(new ClassPathResource("futures-schema.sql")).execute(ds); return ds;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean Object futuresSchema() { return new Object(); }
        @Bean FuturesLedger futuresLedger(DataSource ds) { return new FuturesLedger(ds); }
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Fixture.class); ledger=context.getBean(FuturesLedger.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class)); ledger.initializeEmptyFixture();
        jdbc.update("INSERT INTO users VALUES(1)");
        jdbc.update("INSERT INTO user_balances VALUES(1,'mKRW',1000000,0)");
    }
    @AfterEach void close() { if(context!=null) context.close(); }
    String key() { return UUID.randomUUID().toString(); }
    Operation open() { return ledger.prepareOpen(1,key(),Side.LONG,C,10,ENTRY,1000); }
    void signed(Operation op) {
        jdbc.update("INSERT INTO futures_transactions(id,operation_id,sender,nonce,chain_id,contract_address,raw_transaction,tx_hash,status,created_at) VALUES(?,?,?,?,31337,?,'H2_NOT_A_SIGNED_TRANSACTION',?,'SIGNED',CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(),op.id(),"0x"+"11".repeat(20),jdbc.queryForObject("SELECT count(*) FROM futures_transactions",Long.class),"0x"+"22".repeat(20),Hash.sha3String(op.id()));
        jdbc.update("UPDATE futures_operations SET state='SIGNED' WHERE id=?",op.id());
    }
    ConfirmedEvent event(Operation op,long epoch,long deadline) { return new ConfirmedEvent(op.id(),Hash.sha3String(op.id()),1,"0x"+"33".repeat(32),op.action()==Action.START_CALL?deadline-60:op.observedAt(),op.expectedVersion()+1,epoch,deadline,op.price(),op.collateralAfter(),op.expectedRealized(),op.expectedBadDebt()); }
    void confirm(Operation op,long epoch,long deadline) { signed(op); ledger.settle(event(op,epoch,deadline)); }
    BigInteger wallet(String field) { return TokenUnits.toWei(jdbc.queryForObject("SELECT "+field+" FROM user_balances WHERE user_id=1",BigDecimal.class)); }
    BigInteger house() { return jdbc.queryForObject("SELECT amount FROM futures_house WHERE id=1",BigDecimal.class).toBigIntegerExact(); }

    @Test void reserveReplayRejectAndSameKeyDifferentRequest() {
        String k=key(); var op=ledger.prepareOpen(1,k,Side.LONG,C,10,ENTRY,1000);
        assertThat(wallet("amount")).isEqualTo(BigInteger.TEN.pow(24)); assertThat(wallet("locked_amount")).isEqualTo(C);
        assertThat(ledger.prepareOpen(1,k,Side.LONG,C,10,ENTRY,1000).id()).isEqualTo(op.id());
        assertThatThrownBy(()->ledger.prepareOpen(1,k,Side.SHORT,C,10,ENTRY,1000)).hasMessage("IDEMPOTENCY_CONFLICT");
        ledger.rejectUnsigned(op.id(),"QUOTE_CHANGED"); ledger.rejectUnsigned(op.id(),"QUOTE_CHANGED");
        assertThat(wallet("locked_amount")).isZero(); assertThat(ledger.position(op.positionId(),1).status()).isEqualTo("OPEN_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE key_hash=?",Long.class,FuturesLedger.fingerprint(k))).isEqualTo(1);
    }
    @Test void insufficientMarginRollsBackEveryPrepareWrite() {
        jdbc.update("UPDATE user_balances SET locked_amount=999999 WHERE user_id=1");
        assertThatThrownBy(this::open).hasMessage("INSUFFICIENT_AVAILABLE_MARGIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_positions",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_user_guards",Long.class)).isZero();
        assertThat(wallet("locked_amount")).isEqualTo(new BigInteger("999999000000000000000000"));
    }
    @Test void openCloseProfitHouseAndRepeatedSettlement() {
        var op=open(); confirm(op,0,0); var p=ledger.position(op.positionId(),1);
        var close=ledger.prepare(1,p.id(),key(),Action.CLOSE,p.version(),new BigInteger("8250000000000"),1010,BigInteger.ZERO);
        signed(close); var e=event(close,0,0); ledger.settle(e); ledger.settle(e);
        assertThat(wallet("amount")).isEqualTo(BigInteger.TEN.pow(24).add(close.expectedRealized()));
        assertThat(wallet("locked_amount")).isZero();
        assertThat(house()).isEqualTo(FuturesLedger.INITIAL_CAPITAL.subtract(close.expectedRealized()));
        assertThat(ledger.position(p.id(),1).status()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='CONFIRM'",Long.class,close.id())).isEqualTo(1);
    }
    @Test void callTopupRecoveryAndPendingExtraRelease() {
        var op=open(); confirm(op,0,0); var p=ledger.position(op.positionId(),1);
        var call=ledger.prepare(1,p.id(),key(),Action.START_CALL,p.version(),new BigInteger("7000000000000"),1001,BigInteger.ZERO);
        confirm(call,1,1061); p=ledger.position(p.id(),1);
        var topup=ledger.prepare(1,p.id(),key(),Action.TOP_UP,p.version(),new BigInteger("7000000000000"),1002,C);
        assertThat(wallet("locked_amount")).isEqualTo(C.multiply(BigInteger.TWO));
        ledger.rejectUnsigned(topup.id(),"PRICE_UNAVAILABLE"); assertThat(wallet("locked_amount")).isEqualTo(C);
        topup=ledger.prepare(1,p.id(),key(),Action.TOP_UP,p.version(),new BigInteger("7000000000000"),1003,C);
        confirm(topup,1,0); p=ledger.position(p.id(),1);
        assertThat(p.collateral()).isEqualTo(C.multiply(BigInteger.TWO)); assertThat(p.riskStatus()).isEqualTo("HEALTHY");
        assertThat(p.deadline()).isZero(); assertThat(wallet("amount")).isEqualTo(BigInteger.TEN.pow(24));
    }
    @Test void gapLiquidationCapsUserLossAndNoDoubleBadDebtDebit() {
        var op=open(); confirm(op,0,0); var p=ledger.position(op.positionId(),1);
        var call=ledger.prepare(1,p.id(),key(),Action.START_CALL,p.version(),new BigInteger("6000000000000"),1001,BigInteger.ZERO);
        confirm(call,1,1061); p=ledger.position(p.id(),1);
        var liq=ledger.prepare(1,p.id(),key(),Action.LIQUIDATE,p.version(),new BigInteger("6000000000000"),1061,BigInteger.ZERO);
        confirm(liq,1,1061);
        assertThat(wallet("amount")).isEqualTo(BigInteger.TEN.pow(24).subtract(C)); assertThat(wallet("locked_amount")).isZero();
        assertThat(house()).isEqualTo(FuturesLedger.INITIAL_CAPITAL.add(C));
        assertThat(ledger.position(p.id(),1).badDebt()).isPositive();
    }
    @Test void signedNeverReleasedAndMalformedEventRollsBack() {
        var op=open(); signed(op);
        assertThatThrownBy(()->ledger.rejectUnsigned(op.id(),"TIMEOUT")).hasMessage("SIGNED_OR_NONPENDING_OPERATION");
        var e=event(op,0,0); var bad=new ConfirmedEvent(e.operationId(),e.txHash(),1,e.blockHash(),1000,1,0,0,e.price(),e.collateral(),BigInteger.ONE,BigInteger.ZERO);
        assertThatThrownBy(()->ledger.settle(bad)).hasMessage("EVENT_NOT_VERIFIABLE");
        assertThat(wallet("locked_amount")).isEqualTo(C); assertThat(ledger.position(op.positionId(),1).status()).isEqualTo("OPEN_PENDING");
    }
    @Test void poolInsufficientFailsWithoutChangingLocksOrPosition() {
        var op=open(); confirm(op,0,0); var p=ledger.position(op.positionId(),1);
        assertThatThrownBy(()->ledger.prepare(1,p.id(),key(),Action.CLOSE,p.version(),new BigInteger("100000000000000000"),1002,BigInteger.ZERO)).hasMessage("POOL_INSUFFICIENT");
        ledger.recordBlocked(1,p.id(),p.version(),"POOL_INSUFFICIENT");
        assertThat(ledger.position(p.id(),1).blockReason()).isEqualTo("POOL_INSUFFICIENT");
        assertThat(ledger.position(p.id(),1).pendingOperation()).isNull(); assertThat(wallet("locked_amount")).isEqualTo(C);
    }
    @Test void policyMismatchAndOwnershipFailClosed() {
        var op=open(); assertThatThrownBy(()->ledger.position(op.positionId(),2)).hasMessage("POSITION_NOT_FOUND");
        ledger.rejectUnsigned(op.id(),"TEST"); jdbc.update("UPDATE futures_policy SET policy_hash='wrong'");
        assertThatThrownBy(this::open).hasMessage("FUTURES_POLICY_NOT_INITIALIZED");
    }
}
