package com.pricetrack.exchange.futures.audit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.pricetrack.exchange.futures.audit.FuturesAuditModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.reserve.ReserveModels.DbCut;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.web3j.protocol.core.methods.response.EthBlock;

class FuturesAuditServiceTest {
    final FuturesAuditStore store=mock(FuturesAuditStore.class);
    final FuturesRpcGateway rpc=mock(FuturesRpcGateway.class);
    final FuturesChainIdentity identity=new FuturesChainIdentity(BigInteger.valueOf(31337),"0x"+"1".repeat(40),"0x"+"2".repeat(40),"0x"+"3".repeat(40));
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    FuturesAuditService service;
    FuturesAuditStore.Cut cut(FuturesAuditSnapshot s){return new FuturesAuditStore.Cut(s,new DbCut(Instant.EPOCH,List.of(),List.of(),List.of(),List.of(),List.of(),FuturesAuditAccounting.impact(s,json)));}
    @BeforeEach void setup()throws Exception {
        service=new FuturesAuditService(store,identity,rpc,"new-execution","codehash");
        var base=new Baseline("base","new-execution",identity,"codehash",FuturesLedger.POLICY_HASH,FuturesLedger.INITIAL_CAPITAL.toString(),new Block(1,"b1"),Instant.EPOCH,"db",1);
        when(store.baseline()).thenReturn(Optional.of(base));when(store.cut()).thenReturn(cut(FuturesAuditAccountingTest.empty()));
        when(store.fingerprint(any())).thenReturn("db");when(store.save(any())).thenAnswer(i->i.getArgument(0));
        when(rpc.height()).thenReturn(BigInteger.TWO);
        for(int n=0;n<=2;n++){var b=new EthBlock.Block();b.setNumber("0x"+Integer.toHexString(n));b.setHash("b"+n);when(rpc.block(BigInteger.valueOf(n))).thenReturn(b);when(rpc.engineTransactions(BigInteger.valueOf(n))).thenReturn(List.of());}
        when(rpc.logs(any(),any())).thenReturn(List.of());
    }
    @AfterEach void stop(){service.close();}
    @Test void completeEmptyEngineHasExplicitZeroCoverageAndMatch(){var r=service.run(1);assertThat(r.status()).isEqualTo("MATCH");assertThat(r.coverage().complete()).isTrue();assertThat(r.coverage().transactions()).isZero();assertThat(r.limitation()).contains("not ERC-20 escrow");}
    @Test void missingBaselineDoesNotQueryRpc(){when(store.baseline()).thenReturn(Optional.empty());assertThat(service.run(1).reason()).isEqualTo("BASELINE_REQUIRED");verifyNoInteractions(rpc);}
    @Test void movingDbCutNeverPublishesMismatch(){when(store.fingerprint(any())).thenReturn("first","second");var r=service.run(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.reason()).isEqualTo("CUT_CHANGED");assertThat(r.coverage().complete()).isFalse();}
    @Test void movingChainCutIsInconclusive()throws Exception {when(rpc.height()).thenReturn(BigInteger.TWO,BigInteger.ONE);assertThat(service.run(1).reason()).isEqualTo("CUT_CHANGED");}
    @Test void houseConflictIsMismatchOnlyWithCompleteCut(){var s=FuturesAuditAccountingTest.empty();when(store.cut()).thenReturn(cut(new FuturesAuditSnapshot(s.policy(),s.initial(),s.house().subtract(BigInteger.ONE),s.reserved(),s.positions(),s.operations(),s.transactions(),s.journal(),s.guards())));var r=service.run(1);assertThat(r.status()).isEqualTo("MISMATCH");assertThat(r.coverage().complete()).isTrue();assertThat(r.dbMismatchCount()).isPositive();}
    @Test void missingHistoricalRpcIsNotFullMatch()throws Exception {when(rpc.engineTransactions(BigInteger.TWO)).thenThrow(new IOException("private-rpc-secret"));var r=service.run(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.coverage().complete()).isFalse();assertThat(json.writeValueAsString(r)).doesNotContain("private-rpc-secret","rawTransaction","signature");}
    @Test void reverseScanFindsRevertedEngineCallWithoutEvents()throws Exception {var tx=new org.web3j.protocol.core.methods.response.Transaction();tx.setHash("orphan");when(rpc.engineTransactions(BigInteger.TWO)).thenReturn(List.of(tx));var r=service.run(1);assertThat(r.status()).isEqualTo("MISMATCH");assertThat(r.checks()).anyMatch(c->c.name().equals("FUTURES_TRANSACTION_COVERAGE")&&!c.matches());}
    @Test void pendingIsNotCorruptionAndDoesNotRecoverOrRelease()throws Exception {var s=FuturesAuditAccountingTest.empty();var pending=new FuturesAuditSnapshot(s.policy(),s.initial(),s.house(),s.reserved(),List.of(FuturesAuditAccountingTest.position("OPEN_PENDING",0,"o")),List.of(FuturesAuditAccountingTest.open("PREPARED")),List.of(),List.of(),List.of());when(store.cut()).thenReturn(cut(pending));assertThat(service.run(1).reason()).isEqualTo("UNSETTLED_OR_REVIEW");verify(rpc,never()).broadcast(any());verify(rpc,never()).pendingNonce(any());}
    @Test void boundedRangeIsNotComplete()throws Exception {when(rpc.height()).thenReturn(BigInteger.valueOf(300));var b=new EthBlock.Block();b.setHash("b300");when(rpc.block(BigInteger.valueOf(300))).thenReturn(b);var r=service.run(1);assertThat(r.reason()).isEqualTo("BOUNDED_READ_LIMIT");assertThat(r.coverage().complete()).isFalse();}
    @Test void baselineCannotBeReset(){assertThatThrownBy(()->service.baseline(1)).hasMessageContaining("FUTURES_BASELINE_NOT_CREATABLE");verify(store,never()).create(any());}
}
