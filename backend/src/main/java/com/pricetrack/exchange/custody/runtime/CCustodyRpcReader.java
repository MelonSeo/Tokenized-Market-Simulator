package com.pricetrack.exchange.custody.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import org.web3j.crypto.Hash;

/** Single-provider, fixed-block live observations. NOT a scanner, projection writer or full audit. */
public final class CCustodyRpcReader {
    public static final int MAX_CALLS=64;
    public static final long SNAPSHOT_NANOS=15_000_000_000L;
    public record Point(String number,String hash,String parentHash,String timestamp) {}
    public record Observation<T>(String deploymentId,Point block,Instant observedAt,T value) {
        @com.fasterxml.jackson.annotation.JsonProperty public boolean sameProviderHeaderRechecked(){return true;}
        @com.fasterxml.jackson.annotation.JsonProperty public boolean completeCoverage(){return false;}
        @com.fasterxml.jackson.annotation.JsonProperty public boolean globalCanonicalVerified(){return false;}
        @com.fasterxml.jackson.annotation.JsonProperty public String verificationScope(){return "SINGLE_PROVIDER_PINNED_BLOCK_HASH_RECHECK_NOT_SCANNER_OR_FINALITY";}
    }
    public record Account(String owner,String walletBalance,String allowanceToVault,String free,String locked,
        String totalFree,String totalLocked,String house,String totalUnpaidProfit,String recognizedAssets,
        String unassignedAssets,String actualVaultTokens,String userNonce) {}
    public record PositionView(String positionId,String owner,String accountRef,String side,String leverage,String quantity,
        String entryPriceE8,String historicalCollateral,String status,String version,String callEpoch,String deadline,
        String lastObservedAt,String lockedCollateral,boolean lockTerminal) {}
    public record ClaimView(String claimId,String owner,String operationId,String positionId,String originalAmount,
        String paidAmount,String remainingAmount) {}
    /** Observed prerequisites only: not consent verification, a nonce reservation, a Report or executable authority.
     * Consumers must call their fixed reader themselves; this constructible DTO is not an authenticated capability. */
    public record AdmissionPrestate(Intent intent,String userNonce,String free,String locked,String totalUnpaidProfit,
        boolean newPositionsPaused,String activePosition,PositionView position) {}
    private final CCustodyIdentity identity;private final CReadRpc rpc;private final Clock clock;private final LongSupplier nano;
    public CCustodyRpcReader(CCustodyIdentity identity,CReadRpc rpc,Clock clock){this(identity,rpc,clock,System::nanoTime);}
    CCustodyRpcReader(CCustodyIdentity identity,CReadRpc rpc,Clock clock,LongSupplier nano){this.identity=Objects.requireNonNull(identity);this.rpc=Objects.requireNonNull(rpc);this.clock=Objects.requireNonNull(clock);this.nano=Objects.requireNonNull(nano);}
    public CCustodyIdentity identity(){return identity;}
    public Observation<Map<String,String>> config(){Context c=begin();return finish(c,identity.publicConfig());}
    /** EOA code emptiness at this observed point, not a permanent account-type promise. */
    public Observation<String> eoa(String owner){owner=CCustodyIdentity.address(owner);Context c=begin();requireEoa(c,owner);return finish(c,owner);}
    /** All admission reads use one verified block hash. State may change after the observation;
     * the controller remains authoritative. No Oracle, market freshness or Report signature is checked here. */
    public Observation<AdmissionPrestate> admissionPrestate(Intent i){
        if(i==null)throw CReadAbi.fail("ADMISSION_INTENT");
        String owner=CCustodyIdentity.address(i.owner()),zeroAddress="0x"+"0".repeat(40);
        check(i.relayer().equals(identity.relayer())&&i.symbolHash().equals(identity.symbolHash())
            &&i.policyHash().equals(identity.policyHash())&&i.accountRef().equals(CReadAbi.accountRef(owner,identity.symbolHash()))
            &&!i.operationId().equals(CReadAbi.ZERO)&&!i.positionId().equals(CReadAbi.ZERO),"ADMISSION_SCOPE");
        int action=i.action().intValueExact();
        check(action==0||action==1||action==4,"ADMISSION_ACTION");
        check(i.side().bitLength()<=1&&Set.of(BigInteger.TWO,BigInteger.valueOf(5),BigInteger.TEN).contains(i.leverage())
            &&i.collateralAfter().signum()>0&&i.collateralAfter().compareTo(FuturesMath.AMOUNT_LIMIT)<0
            &&i.priceMinE8().signum()>0&&i.priceMinE8().compareTo(i.priceMaxE8())<=0&&i.priceMaxE8().compareTo(FuturesMath.PRICE_LIMIT)<0
            &&i.quantityMin().signum()>0&&i.quantityMin().compareTo(i.quantityMax())<=0&&i.quantityMax().compareTo(FuturesMath.AMOUNT_LIMIT)<0,
            "ADMISSION_TERMS");
        Context c=begin();requireEoa(c,owner);
        admissionWindow(i,new BigInteger(c.point.timestamp()));admissionWindow(i,BigInteger.valueOf(clock.instant().getEpochSecond()));
        String controller=identity.controller().address(),rules=identity.rules().address(),vault=identity.vault().address();
        String ownerArg=CReadAbi.addressWord(owner),positionArg=i.positionId().substring(2),operationArg=i.operationId().substring(2);
        BigInteger nonce=num(c,controller,"nonces(address)",ownerArg);
        check(nonce.equals(i.nonce())&&nonce.compareTo(com.pricetrack.exchange.custody.abi.CAbiModels.UINT_MAX)<0,"ADMISSION_NONCE");
        check(!CReadAbi.boolAt(call(c,rules,"usedOperations(bytes32)",operationArg),1,0)
            &&!CReadAbi.boolAt(call(c,vault,"usedOperations(bytes32)",operationArg),1,0),"ADMISSION_USED_OPERATION");
        boolean paused=CReadAbi.boolAt(call(c,controller,"newPositionsPaused()",""),1,0);
        String active=call(c,rules,"activePosition(bytes32)",i.accountRef().substring(2));CReadAbi.data(active,1);
        String storedOwner=admissionAddress(call(c,controller,"positionOwner(bytes32)",positionArg),1,0);
        String p=call(c,rules,"getPosition(bytes32)",positionArg);CReadAbi.data(p,11);
        String l=call(c,vault,"positionLocks(bytes32)",positionArg);CReadAbi.data(l,3);
        BigInteger free=num(c,vault,"freeBalance(address)",ownerArg),locked=num(c,vault,"lockedBalance(address)",ownerArg),unpaid=num(c,vault,"totalUnpaidProfit()","");
        BigInteger totalFree=num(c,vault,"totalFree()",""),totalLocked=num(c,vault,"totalLocked()",""),house=num(c,vault,"houseBalance()","");
        BigInteger recognized=num(c,vault,"recognizedAssets()",""),actual=num(c,identity.token().address(),"balanceOf(address)",CReadAbi.addressWord(vault));
        check(recognized.equals(totalFree.add(totalLocked).add(house))&&actual.compareTo(recognized)>=0
            &&free.compareTo(totalFree)<=0&&locked.compareTo(totalLocked)<=0,"ADMISSION_ASSET_CONSERVATION");
        String account=CReadAbi.hashAt(p,11,0),lockOwner=admissionAddress(l,3,0);
        BigInteger side=n(p,11,1),leverage=n(p,11,2),quantity=n(p,11,3),entry=n(p,11,4),collateral=n(p,11,5),status=n(p,11,6);
        BigInteger version=n(p,11,7),epoch=n(p,11,8),deadline=n(p,11,9),lastObserved=n(p,11,10),amount=n(l,3,1);
        boolean terminal=CReadAbi.boolAt(l,3,2);
        if(action==0){
            check(!paused&&unpaid.signum()==0,"ADMISSION_OPEN_BLOCKED");
            check(storedOwner.equals(zeroAddress)&&active.equals(CReadAbi.ZERO)&&p.equals("0x"+"0".repeat(11*64))
                &&lockOwner.equals(zeroAddress)&&amount.signum()==0&&!terminal,"ADMISSION_OPEN_STATE");
            check(i.expectedVersion().signum()==0&&i.expectedCallEpoch().signum()==0&&i.entryPriceE8().signum()==0
                &&i.collateralBefore().signum()==0&&free.compareTo(i.collateralAfter())>=0,"ADMISSION_OPEN_TERMS");
        }else{
            check(storedOwner.equals(owner)&&lockOwner.equals(owner)&&account.equals(i.accountRef())&&active.equals(i.positionId())
                &&(status.equals(BigInteger.ONE)||status.equals(BigInteger.TWO))&&!terminal&&amount.equals(collateral)
                &&locked.compareTo(amount)>=0,"ADMISSION_POSITION_STATE");
            check(version.equals(i.expectedVersion())&&epoch.equals(i.expectedCallEpoch())&&side.equals(i.side())&&leverage.equals(i.leverage())
                &&collateral.equals(i.collateralBefore())&&i.entryPriceE8().equals(entry)&&entry.signum()>0
                &&i.quantityMin().equals(i.quantityMax())&&i.quantityMin().equals(quantity),"ADMISSION_POSITION_TERMS");
            if(action==1)check(i.collateralAfter().compareTo(collateral)>0&&free.compareTo(i.collateralAfter().subtract(collateral))>=0,"ADMISSION_TOP_UP_TERMS");
            else check(i.collateralAfter().equals(collateral),"ADMISSION_CLOSE_TERMS");
        }
        String state=status.signum()==0?"NONE":status.equals(BigInteger.ONE)?"ACTIVE":"MARGIN_CALL";
        PositionView view=new PositionView(i.positionId(),storedOwner,account,side.toString(),leverage.toString(),quantity.toString(),entry.toString(),
            collateral.toString(),state,version.toString(),epoch.toString(),deadline.toString(),lastObserved.toString(),amount.toString(),terminal);
        Observation<AdmissionPrestate> out=finish(c,new AdmissionPrestate(i,nonce.toString(),free.toString(),locked.toString(),unpaid.toString(),paused,active,view));
        // Include the final header checks in the local expiry boundary, not only the initial reads.
        admissionWindow(i,BigInteger.valueOf(out.observedAt().getEpochSecond()));return out;
    }
    private static void admissionWindow(Intent i,BigInteger now){
        check(i.issuedAt().compareTo(now)<=0&&i.deadline().compareTo(i.issuedAt())>0
            &&i.deadline().subtract(i.issuedAt()).compareTo(BigInteger.valueOf(120))<=0&&now.compareTo(i.deadline())<=0,"ADMISSION_WINDOW");
    }
    /** Mapping defaults legitimately contain the zero address; existing public owner reads still reject it. */
    private static String admissionAddress(String raw,int words,int index){
        return CReadAbi.hashAt(raw,words,index).equals(CReadAbi.ZERO)?"0x"+"0".repeat(40):CReadAbi.addressAt(raw,words,index);
    }
    public Observation<Account> account(String owner){
        owner=CCustodyIdentity.address(owner);Context c=begin();requireEoa(c,owner);
        String t=identity.token().address(),v=identity.vault().address();String a=CReadAbi.addressWord(owner);
        BigInteger balance=num(c,t,"balanceOf(address)",a),allowance=num(c,t,"allowance(address,address)",a+CReadAbi.addressWord(v));
        BigInteger free=num(c,v,"freeBalance(address)",a),locked=num(c,v,"lockedBalance(address)",a),totalFree=num(c,v,"totalFree()",""),totalLocked=num(c,v,"totalLocked()",""),house=num(c,v,"houseBalance()","");
        BigInteger unpaid=num(c,v,"totalUnpaidProfit()",""),recognized=num(c,v,"recognizedAssets()",""),unassigned=num(c,v,"unassignedAssets()",""),actual=num(c,t,"balanceOf(address)",CReadAbi.addressWord(v));
        BigInteger nonce=num(c,identity.controller().address(),"nonces(address)",a);
        check(recognized.equals(totalFree.add(totalLocked).add(house))&&actual.compareTo(recognized)>=0&&unassigned.equals(actual.subtract(recognized)),"ASSET_CONSERVATION");
        check(free.compareTo(totalFree)<=0&&locked.compareTo(totalLocked)<=0&&actual.add(balance).compareTo(CCustodyIdentity.SUPPLY)<=0,"ASSET_BOUNDS");
        return finish(c,new Account(owner,balance.toString(),allowance.toString(),free.toString(),locked.toString(),totalFree.toString(),totalLocked.toString(),house.toString(),unpaid.toString(),recognized.toString(),unassigned.toString(),actual.toString(),nonce.toString()));
    }
    public Observation<PositionView> position(String owner,String positionId){
        owner=CCustodyIdentity.address(owner);positionId=CCustodyIdentity.hash(positionId);Context c=begin();requireEoa(c,owner);
        String stored=call(c,identity.controller().address(),"positionOwner(bytes32)",positionId.substring(2));
        if(CReadAbi.hashAt(stored,1,0).equals(CReadAbi.ZERO)||!CReadAbi.addressAt(stored,1,0).equals(owner))throw notFound();
        String p=call(c,identity.rules().address(),"getPosition(bytes32)",positionId.substring(2));CReadAbi.data(p,11);
        String l=call(c,identity.vault().address(),"positionLocks(bytes32)",positionId.substring(2));CReadAbi.data(l,3);
        String account=CReadAbi.hashAt(p,11,0);check(account.equals(CReadAbi.accountRef(owner,identity.symbolHash()))&&CReadAbi.addressAt(l,3,0).equals(owner),"POSITION_OWNER");
        BigInteger side=n(p,11,1),leverage=n(p,11,2),quantity=n(p,11,3),entry=n(p,11,4),collateral=n(p,11,5),status=n(p,11,6),amount=n(l,3,1);
        check(side.bitLength()<=1&&Set.of(BigInteger.TWO,BigInteger.valueOf(5),BigInteger.TEN).contains(leverage)&&quantity.signum()>0&&entry.signum()>0&&collateral.signum()>0&&status.signum()>0&&status.compareTo(BigInteger.valueOf(4))<=0,"POSITION_FIELDS");
        boolean terminal=status.compareTo(BigInteger.valueOf(3))>=0,lockTerminal=CReadAbi.boolAt(l,3,2);
        check(lockTerminal==terminal&&(terminal?amount.signum()==0:amount.equals(collateral)),"POSITION_LOCK");
        String state=switch(status.intValueExact()){case 1->"ACTIVE";case 2->"MARGIN_CALL";case 3->"CLOSED";case 4->"LIQUIDATED";default->throw CReadAbi.fail("POSITION_STATUS");};
        return finish(c,new PositionView(positionId,owner,account,side.toString(),leverage.toString(),quantity.toString(),entry.toString(),collateral.toString(),state,n(p,11,7).toString(),n(p,11,8).toString(),n(p,11,9).toString(),n(p,11,10).toString(),amount.toString(),lockTerminal));
    }
    /** Explicit one-claim lookup. Does not enumerate all of this owner's claims. */
    public Observation<ClaimView> claim(String owner,String id){
        owner=CCustodyIdentity.address(owner);BigInteger claimId=CCustodyIdentity.uint(id);if(claimId.signum()==0)throw notFound();Context c=begin();requireEoa(c,owner);
        String raw=call(c,identity.vault().address(),"claims(uint256)",CReadAbi.word(claimId));CReadAbi.data(raw,6);
        if(CReadAbi.hashAt(raw,6,0).equals(CReadAbi.ZERO)||!CReadAbi.addressAt(raw,6,0).equals(owner))throw notFound();
        BigInteger original=n(raw,6,3),paid=n(raw,6,4),remaining=n(raw,6,5);check(original.signum()>0&&original.equals(paid.add(remaining)),"CLAIM_CONSERVATION");
        String op=CReadAbi.hashAt(raw,6,1),position=CReadAbi.hashAt(raw,6,2);check(!op.equals(CReadAbi.ZERO)&&!position.equals(CReadAbi.ZERO),"CLAIM_IDENTITY");
        return finish(c,new ClaimView(claimId.toString(),owner,op,position,original.toString(),paid.toString(),remaining.toString()));
    }
    private final class Context {
        final long started=nano.getAsLong();int calls;Point point;
        JsonNode rpc(String method,Object...params){long remaining=SNAPSHOT_NANOS-(nano.getAsLong()-started);if(++calls>MAX_CALLS||remaining<=0)throw CReadAbi.fail("SNAPSHOT_LIMIT");long millis=Math.min(3000,Math.max(1,remaining/1_000_000));JsonNode result=CCustodyRpcReader.this.rpc.request(method,List.of(params),millis);if(nano.getAsLong()-started>=SNAPSHOT_NANOS)throw CReadAbi.fail("SNAPSHOT_LIMIT");if(result==null||result.isNull())throw CReadAbi.fail("MISSING_RESULT");return result;}
        Map<String,Object> pinned(){return Map.of("blockHash",point.hash(),"requireCanonical",true);}
    }
    private Context begin(){
        Context c=new Context();check(quantity(c.rpc("eth_chainId")).equals(identity.chainId()),"CHAIN_ID");
        JsonNode genesis=c.rpc("eth_getBlockByNumber","0x0",false);check(text(genesis,"hash").equals(identity.genesisHash())&&quantity(genesis.path("number")).signum()==0,"GENESIS");
        c.point=point(c.rpc("eth_getBlockByNumber","latest",false));
        for(CCustodyIdentity.Contract contract:List.of(identity.token(),identity.controller(),identity.rules(),identity.vault())){
            String code=asHex(c.rpc("eth_getCode",contract.address(),c.pinned()),false);check(code.length()>2&&Hash.sha3(code).equals(contract.codeHash()),"CODE_HASH");
        }
        String controller=identity.controller().address(),rules=identity.rules().address(),vault=identity.vault().address(),token=identity.token().address();
        address(c,controller,"rules()",rules);address(c,controller,"vault()",vault);address(c,controller,"relayer()",identity.relayer());address(c,controller,"pauseAdmin()",identity.pauseAdmin());
        address(c,vault,"asset()",token);address(c,vault,"controller()",controller);address(c,rules,"executor()",controller);address(c,rules,"priceSigner()",identity.oracle());
        check(call(c,rules,"supportedSymbolHash()","").equals(identity.symbolHash())&&call(c,rules,"policyHash()","").equals(identity.policyHash()),"RULES_POLICY");
        check(num(c,token,"totalSupply()","").equals(CCustodyIdentity.SUPPLY)&&num(c,token,"decimals()","").equals(BigInteger.valueOf(18)),"TOKEN_SUPPLY");
        check(call(c,controller,"eip712Domain()","").equals(CReadAbi.domain(CCustodyIdentity.USER_DOMAIN,identity.chainId(),controller)),"USER_DOMAIN");
        check(call(c,rules,"eip712Domain()","").equals(CReadAbi.domain(CCustodyIdentity.PRICE_DOMAIN,identity.chainId(),rules)),"PRICE_DOMAIN");return c;
    }
    private <T> Observation<T> finish(Context c,T value){Point finalHeader=point(c.rpc("eth_getBlockByNumber",hex(new BigInteger(c.point.number())),false));check(finalHeader.equals(c.point),"HEADER_CHANGED");check(text(c.rpc("eth_getBlockByNumber","0x0",false),"hash").equals(identity.genesisHash()),"GENESIS_CHANGED");return new Observation<>(identity.deploymentId(),c.point,clock.instant(),value);}
    private void requireEoa(Context c,String owner){if(!asHex(c.rpc("eth_getCode",owner,c.pinned()),true).equals("0x"))throw new CCustodyFailure("C_EOA_REQUIRED",400);}
    private void address(Context c,String target,String sig,String expected){check(CReadAbi.addressAt(call(c,target,sig,""),1,0).equals(expected),"DEPLOYMENT_BINDING");}
    private String call(Context c,String target,String signature,String argument){return asHex(c.rpc("eth_call",Map.of("to",target,"data",CReadAbi.selector(signature)+argument),c.pinned()),false);}
    private BigInteger num(Context c,String target,String signature,String argument){return CReadAbi.number(call(c,target,signature,argument),1,0);}
    private static BigInteger n(String raw,int words,int index){return CReadAbi.number(raw,words,index);}
    private static Point point(JsonNode raw){check(raw.isObject(),"HEADER");return new Point(quantity(raw.path("number")).toString(),readHash(text(raw,"hash")),readHash(text(raw,"parentHash")),quantity(raw.path("timestamp")).toString());}
    private static String readHash(String value){if(!value.matches("0x[0-9a-f]{64}"))throw CReadAbi.fail("HEADER_HASH");return value;}
    private static BigInteger quantity(JsonNode raw){String s=raw.isTextual()?raw.asText():"";if(!s.matches("0x(?:0|[1-9a-f][0-9a-f]{0,63})"))throw CReadAbi.fail("RPC_QUANTITY");return new BigInteger(s.substring(2),16);}
    private static String text(JsonNode raw,String key){JsonNode n=raw.path(key);if(!n.isTextual())throw CReadAbi.fail("RPC_FIELD");return n.asText();}
    private static String asHex(JsonNode raw,boolean empty){String s=raw.isTextual()?raw.asText():"";if(s.length()>2+512*1024*2||!s.matches(empty?"0x(?:[0-9a-f]{2})*":"0x(?:[0-9a-f]{2})+"))throw CReadAbi.fail("RPC_HEX");return s;}
    private static String hex(BigInteger n){return "0x"+n.toString(16);}
    private static void check(boolean condition,String code){if(!condition)throw CReadAbi.fail(code);}
    private static CCustodyFailure notFound(){return new CCustodyFailure("C_OWN_RECORD_NOT_FOUND",404);}
}
