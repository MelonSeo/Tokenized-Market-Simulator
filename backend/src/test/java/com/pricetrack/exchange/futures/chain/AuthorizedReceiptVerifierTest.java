package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.futures.authorization.UserIntentCodec;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;
import static com.pricetrack.exchange.futures.chain.FuturesReceiptVerifierTest.HASH;
import com.pricetrack.exchange.futures.chain.FuturesReceiptVerifierTest.Case;

/** Fabricated canonical-shaped receipt fixtures with real local signature checks, NOT RPC/Anvil. */
class AuthorizedReceiptVerifierTest {
    final FuturesReceiptVerifierTest fixtures=new FuturesReceiptVerifierTest();
    String encode(BigInteger... values){return fixtures.encode(values);}
    Case authorized(Action action){
        var old=fixtures.fixture(action,Side.LONG,action==Action.LIQUIDATE?60000:75000,action==Action.LIQUIDATE?"GRACE":"HEALTHY",action==Action.LIQUIDATE?1000:0,1000);
        var x=old.stored().report();var c=UserIntentCodecTest.C;String owner=UserIntentCodecTest.USER.getAddress();
        var r=new FuturesReport(x.operationId(),x.positionId(),UserIntentCodec.account(owner),x.symbolHash(),x.action(),x.expectedVersion(),x.expectedCallEpoch(),x.side(),x.leverage(),x.quantity(),x.entryPriceE8(),x.collateralBefore(),x.collateralAfter(),x.priceE8(),x.observedAt(),x.validUntil(),FuturesLedger.POLICY_HASH,c.engine());
        var a=UserIntentCodec.userAction(r.action())?new Approval(owner,BigInteger.valueOf(3),r.validUntil(),c.executor()):null;
        var auth=new Authorization(c,owner,a,a==null?null:UserIntentCodecTest.sign(UserIntentCodec.digest(c,r,a),UserIntentCodecTest.USER));
        String ps=FuturesReportCodec.sign(r,c.chainId(),c.rules(),UserIntentCodecTest.PRICE.getEcKeyPair());
        var stored=new FuturesTransactionStore.Stored("operation","0x00",HASH,7,"SUBMITTED",c.chainId(),c.engine(),c.executor(),r,ps,auth);
        var p=old.p();var position=new Position(p.id(),p.userId(),r.accountRef(),p.side(),p.leverage(),p.quantity(),p.entryPrice(),p.collateral(),p.status(),p.riskStatus(),p.version(),p.callEpoch(),p.deadline(),p.lastObservedAt(),p.pendingOperation(),null,null,null,p.createdAt());
        old.receipt().setFrom(c.executor());old.receipt().setTo(c.engine());old.tx().setFrom(c.executor());old.tx().setTo(c.engine());old.tx().setInput(UserIntentCodec.encode(r,ps,auth));
        var logs=new ArrayList<>(old.receipt().getLogs());for(var l:logs)l.setAddress(c.rules());
        if(a!=null){var l=fixtures.log(FuturesReceiptVerifier.USER_CONSUMED,List.of(Numeric.toHexString(UserIntentCodec.digest(c,r,a)),"0x"+"0".repeat(24)+owner.substring(2),r.positionId()),encode(BigInteger.valueOf(r.action()),a.nonce()),2);l.setAddress(c.engine());logs.add(l);}
        old.receipt().setLogs(logs);
        return new Case(stored,position,old.receipt(),old.tx(),old.block(),"0x"+Numeric.cleanHexPrefix(r.accountRef())+old.state().substring(66));
    }
    @Test void userThreeEventsAndAutomaticTwoEventsAccepted(){assertThat(authorized(Action.OPEN).verify().version()).isEqualTo(1);assertThat(authorized(Action.LIQUIDATE).verify().realized()).isNegative();}
    @Test void ownerDigestNonceEmitterOrderMissingAndCalldataMismatchBlockSettlement(){
        for(int n=0;n<9;n++){
            var c=authorized(Action.OPEN);var logs=new ArrayList<>(c.receipt().getLogs());var user=logs.get(2);
            switch(n){
                case 0->user.setAddress(UserIntentCodecTest.C.rules());
                case 1->{var topics=new ArrayList<>(user.getTopics());topics.set(1,"0x"+"ff".repeat(32));user.setTopics(topics);}
                case 2->{var topics=new ArrayList<>(user.getTopics());topics.set(2,"0x"+"ff".repeat(32));user.setTopics(topics);}
                case 3->user.setData(encode(BigInteger.ZERO,BigInteger.valueOf(4)));
                case 4->Collections.swap(logs,1,2);
                case 5->logs.remove(2);
                case 6->c.tx().setInput(FuturesReportCodec.encode(c.stored().report(),c.stored().signature()));
                case 7->logs.get(0).setAddress(UserIntentCodecTest.C.engine());
                case 8->user.setRemoved(true);
            }
            c.receipt().setLogs(logs);assertThatThrownBy(c::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
        }
    }
}
