package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

/** Deliberate receipt/state fixtures, NOT network, mined transactions or real signatures. */
class FuturesReceiptVerifierTest {
    static final BigInteger C=BigInteger.TEN.pow(23),ENTRY=BigInteger.valueOf(75000).multiply(BigInteger.TEN.pow(8));
    static final String ENGINE="0x"+"12".repeat(20),SENDER="0x"+"34".repeat(20),HASH="0x"+"56".repeat(32),BLOCK="0x"+"78".repeat(32);
    record Case(FuturesTransactionStore.Stored stored,Position p,TransactionReceipt receipt,Transaction tx,EthBlock.Block block,String state) {
        ConfirmedEvent verify() { return FuturesReceiptVerifier.success(stored,p,receipt,tx,block,state); }
    }
    Case fixture(Action action,Side side,int price,String risk,long deadline,long time) {
        BigInteger q=FuturesMath.quantity(C,10,ENTRY),markPrice=BigInteger.valueOf(price).multiply(BigInteger.TEN.pow(8));
        long version=action==Action.OPEN?0:1,epoch=risk.equals("GRACE")?1:0;
        var p=new Position("position",1,Hash.sha3String("account"),side,10,q,ENTRY,C,"OPEN",risk,version,epoch,deadline,990,"operation",null,null,null,Instant.EPOCH);
        var r=new FuturesReport(Hash.sha3String("operation"),Hash.sha3String("position"),p.accountRef(),FuturesReport.SYMBOL_HASH,action.ordinal(),BigInteger.valueOf(version),BigInteger.valueOf(epoch),side.ordinal(),10,q,ENTRY,action==Action.OPEN?BigInteger.ZERO:C,C,markPrice,BigInteger.valueOf(time),BigInteger.valueOf(time+30),Hash.sha3String("policy"),SENDER);
        String sig="0x"+"11".repeat(65);
        var stored=new FuturesTransactionStore.Stored("operation","0x00",HASH,7,"SUBMITTED",BigInteger.valueOf(31337),ENGINE,SENDER,r,sig);
        var mark=FuturesMath.mark(side,q,ENTRY,C,markPrice); boolean terminal=action==Action.CLOSE || action==Action.LIQUIDATE;
        long newEpoch=epoch+(action==Action.START_CALL?1:0),newDeadline=action==Action.START_CALL?time+60:action==Action.CLEAR_CALL?0:deadline;
        int status=terminal?(action==Action.CLOSE?3:4):(newDeadline>0?2:1);
        Log applied=log(FuturesReceiptVerifier.APPLIED,List.of(r.operationId(),r.positionId()),encode(BigInteger.valueOf(action.ordinal()),BigInteger.valueOf(version+1),markPrice,C,mark.unrealized(),terminal?mark.realized():BigInteger.ZERO,terminal?mark.badDebt():BigInteger.ZERO,BigInteger.valueOf(newEpoch),BigInteger.valueOf(newDeadline)),0);
        Log consumed=log(FuturesReceiptVerifier.CONSUMED,List.of(r.operationId()),encode(r.observedAt(),r.validUntil()),1);
        var receipt=new TransactionReceipt();receipt.setTransactionHash(HASH);receipt.setBlockHash(BLOCK);receipt.setBlockNumber("0x7");
        receipt.setFrom(SENDER);receipt.setTo(ENGINE);receipt.setStatus("0x1");receipt.setLogs(List.of(applied,consumed));receipt.setTransactionIndex("0x0");
        var tx=new Transaction();tx.setHash(HASH);tx.setBlockHash(BLOCK);tx.setBlockNumber("0x7");tx.setFrom(SENDER);tx.setTo(ENGINE);tx.setNonce("0x7");tx.setValue("0x0");tx.setInput(FuturesReportCodec.encode(r,sig));
        tx.setTransactionIndex("0x0");
        var block=new EthBlock.Block();block.setHash(BLOCK);block.setNumber("0x7");block.setTimestamp(Numeric.encodeQuantity(BigInteger.valueOf(time)));
        String state=encode(Numeric.toBigInt(p.accountRef()),BigInteger.valueOf(side.ordinal()),BigInteger.TEN,q,ENTRY,C,BigInteger.valueOf(status),BigInteger.valueOf(version+1),BigInteger.valueOf(newEpoch),BigInteger.valueOf(newDeadline),r.observedAt());
        return new Case(stored,p,receipt,tx,block,state);
    }
    Log log(String topic,List<String> indexed,String data,int index) {
        var l=new Log();var topics=new ArrayList<String>();topics.add(topic);topics.addAll(indexed);
        l.setTopics(topics);l.setAddress(ENGINE);l.setData(data);l.setTransactionHash(HASH);l.setBlockHash(BLOCK);l.setBlockNumber("0x7");l.setLogIndex("0x"+index);l.setTransactionIndex("0x0");return l;
    }
    String encode(BigInteger... values) { var s=new StringBuilder("0x");for(var v:values) s.append(Numeric.toHexStringNoPrefixZeroPadded(v.signum()<0?v.add(BigInteger.ONE.shiftLeft(256)):v,64));return s.toString(); }
    @Test void exactOpenAndNegativePnlAndBothLiquidations() {
        assertThat(fixture(Action.OPEN,Side.LONG,75000,"HEALTHY",0,1000).verify().version()).isEqualTo(1);
        assertThat(fixture(Action.CLOSE,Side.LONG,74000,"HEALTHY",0,1000).verify().realized()).isNegative();
        for(var side:Side.values()) assertThat(fixture(Action.LIQUIDATE,side,side==Side.LONG?60000:90000,"GRACE",1000,1000).verify().badDebt()).isPositive();
    }
    @Test void changedTransactionAndNonCanonicalBlockAreRejected() {
        var c=fixture(Action.OPEN,Side.LONG,75000,"HEALTHY",0,1000); c.tx().setNonce("0x8");assertThatThrownBy(c::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
        var other=fixture(Action.OPEN,Side.LONG,75000,"HEALTHY",0,1000);other.block().setHash("0x"+"ab".repeat(32));assertThatThrownBy(other::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
    }
    @Test void removedWrongAddressOrDuplicateEventAreRejected() {
        for(int option=0;option<3;option++) {
            var c=fixture(Action.OPEN,Side.LONG,75000,"HEALTHY",0,1000);var log=c.receipt().getLogs().get(0);
            if(option==0)log.setRemoved(true);if(option==1)log.setAddress(SENDER);if(option==2)c.receipt().setLogs(List.of(log,log));
            assertThatThrownBy(c::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
        }
    }
    @Test void explicitBlockStateMustMatchAndGraceCannotUsePreDeadlineMark() {
        var c=fixture(Action.OPEN,Side.LONG,75000,"HEALTHY",0,1000);
        var mismatch=new Case(c.stored(),c.p(),c.receipt(),c.tx(),c.block(),c.state().substring(0,c.state().length()-1)+"0");
        assertThatThrownBy(mismatch::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
        var early=fixture(Action.LIQUIDATE,Side.LONG,60000,"GRACE",1001,1000);assertThatThrownBy(early::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
    }
    @Test void callDeadlineIsReceiptBlockTimePlusSixtyAndFailedStatusIsNotSuccess() {
        var call=fixture(Action.START_CALL,Side.LONG,70000,"HEALTHY",0,1000);assertThat(call.verify().deadline()).isEqualTo(1060);
        var fail=fixture(Action.OPEN,Side.LONG,75000,"HEALTHY",0,1000);fail.receipt().setStatus("0x0");assertThatThrownBy(fail::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
    }
    @Test void everyLogMustHaveMatchingCanonicalBlockTransactionAndOrderedUniqueIndex() {
        for(int option=0;option<10;option++) {
            var c=fixture(Action.OPEN,Side.LONG,75000,"HEALTHY",0,1000);var l=c.receipt().getLogs().get(0);
            switch(option) {
                case 0->l.setBlockHash("0x"+"ff".repeat(32));case 1->l.setBlockNumber("0x8");
                case 2->l.setTransactionIndex("0x1");case 3->l.setLogIndex("0x1");case 4->l.setLogIndex(null);
                case 5->l.setBlockNumber(null);case 6->l.setTransactionIndex(null);case 7->c.receipt().setTransactionIndex(null);
                case 8->c.tx().setTransactionIndex("0x1");case 9->c.receipt().setLogs(List.of(c.receipt().getLogs().get(1),l));
            }
            assertThatThrownBy(c::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
        }
    }
}
