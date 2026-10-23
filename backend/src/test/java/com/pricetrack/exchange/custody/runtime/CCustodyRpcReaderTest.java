package com.pricetrack.exchange.custody.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CCustodyRpcReaderTest {
    public static final class Fixture implements CReadRpc {
        final CCustodyIdentity id=CCustodyIdentityTest.sample();
        public final String owner=CCustodyIdentityTest.address(99);final String block=CCustodyIdentityTest.hash(101),parent=CCustodyIdentityTest.hash(100),position=CCustodyIdentityTest.hash(77);
        public int calls;public boolean changedHeader,changedCode,wrongRole,badDomain,badBalance,foreignOwner,ownerCode,terminal=true;public String missingSelector;public boolean fail;public boolean malformedTuple;
        final Map<String,String> values=new HashMap<>();
        public Fixture(){
            add(id.controller().address(),"rules()",aw(id.rules().address()));add(id.controller().address(),"vault()",aw(id.vault().address()));add(id.controller().address(),"relayer()",aw(id.relayer()));add(id.controller().address(),"pauseAdmin()",aw(id.pauseAdmin()));
            add(id.vault().address(),"asset()",aw(id.token().address()));add(id.vault().address(),"controller()",aw(id.controller().address()));add(id.rules().address(),"executor()",aw(id.controller().address()));add(id.rules().address(),"priceSigner()",aw(id.oracle()));
            add(id.rules().address(),"supportedSymbolHash()",id.symbolHash());add(id.rules().address(),"policyHash()",id.policyHash());add(id.token().address(),"totalSupply()",w(CCustodyIdentity.SUPPLY));add(id.token().address(),"decimals()",w(18));
            add(id.controller().address(),"eip712Domain()",CReadAbi.domain(CCustodyIdentity.USER_DOMAIN,id.chainId(),id.controller().address()));add(id.rules().address(),"eip712Domain()",CReadAbi.domain(CCustodyIdentity.PRICE_DOMAIN,id.chainId(),id.rules().address()));
            add(id.token().address(),"balanceOf(address)",w(5_000));add(id.token().address(),"allowance(address,address)",w(12));
            for(String sig:List.of("freeBalance(address)","totalFree()"))add(id.vault().address(),sig,w(100));
            for(String sig:List.of("lockedBalance(address)","totalLocked()"))add(id.vault().address(),sig,w(30));
            add(id.vault().address(),"houseBalance()",w(20));add(id.vault().address(),"totalUnpaidProfit()",w(900));add(id.vault().address(),"recognizedAssets()",w(150));add(id.vault().address(),"unassignedAssets()",w(1));add(id.controller().address(),"nonces(address)",w(3));
        }
        void add(String target,String sig,String raw){values.put(target+CReadAbi.selector(sig),raw);}
        public CCustodyRpcReader reader(){return new CCustodyRpcReader(id,this,Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC));}
        public JsonNode request(String method,List<Object> params,long timeout){
            calls++;assertTrue(timeout>0&&timeout<=3000);if(fail)throw new CCustodyFailure("C_RPC_UNAVAILABLE",503);
            if(method.equals("eth_chainId"))return TextNode.valueOf("0x7a69");
            if(method.equals("eth_getBlockByNumber")){
                String number=(String)params.get(0);if(number.equals("0x0"))return JsonNodeFactory.instance.objectNode().put("hash",id.genesisHash()).put("number","0x0");
                return JsonNodeFactory.instance.objectNode().put("number","0x64").put("hash",changedHeader&&!number.equals("latest")?CCustodyIdentityTest.hash(999):block).put("parentHash",parent).put("timestamp","0x100");
            }
            assertEquals(Map.of("blockHash",block,"requireCanonical",true),params.get(1));
            if(method.equals("eth_getCode")){
                String target=(String)params.get(0);if(target.equals(owner))return TextNode.valueOf(ownerCode?"0x6000":"0x");
                if(changedCode)return TextNode.valueOf("0x6000");
                return TextNode.valueOf(target.equals(id.token().address())?"0x6001":target.equals(id.controller().address())?"0x6002":target.equals(id.rules().address())?"0x6003":"0x6004");
            }
            assertEquals("eth_call",method);@SuppressWarnings("unchecked")Map<String,String> call=(Map<String,String>)params.get(0);String target=call.get("to"),data=call.get("data"),selector=data.substring(0,10);
            if(selector.equals(missingSelector))return NullNode.instance;
            if(wrongRole&&selector.equals(CReadAbi.selector("relayer()")))return TextNode.valueOf(aw(id.oracle()));
            if(badDomain&&selector.equals(CReadAbi.selector("eip712Domain()")))return TextNode.valueOf(CReadAbi.domain("TokenizedMarketFuturesUserIntent",id.chainId(),id.controller().address()));
            if(badBalance&&selector.equals(CReadAbi.selector("recognizedAssets()")))return TextNode.valueOf(w(999));
            if(selector.equals(CReadAbi.selector("positionOwner(bytes32)")))return TextNode.valueOf(aw(foreignOwner?CCustodyIdentityTest.address(98):owner));
            if(selector.equals(CReadAbi.selector("getPosition(bytes32)")))return TextNode.valueOf(malformedTuple?w(0):"0x"+CReadAbi.accountRef(owner,id.symbolHash()).substring(2)+CReadAbi.word(BigInteger.ZERO)+CReadAbi.word(BigInteger.TEN)+CReadAbi.word(BigInteger.valueOf(100))+CReadAbi.word(BigInteger.valueOf(100))+CReadAbi.word(BigInteger.valueOf(1000))+CReadAbi.word(BigInteger.valueOf(terminal?3:1))+CReadAbi.word(BigInteger.valueOf(3))+CReadAbi.word(BigInteger.ONE)+CReadAbi.word(BigInteger.valueOf(316))+CReadAbi.word(BigInteger.valueOf(256)));
            if(selector.equals(CReadAbi.selector("positionLocks(bytes32)")))return TextNode.valueOf("0x"+CReadAbi.addressWord(owner)+CReadAbi.word(terminal?BigInteger.ZERO:BigInteger.valueOf(1000))+CReadAbi.word(terminal?BigInteger.ONE:BigInteger.ZERO));
            if(selector.equals(CReadAbi.selector("claims(uint256)")))return TextNode.valueOf("0x"+CReadAbi.addressWord(foreignOwner?CCustodyIdentityTest.address(98):owner)+CCustodyIdentityTest.hash(76).substring(2)+position.substring(2)+CReadAbi.word(BigInteger.valueOf(900))+CReadAbi.word(BigInteger.valueOf(400))+CReadAbi.word(BigInteger.valueOf(500)));
            if(selector.equals(CReadAbi.selector("balanceOf(address)"))&&data.endsWith(CReadAbi.addressWord(id.vault().address())))return TextNode.valueOf(w(151));
            String value=values.get(target+selector);assertNotNull(value,"unexpected fixed selector");return TextNode.valueOf(value);
        }
        static String w(long n){return w(BigInteger.valueOf(n));}static String w(BigInteger n){return "0x"+CReadAbi.word(n);}static String aw(String owner){return "0x"+CReadAbi.addressWord(owner);}
    }
    @Test void snapshotUsesHashPinAndSeparatesUnpaidFromFree(){var f=new Fixture();var out=f.reader().account(f.owner);assertEquals("100",out.value().free());assertEquals("900",out.value().totalUnpaidProfit());assertEquals("151",out.value().actualVaultTokens());assertTrue(out.sameProviderHeaderRechecked());assertFalse(out.completeCoverage());assertFalse(out.globalCanonicalVerified());assertTrue(f.calls<CCustodyRpcReader.MAX_CALLS);}
    @Test void terminalRulesCollateralDoesNotBecomeWithdrawableLocked(){var f=new Fixture();var out=f.reader().position(f.owner,f.position);assertEquals("1000",out.value().historicalCollateral());assertEquals("0",out.value().lockedCollateral());assertEquals("CLOSED",out.value().status());assertTrue(out.value().lockTerminal());}
    @Test void ownClaimIsSingleRecordNotQueueCompleteness(){var f=new Fixture();var out=f.reader().claim(f.owner,"1");assertEquals("900",out.value().originalAmount());assertEquals("400",out.value().paidAmount());assertEquals("500",out.value().remainingAmount());assertFalse(out.completeCoverage());}
    @Test void foreignPositionAndClaimAreNotReturned(){var f=new Fixture();f.foreignOwner=true;assertEquals(404,assertThrows(CCustodyFailure.class,()->f.reader().position(f.owner,f.position)).status());assertEquals(404,assertThrows(CCustodyFailure.class,()->f.reader().claim(f.owner,"1")).status());}
    @Test void changedHeaderAndCodeFailClosed(){var f=new Fixture();f.changedHeader=true;assertThrows(CCustodyFailure.class,()->f.reader().account(f.owner));f.changedHeader=false;f.changedCode=true;assertThrows(CCustodyFailure.class,()->f.reader().config());}
    @Test void roleDomainAndConservationMismatchFailClosed(){var f=new Fixture();f.wrongRole=true;assertThrows(CCustodyFailure.class,()->f.reader().config());f.wrongRole=false;f.badDomain=true;assertThrows(CCustodyFailure.class,()->f.reader().config());f.badDomain=false;f.badBalance=true;assertThrows(CCustodyFailure.class,()->f.reader().account(f.owner));}
    @Test void missingPartialRpcAndMalformedAbiDoNotProduceSnapshot(){var f=new Fixture();f.missingSelector=CReadAbi.selector("freeBalance(address)");assertThrows(CCustodyFailure.class,()->f.reader().account(f.owner));f.missingSelector=null;f.malformedTuple=true;assertThrows(CCustodyFailure.class,()->f.reader().position(f.owner,f.position));}
    @Test void eoaAndInvalidClaimIdsAreRejected(){var f=new Fixture();f.ownerCode=true;assertEquals("C_EOA_REQUIRED",assertThrows(CCustodyFailure.class,()->f.reader().eoa(f.owner)).code());assertThrows(CCustodyFailure.class,()->f.reader().claim(f.owner,"-1"));assertThrows(CCustodyFailure.class,()->f.reader().claim(f.owner,"0"));}
    @Test void wholeSnapshotBudgetBoundsOtherwiseFastIndividualRequests(){var f=new Fixture();AtomicLong n=new AtomicLong();var reader=new CCustodyRpcReader(f.id,f,Clock.systemUTC(),()->n.getAndAdd(2_000_000_000L));assertEquals("C_READ_SNAPSHOT_LIMIT",assertThrows(CCustodyFailure.class,reader::config).code());}
}
