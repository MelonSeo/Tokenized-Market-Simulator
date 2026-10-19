package com.pricetrack.exchange.futures.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.web3j.protocol.core.methods.response.EthBlock;

class FuturesWorkerTest {
    final FuturesLedger ledger=mock(FuturesLedger.class);final FuturesIntentStore intents=mock(FuturesIntentStore.class);
    final FuturesMarketGate market=mock(FuturesMarketGate.class);final FuturesRpcGateway rpc=mock(FuturesRpcGateway.class);
    final FuturesReconciler reconciler=mock(FuturesReconciler.class);final FuturesExecution execution=mock(FuturesExecution.class);
    final FuturesTransactionStore transactions=mock(FuturesTransactionStore.class);
    final FuturesWorker worker=new FuturesWorker(ledger,intents,market,rpc,reconciler,execution,transactions);
    @AfterEach void close(){worker.close();}
    Position p(){return new Position("position",1,"a",Side.LONG,10,new BigInteger("13333333333333333333"),new BigInteger("7500000000000"),BigInteger.TEN.pow(23),"OPEN","HEALTHY",1,0,0,1000,null,null,null,null,Instant.EPOCH);}
    @Test void marketUnavailableBlocksOnlyNewRiskButSignedRecoveryStillRuns() {
        when(transactions.pending(50)).thenReturn(List.of("signed"));when(ledger.riskCandidates("",50)).thenReturn(List.of(p()));
        when(market.capture()).thenThrow(new FuturesLedger.Failure("MARKET_UNAVAILABLE"));worker.tick();
        verify(reconciler).reconcile("signed");verify(ledger).recordBlocked(1,"position",1,"MARKET_UNAVAILABLE");verify(intents,never()).prepare(anyLong(),anyString(),anyString(),any(),anyLong(),any(),any());
    }
    @Test void riskCreatesOneDurableIntentAndUsesExistingExecutionBoundary()throws Exception {
        var mark=new FuturesMarketGate.Mark(new BigInteger("6000000000000"),Instant.ofEpochSecond(1001),"SIMULATED",null);
        when(ledger.riskCandidates("",50)).thenReturn(List.of(p()));when(market.capture()).thenReturn(mark);when(market.now()).thenReturn(Instant.ofEpochSecond(1001));
        var block=new EthBlock.Block();block.setTimestamp("0x3e9");when(rpc.height()).thenReturn(BigInteger.ONE);when(rpc.block(BigInteger.ONE)).thenReturn(block);
        var op=mock(Operation.class);when(op.id()).thenReturn("call");when(intents.prepare(eq(1L),eq("position"),anyString(),eq(Action.START_CALL),eq(1L),eq(BigInteger.ZERO),eq(mark))).thenReturn(op);
        worker.tick();verify(execution).submitPrepared("call");verify(intents,times(1)).prepare(eq(1L),eq("position"),anyString(),eq(Action.START_CALL),eq(1L),eq(BigInteger.ZERO),eq(mark));
    }
    @Test void overlappingTicksCannotProcessTheSamePendingTransactionTwice()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(transactions.pending(50)).thenReturn(List.of("signed"));
        doAnswer(i->{entered.countDown();release.await(3,TimeUnit.SECONDS);return FuturesReconciler.Result.WAITING;}).when(reconciler).reconcile("signed");
        var pool=Executors.newSingleThreadExecutor();try {var task=pool.submit(worker::tick);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();worker.tick();release.countDown();task.get(3,TimeUnit.SECONDS);verify(reconciler,times(1)).reconcile("signed");}finally{release.countDown();pool.shutdownNow();}
    }
}
