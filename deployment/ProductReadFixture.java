import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import org.web3j.crypto.*;

/** New disposable CI chain only. No old snapshot, user wallet or persisted user state. */
public final class ProductReadFixture {
    static final BigInteger Z=BigInteger.ZERO,ONE=BigInteger.ONE,CHAIN=CAnvilContractGate.CHAIN;
    static BigInteger units(long n){return CAnvilContractGate.units(n);}
    static BigInteger number(com.fasterxml.jackson.databind.JsonNode n){return CAnvilContractGate.number(n);}
    static BigInteger wordNum(String s,int n){return CAnvilContractGate.wordNum(s,n);}
    static String hash(String s){return CAnvilContractGate.hash(s);}
    static String hex(BigInteger n){return CAnvilContractGate.hex(n);}
    static String words(String...s){return CAnvilContractGate.words(s);}
    static String addressWord(String s){return CAnvilContractGate.addressWord(s);}
    static String address(ECKeyPair k){return CAnvilContractGate.address(k);}
    static String signature(String s,ECKeyPair k){return CAnvilContractGate.signature(s,k);}
    static void check(boolean b,String s){CAnvilContractGate.check(b,s);}
    static void eq(Object a,Object b){CAnvilContractGate.eq(a,b);}
    final CAnvilContractGate g;
    ProductReadFixture(Path artifacts,Path evidence){g=new CAnvilContractGate(artifacts,evidence);}
    static String position(String role){return Hash.sha3String("PRODUCT_READ_CI_POSITION_"+role);}
    void trade(String role,int action,int version,BigInteger quantity,BigInteger price)throws Exception{
        String owner=g.addr(role),pos=position(role),account=g.call(g.controller,"accountRef(address)",addressWord(owner));
        BigInteger at=number(g.rpc("eth_getBlockByNumber","latest",false).path("timestamp")),collateral=units(1000),entry=BigInteger.valueOf(100_00000000L);
        String operation=hash("PRODUCT_READ_CI_"+role+"_"+action);
        Report r=new Report(operation,pos,account,g.symbol,BigInteger.valueOf(action),BigInteger.valueOf(version),Z,Z,BigInteger.TEN,quantity,entry,action==0?Z:collateral,collateral,price,at,at.add(BigInteger.valueOf(30)),g.policy,g.controller);
        Intent i=new Intent(owner,g.addr("relayer"),operation,pos,account,g.symbol,r.action(),r.expectedVersion(),Z,Z,BigInteger.TEN,action==0?Z:entry,r.collateralBefore(),collateral,price,price,quantity,quantity,BigInteger.valueOf(version),at,at.add(BigInteger.valueOf(120)),g.policy);
        String rd=g.crypto.reportDigest(r,CHAIN,g.rules),id=g.crypto.intentDigest(i,CHAIN,g.controller);
        var call=new Relayed(r,signature(rd,g.keys.get("oracle")),i,signature(id,g.keys.get(role)));
        var mark=FuturesMath.mark(FuturesMath.Side.LONG,quantity,entry,collateral,price);BigInteger pnl=action==4?mark.realized():Z;
        var expected=new ArrayList<CAnvilContractGate.Expected>();
        expected.add(new CAnvilContractGate.Expected(g.rules,EmitterKind.RULES,new OperationApplied(operation,pos,r.action(),r.expectedVersion().add(ONE),price,collateral,mark.unrealized(),pnl,Z,Z,Z)));
        expected.add(new CAnvilContractGate.Expected(g.rules,EmitterKind.RULES,new FuturesReportConsumed(operation,at,r.validUntil())));
        expected.add(new CAnvilContractGate.Expected(g.vault,EmitterKind.VAULT,action==0?new CollateralLocked(operation,pos,owner,collateral,collateral):new PositionSettled(operation,pos,owner,collateral,pnl,Z,pnl,Z)));
        expected.add(new CAnvilContractGate.Expected(g.controller,EmitterKind.CONTROLLER,new CUserIntentExecuted(operation,pos,owner,id,rd,r.action(),i.nonce(),false)));
        var receipt=g.send(g.keys.get("relayer"),g.controller,g.abi.encode(call),true);g.expect(receipt,expected);
        eq(g.callAddress(g.controller,"positionOwner(bytes32)",pos),owner);
        eq(g.callNum(g.controller,"nonces(address)",addressWord(owner)),BigInteger.valueOf(version+1));
        g.save("public-trade-"+role+"-"+action+".json",CAnvilContractGate.JSON.createObjectNode().put("txHash",receipt.path("transactionHash").asText()).put("blockHash",receipt.path("blockHash").asText()).put("positionId",pos).put("owner",owner).put("action",action).put("canonicalReceiptVerified",true).put("exactEventCount",4));
    }
    void run(Path publicOutput,Path keyOutput)throws Exception{
        CAnvilContractGate.verifyTransportPolicy();g.verifySourceMetadata();
        eq(number(g.rpc("eth_chainId")),CHAIN);g.genesis=g.rpc("eth_getBlockByNumber","0x0",false).path("hash").asText();
        for(String role:List.of("treasury","admin","relayer","oracle","userA","userB"))g.keys.put(role,Keys.createEcKeyPair(new SecureRandom()));
        check(new HashSet<>(g.keys.values().stream().map(CAnvilContractGate::address).toList()).size()==6,"CI_DISTINCT_KEYS");
        // Private values stay on this runner, in a separately mounted Driver-only directory.
        ObjectNode keys=CAnvilContractGate.JSON.createObjectNode();
        for(String role:List.of("userA","userB"))keys.put(role,String.format("%064x",g.keys.get(role).getPrivateKey()));
        Files.writeString(keyOutput.resolve("wallet-keys.private.json"),keys.toString(),StandardOpenOption.CREATE_NEW);
        for(var key:g.keys.values())g.rpc("anvil_setBalance",address(key),hex(units(100)));
        g.token=g.deploy("CMockCollateral",words(addressWord(g.addr("treasury"))),g.keys.get("treasury"));
        g.controller=g.deploy("CCollateralizedFuturesController",words(addressWord(g.token),addressWord(g.addr("relayer")),addressWord(g.addr("oracle")),addressWord(g.addr("admin")),g.symbol,g.policy),g.keys.get("treasury"));
        g.rules=g.callAddress(g.controller,"rules()");g.vault=g.callAddress(g.controller,"vault()");
        check(Set.of(g.token,g.controller,g.rules,g.vault).size()==4,"CI_CONTRACTS_DISTINCT");
        for(var entry:Map.of("CMockCollateral",g.token,"CCollateralizedFuturesController",g.controller,"FuturesEngine",g.rules,"CMarginVault",g.vault).entrySet())g.verifyRuntime(entry.getKey(),entry.getValue());
        eq(g.callAddress(g.vault,"asset()"),g.token);eq(g.callAddress(g.vault,"controller()"),g.controller);
        eq(g.callAddress(g.rules,"executor()"),g.controller);eq(g.callAddress(g.rules,"priceSigner()"),g.addr("oracle"));
        eq(g.callAddress(g.controller,"relayer()"),g.addr("relayer"));eq(g.callAddress(g.controller,"pauseAdmin()"),g.addr("admin"));
        eq(g.call(g.rules,"supportedSymbolHash()"),g.symbol);eq(g.call(g.rules,"policyHash()"),g.policy);
        g.cash("treasury",VaultAction.FUND_HOUSE,units(100000));
        var meta=CAnvilContractGate.JSON.createObjectNode().put("scope","NEW_CI_RECIPE_NOT_C5A2_SNAPSHOT").put("genesisHash",g.genesis).put("controller",g.controller).put("rules",g.rules).put("vault",g.vault).put("token",g.token);
        var hashes=meta.putObject("codeHashes");
        for(var entry:Map.of("token","CMockCollateral","controller","CCollateralizedFuturesController","rules","FuturesEngine","vault","CMarginVault").entrySet())hashes.put(entry.getKey(),g.read(g.output.resolve("code-"+entry.getValue()+".json")).path("runtimeCodeHash").asText());
        BigInteger quantity=FuturesMath.quantity(units(1000),10,BigInteger.valueOf(100_00000000L));
        for(String role:List.of("userA","userB")){
            g.transfer("treasury",g.addr(role),units(1000000));g.cash(role,VaultAction.DEPOSIT,units(3000));
            trade(role,0,0,quantity,BigInteger.valueOf(100_00000000L));trade(role,4,1,quantity,BigInteger.valueOf(105_00000000L));
            for(int action:List.of(0,4)){
                String summary="public-trade-"+role+"-"+action+".json";
                Files.copy(g.output.resolve(summary),publicOutput.resolve(summary));
            }
            eq(g.balance(g.addr(role)),units(997000));eq(g.free(g.addr(role)),units(3500));
            eq(g.callNum(g.vault,"lockedBalance(address)",addressWord(g.addr(role))),Z);
            String state=g.call(g.rules,"getPosition(bytes32)",position(role));eq(wordNum(state,6),BigInteger.valueOf(3));
            String suffix=role.equals("userA")?"A":"B";
            meta.put(role,g.addr(role)).put("position"+suffix,position(role));
            meta.putObject("expected"+suffix).put("walletBalance",units(997000).toString()).put("free",units(3500).toString()).put("locked","0").put("positionStatus","CLOSED");
        }
        g.cashState(units(7000),Z,units(99000));eq(g.callNum(g.vault,"totalUnpaidProfit()"),Z);
        meta.put("fixtureTransactions",g.recordId).put("realMetaMask",false).put("chainId",CHAIN.toString());
        Files.writeString(publicOutput.resolve("test-chain.json"),meta.toString(),StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args){
        try{
            if(Arrays.equals(args,new String[]{"--self-test"})){
                check(!position("userA").equals(position("userB")),"CI_POSITION_DISTINCT");
                eq(FuturesMath.quantity(units(1000),10,BigInteger.valueOf(100_00000000L)),units(100));
                System.out.println("PRODUCT_READ_FIXTURE_PURE_PASS_NOT_EVM");return;
            }
            if(!Arrays.equals(args,new String[]{"/artifacts","/private-evidence","/fixture","/keys"}))throw new IllegalArgumentException();
            new ProductReadFixture(Path.of(args[0]),Path.of(args[1])).run(Path.of(args[2]),Path.of(args[3]));
            System.out.println("PRODUCT_READ_NEW_FIXTURE_PASS");
        }catch(Exception safe){System.err.println("PRODUCT_READ_FIXTURE_STOP_NO_RETRY");System.exit(1);}
    }
}
