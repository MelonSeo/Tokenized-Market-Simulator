package com.pricetrack.exchange.custody.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Mock RPC observations, not actual chain, wallet consent, PG admission or executable Report proof. */
public class CAdmissionPrestateTest {
    static final long NOW=1_792_713_600L;
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static final class Fixture implements CReadRpc {
        final CCustodyRpcReaderTest.Fixture base=new CCustodyRpcReaderTest.Fixture();
        final CCustodyIdentity id=base.id;
        final String owner=base.owner,op=CCustodyIdentityTest.hash(76),pos=base.position;
        final Map<String,String> override=new HashMap<>();
        boolean open=true,paused,usedRules,usedVault,foreign,terminal,badLock,badPosition,changedHeader,malformed;
        long blockTime=NOW;BigInteger nonce=b(3),free=b(2000),locked=BigInteger.ZERO,unpaid=BigInteger.ZERO;
        BigInteger version=b(2),epoch=BigInteger.ONE,quantity=b(100),entry=b(100),collateral=b(1000);
        int status=1,calls;
        Intent intent(int action){return intent(action,b(action==0?0:100),b(action==0?0:1000),b(action==1?1200:1000),b(3),b(NOW-1),b(NOW+119));}
        Intent intent(int action,BigInteger entryPrice,BigInteger before,BigInteger after,BigInteger n,BigInteger issued,BigInteger deadline){
            return new Intent(owner,id.relayer(),op,pos,CReadAbi.accountRef(owner,id.symbolHash()),id.symbolHash(),b(action),
                b(action==0?0:2),b(action==0?0:1),BigInteger.ZERO,BigInteger.TEN,entryPrice,before,after,b(90),b(110),
                b(action==0?90:100),b(action==0?110:100),n,issued,deadline,id.policyHash());
        }
        CCustodyRpcReader reader(){return reader(Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC));}
        CCustodyRpcReader reader(Clock clock){return new CCustodyRpcReader(id,this,clock);}
        void existing(){open=false;locked=b(1000);}
        void set(String signature,String raw){override.put(CReadAbi.selector(signature),raw);}
        public JsonNode request(String method,List<Object> params,long timeout){
            calls++;assertTrue(timeout>0&&timeout<=3000);
            if(method.equals("eth_getBlockByNumber")&&!params.get(0).equals("0x0")){
                JsonNode h=base.request(method,params,timeout).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode)h).put("timestamp","0x"+Long.toHexString(blockTime));
                if(changedHeader&&!params.get(0).equals("latest"))((com.fasterxml.jackson.databind.node.ObjectNode)h).put("hash",CCustodyIdentityTest.hash(999));
                return h;
            }
            if(!method.equals("eth_call"))return base.request(method,params,timeout);
            assertEquals(Map.of("blockHash",base.block,"requireCanonical",true),params.get(1));
            @SuppressWarnings("unchecked")Map<String,String> call=(Map<String,String>)params.get(0);
            String target=call.get("to"),data=call.get("data"),selector=data.substring(0,10);
            if(override.containsKey(selector))return TextNode.valueOf(override.get(selector));
            String value=null;
            if(selector.equals(CReadAbi.selector("nonces(address)"))){assertEquals(id.controller().address(),target);assertEquals(CReadAbi.addressWord(owner),data.substring(10));value=w(nonce);}
            if(selector.equals(CReadAbi.selector("usedOperations(bytes32)"))){assertEquals(op.substring(2),data.substring(10));assertTrue(Set.of(id.rules().address(),id.vault().address()).contains(target));value=w(target.equals(id.rules().address())?usedRules?1:0:usedVault?1:0);}
            if(selector.equals(CReadAbi.selector("newPositionsPaused()"))){assertEquals(id.controller().address(),target);value=w(paused?1:0);}
            if(selector.equals(CReadAbi.selector("activePosition(bytes32)"))){assertEquals(id.rules().address(),target);assertEquals(CReadAbi.accountRef(owner,id.symbolHash()).substring(2),data.substring(10));value=open?CReadAbi.ZERO:pos;}
            if(selector.equals(CReadAbi.selector("positionOwner(bytes32)"))){assertEquals(id.controller().address(),target);assertEquals(pos.substring(2),data.substring(10));value=aw(open?CCustodyIdentityTest.address(0):foreign?CCustodyIdentityTest.address(98):owner);}
            if(selector.equals(CReadAbi.selector("getPosition(bytes32)"))){
                assertEquals(id.rules().address(),target);assertEquals(pos.substring(2),data.substring(10));
                value=malformed?w(0):open?"0x"+"0".repeat(11*64):"0x"+CReadAbi.accountRef(owner,id.symbolHash()).substring(2)
                    +CReadAbi.word(BigInteger.ZERO)+CReadAbi.word(BigInteger.TEN)+CReadAbi.word(quantity)+CReadAbi.word(entry)+CReadAbi.word(collateral)
                    +CReadAbi.word(b(status))+CReadAbi.word(badPosition?version.add(BigInteger.ONE):version)+CReadAbi.word(epoch)
                    +CReadAbi.word(status==2?b(NOW+60):BigInteger.ZERO)+CReadAbi.word(b(NOW-10));
            }
            if(selector.equals(CReadAbi.selector("positionLocks(bytes32)"))){assertEquals(id.vault().address(),target);assertEquals(pos.substring(2),data.substring(10));value="0x"+(open?"0".repeat(64):CReadAbi.addressWord(owner))+CReadAbi.word(open?BigInteger.ZERO:badLock?collateral.add(BigInteger.ONE):collateral)+CReadAbi.word(terminal?BigInteger.ONE:BigInteger.ZERO);}
            if(selector.equals(CReadAbi.selector("freeBalance(address)"))){assertEquals(id.vault().address(),target);assertEquals(CReadAbi.addressWord(owner),data.substring(10));value=w(free);}
            if(selector.equals(CReadAbi.selector("lockedBalance(address)"))){assertEquals(id.vault().address(),target);assertEquals(CReadAbi.addressWord(owner),data.substring(10));value=w(locked);}
            if(selector.equals(CReadAbi.selector("totalUnpaidProfit()")))value=w(unpaid);
            if(selector.equals(CReadAbi.selector("totalFree()")))value=w(b(2000));
            if(selector.equals(CReadAbi.selector("totalLocked()")))value=w(open?BigInteger.ZERO:b(1000));
            if(selector.equals(CReadAbi.selector("houseBalance()")))value=w(b(100));
            if(selector.equals(CReadAbi.selector("recognizedAssets()")))value=w(open?2100:3100);
            if(selector.equals(CReadAbi.selector("balanceOf(address)"))){assertEquals(id.token().address(),target);assertEquals(CReadAbi.addressWord(id.vault().address()),data.substring(10));value=w(open?2100:3100);}
            return value==null?base.request(method,params,timeout):TextNode.valueOf(value);
        }
        static String w(long n){return w(b(n));}static String w(BigInteger n){return "0x"+CReadAbi.word(n);}static String aw(String a){return "0x"+"0".repeat(24)+a.substring(2);}
    }
    static void rejects(Fixture f,Intent i,String suffix){assertEquals("C_READ_"+suffix,assertThrows(CCustodyFailure.class,()->f.reader().admissionPrestate(i)).code());}

    @Test void openUsesOnePinnedContextAndReturnsExactIntentWithoutExecutionClaim(){
        var f=new Fixture();var i=f.intent(0);var out=f.reader().admissionPrestate(i);
        assertEquals(i,out.value().intent());assertEquals("3",out.value().userNonce());assertEquals("2000",out.value().free());
        assertEquals("NONE",out.value().position().status());assertEquals("0",out.value().locked());assertEquals("0",out.value().totalUnpaidProfit());
        assertEquals(f.base.block,out.block().hash());assertEquals(Long.toString(NOW),out.block().timestamp());
        assertFalse(out.completeCoverage());assertFalse(out.globalCanonicalVerified());assertTrue(f.calls<CCustodyRpcReader.MAX_CALLS);
        assertEquals(40,f.calls);
    }
    @Test void topUpAndCloseUseExistingPositionAndOnlyTopUpNeedsAdditionalFree(){
        var f=new Fixture();f.existing();f.status=2;f.paused=true;f.unpaid=b(900);f.free=b(200);
        var top=f.reader().admissionPrestate(f.intent(1));assertEquals("MARGIN_CALL",top.value().position().status());
        assertEquals("1000",top.value().position().lockedCollateral());assertTrue(top.value().newPositionsPaused());
        f.free=BigInteger.ZERO;assertEquals("100",f.reader().admissionPrestate(f.intent(4)).value().position().quantity());
        rejects(f,f.intent(1),"ADMISSION_TOP_UP_TERMS");
    }
    @Test void changedNonceOverflowAndBothOperationMapsAreRejected(){
        var f=new Fixture();f.nonce=b(4);rejects(f,f.intent(0),"ADMISSION_NONCE");
        f.nonce=CAbiModels.UINT_MAX;rejects(f,f.intent(0,BigInteger.ZERO,BigInteger.ZERO,b(1000),CAbiModels.UINT_MAX,b(NOW-1),b(NOW+119)),"ADMISSION_NONCE");
        f.nonce=b(3);f.usedRules=true;rejects(f,f.intent(0),"ADMISSION_USED_OPERATION");
        f.usedRules=false;f.usedVault=true;rejects(f,f.intent(0),"ADMISSION_USED_OPERATION");
    }
    @Test void ownerCodeAndDeploymentHeaderRemainRequired(){
        var f=new Fixture();f.base.ownerCode=true;assertEquals("C_EOA_REQUIRED",assertThrows(CCustodyFailure.class,()->f.reader().admissionPrestate(f.intent(0))).code());
        f.base.ownerCode=false;f.changedHeader=true;rejects(f,f.intent(0),"HEADER_CHANGED");
        f.changedHeader=false;f.base.changedCode=true;rejects(f,f.intent(0),"CODE_HASH");
    }
    @Test void intentWindowMustHoldForBlockAndLocalClockWithoutReportFutureAllowance(){
        var f=new Fixture();f.blockTime=NOW-2;rejects(f,f.intent(0),"ADMISSION_WINDOW");
        f.blockTime=NOW;rejects(f,f.intent(0,BigInteger.ZERO,BigInteger.ZERO,b(1000),b(3),b(NOW-121),b(NOW-1)),"ADMISSION_WINDOW");
        rejects(f,f.intent(0,BigInteger.ZERO,BigInteger.ZERO,b(1000),b(3),b(NOW-1),b(NOW+120)),"ADMISSION_WINDOW");
        assertThrows(CCustodyFailure.class,()->f.reader(Clock.fixed(Instant.ofEpochSecond(NOW-2),ZoneOffset.UTC)).admissionPrestate(f.intent(0)));
        var boundary=f.intent(0,BigInteger.ZERO,BigInteger.ZERO,b(1000),b(3),b(NOW-120),b(NOW));
        assertEquals(boundary,f.reader().admissionPrestate(boundary).value().intent());
    }
    @Test void intentExpiringDuringRpcDoesNotReturnSuccessfulObservation(){
        var f=new Fixture();var count=new AtomicInteger();Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochSecond(count.getAndIncrement()==0?NOW:NOW+120);}};
        assertThrows(CCustodyFailure.class,()->f.reader(clock).admissionPrestate(f.intent(0)));
    }
    @Test void openPauseUnpaidActiveOrNonemptyPositionAreRejected(){
        var f=new Fixture();f.paused=true;rejects(f,f.intent(0),"ADMISSION_OPEN_BLOCKED");
        f.paused=false;f.unpaid=BigInteger.ONE;rejects(f,f.intent(0),"ADMISSION_OPEN_BLOCKED");
        f.unpaid=BigInteger.ZERO;f.set("activePosition(bytes32)",f.pos);rejects(f,f.intent(0),"ADMISSION_OPEN_STATE");
        f.override.clear();f.set("getPosition(bytes32)","0x"+"0".repeat(10*64)+CReadAbi.word(BigInteger.ONE));rejects(f,f.intent(0),"ADMISSION_OPEN_STATE");
        f.override.clear();f.terminal=true;rejects(f,f.intent(0),"ADMISSION_OPEN_STATE");
    }
    @Test void openNeedsExactZeroTermsAndObservedFreeCollateral(){
        var f=new Fixture();f.free=b(999);rejects(f,f.intent(0),"ADMISSION_OPEN_TERMS");
        f.free=b(2000);rejects(f,f.intent(0,BigInteger.ONE,BigInteger.ZERO,b(1000),b(3),b(NOW-1),b(NOW+119)),"ADMISSION_OPEN_TERMS");
        rejects(f,f.intent(0,BigInteger.ZERO,BigInteger.ONE,b(1000),b(3),b(NOW-1),b(NOW+119)),"ADMISSION_OPEN_TERMS");
    }
    @Test void wrongOwnerTerminalLockAndExactPositionTermsAreRejected(){
        var f=new Fixture();f.existing();f.foreign=true;rejects(f,f.intent(1),"ADMISSION_POSITION_STATE");
        f.foreign=false;f.status=3;rejects(f,f.intent(4),"ADMISSION_POSITION_STATE");
        f.status=1;f.badLock=true;rejects(f,f.intent(1),"ADMISSION_POSITION_STATE");
        f.badLock=false;f.badPosition=true;rejects(f,f.intent(1),"ADMISSION_POSITION_TERMS");
        f.badPosition=false;f.epoch=b(2);rejects(f,f.intent(1),"ADMISSION_POSITION_TERMS");
        f.epoch=BigInteger.ONE;f.quantity=b(101);rejects(f,f.intent(1),"ADMISSION_POSITION_TERMS");
        f.quantity=b(100);f.entry=b(101);rejects(f,f.intent(1),"ADMISSION_POSITION_TERMS");
    }
    @Test void closeCannotIncreaseCollateralAndTopUpMustIncreaseIt(){
        var f=new Fixture();f.existing();
        rejects(f,f.intent(4,b(100),b(1000),b(1200),b(3),b(NOW-1),b(NOW+119)),"ADMISSION_CLOSE_TERMS");
        rejects(f,f.intent(1,b(100),b(1000),b(1000),b(3),b(NOW-1),b(NOW+119)),"ADMISSION_TOP_UP_TERMS");
    }
    @Test void malformedOrMissingRpcCannotProduceAdmission(){
        var f=new Fixture();f.malformed=true;assertThrows(CCustodyFailure.class,()->f.reader().admissionPrestate(f.intent(0)));
        f.malformed=false;f.base.fail=true;assertThrows(CCustodyFailure.class,()->f.reader().admissionPrestate(f.intent(0)));
        assertThrows(CCustodyFailure.class,()->f.reader().admissionPrestate(null));
    }
    @Test void assetDeficitAndCorruptAggregatesFailClosed(){
        var f=new Fixture();f.set("balanceOf(address)",Fixture.w(2099));rejects(f,f.intent(0),"ADMISSION_ASSET_CONSERVATION");
        f.override.clear();f.set("recognizedAssets()",Fixture.w(2101));rejects(f,f.intent(0),"ADMISSION_ASSET_CONSERVATION");
    }
    @Test void staticActionCollateralAndFiniteApprovedRangesAreNotExpanded(){
        var f=new Fixture();rejects(f,f.intent(2),"ADMISSION_ACTION");
        rejects(f,f.intent(0,BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN.pow(30),b(3),b(NOW-1),b(NOW+119)),"ADMISSION_TERMS");
        Intent i=f.intent(0);Intent zeroPrice=new Intent(i.owner(),i.relayer(),i.operationId(),i.positionId(),i.accountRef(),i.symbolHash(),
            i.action(),i.expectedVersion(),i.expectedCallEpoch(),i.side(),i.leverage(),i.entryPriceE8(),i.collateralBefore(),i.collateralAfter(),
            BigInteger.ZERO,i.priceMaxE8(),i.quantityMin(),i.quantityMax(),i.nonce(),i.issuedAt(),i.deadline(),i.policyHash());
        rejects(f,zeroPrice,"ADMISSION_TERMS");assertEquals(0,f.calls);
    }
    @Test void activePositionAndLockedAggregateCannotDisagreeWithTheSelectedPosition(){
        var f=new Fixture();f.existing();f.set("activePosition(bytes32)",CCustodyIdentityTest.hash(78));rejects(f,f.intent(1),"ADMISSION_POSITION_STATE");
        f.override.clear();f.locked=b(999);rejects(f,f.intent(1),"ADMISSION_POSITION_STATE");
        f.locked=b(1000);f.terminal=true;rejects(f,f.intent(4),"ADMISSION_POSITION_STATE");
    }
    @Test void snapshotTotalDeadlineStillBoundsAdmissionReads(){
        var f=new Fixture();var nanos=new AtomicLong();var reader=new CCustodyRpcReader(f.id,f,Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC),()->nanos.getAndAdd(2_000_000_000L));
        assertEquals("C_READ_SNAPSHOT_LIMIT",assertThrows(CCustodyFailure.class,()->reader.admissionPrestate(f.intent(0))).code());
    }
}
