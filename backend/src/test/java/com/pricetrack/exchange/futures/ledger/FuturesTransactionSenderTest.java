package com.pricetrack.exchange.futures.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

/** Real local signing + H2 commit boundaries; Transport is a FAKE, not an Anvil receipt test. */
class FuturesTransactionSenderTest {
    static final Credentials EXECUTOR=Credentials.create(BigInteger.valueOf(442211).toString(16)); // public fixture only
    static final Credentials SIGNER=Credentials.create(BigInteger.valueOf(443311).toString(16)); // public fixture only
    static final FuturesChainIdentity CHAIN=new FuturesChainIdentity(BigInteger.valueOf(31337),"0x"+"12".repeat(20),EXECUTOR.getAddress(),SIGNER.getAddress());
    @Configuration static class Fixture extends FuturesLedgerTest.Fixture {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean FuturesTransactionStore store(DataSource ds,FuturesLedger ledger,ObjectMapper mapper) { return new FuturesTransactionStore(ds,ledger,mapper); }
    }
    AnnotationConfigApplicationContext context; FuturesLedger ledger; FuturesTransactionStore store; JdbcTemplate jdbc;
    Fake rpc; FuturesTransactionSender sender;
    class Fake implements FuturesTransactionSender.Transport {
        boolean estimateFails,loseResponse,wrongHash,known; int sends,nonceReads,verifications;
        List<String> raws=new ArrayList<>();
        public void verifyDeployment(FuturesChainIdentity c) { assertThat(c).isEqualTo(CHAIN); verifications++; }
        public BigInteger pendingNonce(String a) { nonceReads++; return BigInteger.valueOf(7); }
        public BigInteger gasPrice() { return BigInteger.ONE; }
        public BigInteger estimate(String a,BigInteger n,BigInteger g,String to,String data)throws IOException {
            if(estimateFails) throw new IOException("DO_NOT_EXPOSE_PROVIDER_BODY"); return BigInteger.valueOf(500000);
        }
        public boolean known(String hash) { return known; }
        public String broadcast(String raw)throws IOException {
            sends++; raws.add(raw);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_transactions WHERE status='SIGNED'",Long.class)).isEqualTo(1);
            if(loseResponse) throw new IOException("DO_NOT_EXPOSE_AUTHORIZATION");
            return wrongHash?"0x"+"ff".repeat(32):Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(raw)));
        }
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Fixture.class); ledger=context.getBean(FuturesLedger.class);
        store=context.getBean(FuturesTransactionStore.class); jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        ledger.initializeEmptyFixture(); jdbc.update("INSERT INTO users VALUES(1)");
        jdbc.update("INSERT INTO user_balances VALUES(1,'mKRW',1000000,0)");
        rpc=new Fake(); sender=new FuturesTransactionSender(CHAIN,EXECUTOR,store,rpc);
    }
    @AfterEach void close() { context.close(); }
    FuturesRecords.Operation open() { return ledger.prepareOpen(1,UUID.randomUUID().toString(),Side.LONG,FuturesLedgerTest.C,10,FuturesLedgerTest.ENTRY,1000); }
    FuturesReport report(FuturesRecords.Operation op) { return FuturesReport.from(op,ledger.position(op.positionId(),op.userId()),CHAIN.executor()); }
    String signature(FuturesReport r) { return FuturesReportCodec.sign(r,CHAIN.chainId(),CHAIN.engine(),SIGNER.getEcKeyPair()); }
    FuturesTransactionSender.Submission submit(FuturesRecords.Operation op) { var r=report(op); return sender.submit(op.id(),r,signature(r)); }

    @Test void signedCommitPrecedesBroadcastAndReplayDoesNotSendAgain() {
        var op=open(); var result=submit(op);
        assertThat(result.status()).isEqualTo("SUBMITTED"); assertThat(rpc.sends).isEqualTo(1);
        var stored=store.find(op.id()).orElseThrow();
        assertThat(stored.nonce()).isEqualTo(7); assertThat(stored.status()).isEqualTo("SUBMITTED");
        assertThat(stored.toString()).doesNotContain(stored.raw(),stored.signature());
        assertThat(submit(op).txHash()).isEqualTo(result.txHash()); assertThat(rpc.sends).isEqualTo(1);
        assertThat(ledger.operation(op.id()).state()).isEqualTo("SUBMITTED");
        assertThatThrownBy(()->ledger.rejectUnsigned(op.id(),"ERROR")).hasMessage("SIGNED_OR_NONPENDING_OPERATION");
    }
    @Test void responseLossRetainsSignedAndRecoveryUsesSameRawNonceHash() {
        var op=open(); rpc.loseResponse=true; var first=submit(op);
        assertThat(first.status()).isEqualTo("SIGNED");
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances",java.math.BigDecimal.class)).isEqualByComparingTo("100000");
        rpc.loseResponse=false; var second=sender.recover(op.id());
        assertThat(second.status()).isEqualTo("SUBMITTED"); assertThat(second.txHash()).isEqualTo(first.txHash());
        assertThat(rpc.raws.get(0)).isEqualTo(rpc.raws.get(1)); assertThat(rpc.nonceReads).isEqualTo(1);
    }
    @Test void alreadyKnownRecoveryDoesNotRebroadcast() {
        var op=open(); rpc.loseResponse=true; submit(op); rpc.known=true;
        assertThat(sender.recover(op.id()).status()).isEqualTo("SUBMITTED"); assertThat(rpc.sends).isEqualTo(1);
    }
    @Test void preSignFailureDoesNotPersistTransactionAndCanReleaseOnlyUnsignedReservation() {
        var op=open(); rpc.estimateFails=true;
        assertThatThrownBy(()->submit(op)).hasMessage("FUTURES_RPC_UNAVAILABLE");
        assertThat(store.find(op.id())).isEmpty(); assertThat(rpc.sends).isZero();
        ledger.rejectUnsigned(op.id(),"ESTIMATE_REJECTED");
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances",java.math.BigDecimal.class)).isZero();
    }
    @Test void wrongRpcHashDoesNotUnlockOrClaimSubmitted() {
        var op=open(); rpc.wrongHash=true;
        assertThat(submit(op).status()).isEqualTo("SIGNED");
        assertThat(ledger.operation(op.id()).state()).isEqualTo("SIGNED");
    }
    @Test void reportFromAnotherPreparedOperationCannotBeSignedIntoThisOne() {
        var op=open(); var r=report(op);
        var invalid=new FuturesReport(r.operationId(),r.positionId(),r.accountRef(),r.symbolHash(),r.action(),r.expectedVersion(),r.expectedCallEpoch(),r.side(),r.leverage(),r.quantity(),r.entryPriceE8(),r.collateralBefore(),r.collateralAfter().add(BigInteger.ONE),r.priceE8(),r.observedAt(),r.validUntil(),r.policyHash(),r.executor());
        assertThatThrownBy(()->sender.submit(op.id(),invalid,signature(invalid))).hasMessage("REPORT_NOT_AUTHORIZED");
        assertThat(rpc.sends).isZero(); assertThat(store.find(op.id())).isEmpty();
    }
    @Test void unresolvedExecutorBlocksOtherUsersWithoutConsumingNonceOrSending() {
        var op=open(); rpc.loseResponse=true; submit(op);
        jdbc.update("INSERT INTO users VALUES(2)"); jdbc.update("INSERT INTO user_balances VALUES(2,'mKRW',1000000,0)");
        var second=ledger.prepareOpen(2,UUID.randomUUID().toString(),Side.SHORT,FuturesLedgerTest.C,5,FuturesLedgerTest.ENTRY,1000);
        assertThatThrownBy(()->submit(second)).hasMessage("FUTURES_EXECUTOR_BUSY");
        assertThat(store.find(second.id())).isEmpty(); assertThat(rpc.nonceReads).isEqualTo(1); assertThat(rpc.sends).isEqualTo(1);
    }
    @Test void corruptedRawCannotBeRecoveredOrReplaced() {
        var op=open(); rpc.loseResponse=true; submit(op);
        jdbc.update("UPDATE futures_transactions SET raw_transaction='0x1234' WHERE operation_id=?",op.id());
        assertThatThrownBy(()->sender.recover(op.id())).hasMessage("SIGNED_RAW_NOT_VERIFIABLE");
        assertThat(rpc.sends).isEqualTo(1); assertThat(rpc.nonceReads).isEqualTo(1);
    }
    @Test void staleSubmittedWriteCannotRegressConfirmedState() {
        var op=open(); var s=submit(op);
        jdbc.update("UPDATE futures_transactions SET status='CONFIRMED' WHERE operation_id=?",op.id());
        jdbc.update("UPDATE futures_operations SET state='CONFIRMED' WHERE id=?",op.id());
        store.markSubmitted(op.id(),s.txHash());
        assertThat(store.find(op.id()).orElseThrow().status()).isEqualTo("CONFIRMED");
        assertThat(ledger.operation(op.id()).state()).isEqualTo("CONFIRMED");
    }
}
