package com.pricetrack.exchange.futures.chain;
import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

/** Canonical-shaped mock evidence only, not an RPC/Anvil claim. */
class AuthorizedV2ReceiptTest {
    final AuthorizedReceiptVerifierTest base=new AuthorizedReceiptVerifierTest();
    FuturesReceiptVerifierTest.Case fixture(){
        var old=base.authorized(Action.OPEN);var r=old.stored().report();var c=UserIntentV2CodecTest.C;var v=UserIntentV2CodecTest.vector();
        var i=new UserIntentV2Codec.Intent(v.owner(),c.executor(),r.operationId(),r.positionId(),r.accountRef(),r.symbolHash(),0,r.expectedVersion(),r.expectedCallEpoch(),r.side(),r.leverage(),BigInteger.ZERO,r.collateralBefore(),r.collateralAfter(),r.priceE8(),r.priceE8(),r.quantity(),r.quantity(),BigInteger.valueOf(3),BigInteger.valueOf(1000),BigInteger.valueOf(1120),r.policyHash());
        var a=new Authorization(c,i.owner(),null,UserIntentCodecTest.sign(UserIntentV2Codec.digest(c,i),UserIntentCodecTest.USER),i);
        var s=old.stored();var stored=new FuturesTransactionStore.Stored(s.operationId(),s.raw(),s.hash(),s.nonce(),s.status(),c.chainId(),c.engine(),c.executor(),r,s.signature(),a);
        old.tx().setInput(UserIntentCodec.encode(r,s.signature(),a));var l=old.receipt().getLogs().get(2);l.setTopics(List.of(FuturesReceiptVerifier.USER_EXECUTED_V2,Numeric.toHexString(UserIntentV2Codec.digest(c,i)),"0x"+"0".repeat(24)+i.owner().substring(2),r.positionId()));
        l.setData(base.encode(Numeric.toBigInt(r.operationId()),Numeric.toBigInt(Numeric.toHexString(FuturesReportCodec.digest(r,c.chainId(),c.rules()))),BigInteger.ZERO,i.nonce()));
        return new FuturesReceiptVerifierTest.Case(stored,old.p(),old.receipt(),old.tx(),old.block(),old.state());
    }
    @Test void v2ReceiptContainsActualReportAndIntentDigest(){assertThat(fixture().verify().version()).isEqualTo(1);}
    @Test void reportAndUserWindowAreCheckedAtMinedBlockNotCurrentWallClock(){
        var expired=fixture();expired.block().setTimestamp("0x407"); // 1031 > report validUntil 1030.
        assertThatThrownBy(expired::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
        var before=fixture();before.block().setTimestamp("0x3e7"); // 999 < user issuedAt 1000.
        assertThatThrownBy(before::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
    }
    @Test void mismatchedDigestsOperationOwnerCalldataEmitterOrderAndV1EventRejected(){
        for(int n=0;n<9;n++){var x=fixture();var logs=new ArrayList<>(x.receipt().getLogs());var l=logs.get(2);var data=l.getData();var topics=new ArrayList<>(l.getTopics());
            switch(n){case 0->l.setData("0x"+"ff".repeat(32)+data.substring(66));case 1->l.setData(data.substring(0,66)+"ff".repeat(32)+data.substring(130));case 2->topics.set(1,"0x"+"ff".repeat(32));case 3->topics.set(2,"0x"+"ff".repeat(32));case 4->l.setAddress(UserIntentV2CodecTest.C.rules());case 5->Collections.swap(logs,0,2);case 6->topics.set(0,FuturesReceiptVerifier.USER_CONSUMED);case 7->x.tx().setInput(FuturesReportCodec.encode(x.stored().report(),x.stored().signature()));case 8->l.setBlockHash("0x"+"ff".repeat(32));}
            l.setTopics(topics);x.receipt().setLogs(logs);assertThatThrownBy(x::verify).hasMessage("CANONICAL_EVENT_NOT_VERIFIABLE");
        }
    }
}
