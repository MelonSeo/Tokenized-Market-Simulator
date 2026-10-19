package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.util.*;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;

/** Pure evidence validation. Caller must obtain explicit-block state and recheck canonical hash before settlement. */
public final class FuturesReceiptVerifier {
    public static final String APPLIED=Hash.sha3String("OperationApplied(bytes32,bytes32,uint8,uint256,uint256,uint256,int256,int256,uint256,uint256,uint256)");
    public static final String CONSUMED=Hash.sha3String("FuturesReportConsumed(bytes32,uint256,uint256)");
    private FuturesReceiptVerifier() {}
    public static void transaction(FuturesTransactionStore.Stored stored,TransactionReceipt receipt,Transaction tx,EthBlock.Block block) {
        quantity(receipt.getTransactionIndexRaw()); quantity(tx.getTransactionIndexRaw());
        quantity(receipt.getBlockNumberRaw()); quantity(tx.getBlockNumberRaw());
        if(!same(receipt.getTransactionHash(),stored.hash()) || !same(tx.getHash(),stored.hash())
            || !same(receipt.getFrom(),stored.sender()) || !same(tx.getFrom(),stored.sender())
            || !same(receipt.getTo(),stored.engine()) || !same(tx.getTo(),stored.engine())
            || !same(receipt.getBlockHash(),block.getHash()) || !same(tx.getBlockHash(),block.getHash())
            || !Objects.equals(receipt.getBlockNumber(),block.getNumber()) || !Objects.equals(tx.getBlockNumber(),block.getNumber())
            || !receipt.getTransactionIndex().equals(tx.getTransactionIndex())
            || !Objects.equals(tx.getNonce(),BigInteger.valueOf(stored.nonce())) || tx.getValue().signum()!=0
            || !Numeric.cleanHexPrefix(tx.getInput()).equalsIgnoreCase(Numeric.cleanHexPrefix(FuturesReportCodec.encode(stored.report(),stored.signature())))) bad();
    }
    public static ConfirmedEvent success(FuturesTransactionStore.Stored stored,Position p,TransactionReceipt receipt,
                                         Transaction tx,EthBlock.Block block,String state) {
        transaction(stored,receipt,tx,block);
        var r=stored.report();
        if(!"0x1".equalsIgnoreCase(receipt.getStatus()) || !Hash.sha3String(p.id()).equals(r.positionId())) bad();
        if(!stored.operationId().isEmpty() && !Hash.sha3String(stored.operationId()).equals(r.operationId())) bad();
        long time=block.getTimestamp().longValueExact(),obs=r.observedAt().longValueExact();
        if(obs>time+2 || time>r.validUntil().longValueExact() || !r.validUntil().equals(r.observedAt().add(BigInteger.valueOf(30)))) bad();
        var logs=receipt.getLogs();
        if(logs==null || logs.size()!=2) bad();
        Log applied=event(logs,stored,receipt,APPLIED,3),consumed=event(logs,stored,receipt,CONSUMED,2);
        if(logs.get(0)!=applied || logs.get(1)!=consumed || applied.getLogIndex().compareTo(consumed.getLogIndex())>=0) bad();
        if(!same(applied.getTopics().get(1),r.operationId()) || !same(applied.getTopics().get(2),r.positionId())
            || !same(consumed.getTopics().get(1),r.operationId())) bad();
        List<BigInteger> a=words(applied.getData(),9),c=words(consumed.getData(),2),s=words(state,11);
        var mark=FuturesMath.mark(p.side(),p.quantity(),p.entryPrice(),r.collateralAfter(),r.priceE8());
        Action action=Action.values()[r.action()]; boolean terminal=action==Action.CLOSE || action==Action.LIQUIDATE;
        long epoch=r.expectedCallEpoch().longValueExact()+(action==Action.START_CALL?1:0),deadline=p.deadline();
        boolean grace=p.riskStatus().equals("GRACE");
        if(action==Action.START_CALL) { grace=true;deadline=Math.addExact(time,60); }
        if(action==Action.CLEAR_CALL || action==Action.TOP_UP && !mark.atRisk()) { grace=false;deadline=0; }
        if(action==Action.LIQUIDATE && (time<p.deadline() || obs<p.deadline() || obs>time || !mark.atRisk())) bad();
        int status=terminal?(action==Action.CLOSE?3:4):(grace?2:1);
        BigInteger realized=terminal?mark.realized():BigInteger.ZERO,debt=terminal?mark.badDebt():BigInteger.ZERO;
        var expected=List.of(BigInteger.valueOf(r.action()),r.expectedVersion().add(BigInteger.ONE),r.priceE8(),r.collateralAfter(),
            mark.unrealized(),realized,debt,BigInteger.valueOf(epoch),BigInteger.valueOf(deadline));
        // ABI signed ints occupy full 256-bit two's-complement words.
        a.set(4,signed(a.get(4)));a.set(5,signed(a.get(5)));
        if(!a.equals(expected) || !c.equals(List.of(r.observedAt(),r.validUntil()))) bad();
        var expectedState=List.of(Numeric.toBigInt(r.accountRef()),BigInteger.valueOf(r.side()),BigInteger.valueOf(r.leverage()),
            r.quantity(),r.entryPriceE8(),r.collateralAfter(),BigInteger.valueOf(status),r.expectedVersion().add(BigInteger.ONE),
            BigInteger.valueOf(epoch),BigInteger.valueOf(deadline),r.observedAt());
        if(!s.equals(expectedState)) bad();
        return new ConfirmedEvent(stored.operationId(),stored.hash(),block.getNumber().longValueExact(),block.getHash(),time,
            r.expectedVersion().longValueExact()+1,epoch,deadline,r.priceE8(),r.collateralAfter(),realized,debt);
    }
    private static Log event(List<Log> logs,FuturesTransactionStore.Stored s,TransactionReceipt receipt,String topic,int count) {
        var found=logs.stream().filter(l->l.getTopics()!=null && !l.getTopics().isEmpty() && same(l.getTopics().get(0),topic)).toList();
        if(found.size()!=1) { bad(); }
        Log l=found.get(0);
        quantity(l.getBlockNumberRaw()); quantity(l.getTransactionIndexRaw()); quantity(l.getLogIndexRaw());
        if(l.isRemoved() || !same(l.getAddress(),s.engine()) || !same(l.getTransactionHash(),s.hash()) || l.getTopics().size()!=count
            || !same(l.getBlockHash(),receipt.getBlockHash()) || !l.getBlockNumber().equals(receipt.getBlockNumber())
            || !l.getTransactionIndex().equals(receipt.getTransactionIndex())) bad();
        return l;
    }
    private static List<BigInteger> words(String encoded,int count) {
        if(encoded==null || !encoded.matches("0x[0-9a-fA-F]{"+(count*64)+"}")) { bad(); }
        var list=new ArrayList<BigInteger>(); for(int i=0;i<count;i++) list.add(new BigInteger(encoded.substring(2+64*i,2+64*(i+1)),16)); return list;
    }
    private static BigInteger signed(BigInteger n) { return n.testBit(255)?n.subtract(BigInteger.ONE.shiftLeft(256)):n; }
    private static void quantity(String n) { if(n==null || !n.matches("0x[0-9a-fA-F]{1,64}")) bad(); }
    private static boolean same(String a,String b) { return a!=null && b!=null && a.equalsIgnoreCase(b); }
    private static void bad() { throw new Failure("CANONICAL_EVENT_NOT_VERIFIABLE"); }
}
