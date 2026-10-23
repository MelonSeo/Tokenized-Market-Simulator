import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.VaultAction;
import com.pricetrack.exchange.custody.runtime.CCustodyIdentity;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import org.web3j.crypto.*;

/** Fresh browser fixture bootstrap. The external owner is an ADDRESS ONLY, never a generated key. */
public final class CDirectBrowserBootstrap {
    static String owner(String value) {
        String address=CAbiModels.address(value);
        if(address.equals("0x"+"0".repeat(40)))throw new IllegalArgumentException("C_BROWSER_ZERO_OWNER");
        return address;
    }
    public static void main(String[] args) {
        CDirectDemoDriver d=null;String stage="ARGS";
        try {
            if(args.length==1&&args[0].equals("--self-test")){selfTest();System.out.println("C_BROWSER_BOOTSTRAP_NO_SOCKET_SELF_TEST_PASS");return;}
            CAnvilContractGate.verifyTransportPolicy();
            if(args.length!=6)throw new IllegalArgumentException("C_BROWSER_ARGS");
            d=new CDirectDemoDriver("bootstrap",Path.of(args[0]),Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),Path.of(args[4]));
            String external=owner(args[5]);
            if(!d.manifest.path("browserOwner").asText().equals(external)||!d.manifest.path("browserExternalOwnerOnly").asBoolean())throw new IllegalArgumentException("C_BROWSER_MANIFEST");
            stage="SOURCE";d.g.verifySourceMetadata();stage="CHAIN";chain(d,external);
            stage="POSTGRES";d.databaseBootstrap();stage="KEYLESS_CONFIG";d.writeConfig();
            // Only this NEW owned browser configuration is extended, before App startup.
            // Short HTTP keep-alive avoids occupying the bounded opaque relay worker slots.
            Files.writeString(d.appConfig.resolve("application.properties"),sessionProperties(d.manifest.path("lifetimeSeconds").intValue()),java.nio.charset.StandardCharsets.US_ASCII,StandardOpenOption.APPEND);
            d.g.save("phase-result.json",CAnvilContractGate.JSON.createObjectNode().put("runId",d.run).put("sourceIdentity",d.source).put("phase","bootstrap").put("result","PASS").put("externalOwner",external).put("externalOwnerPrivateKeyGenerated",false).put("realMetaMask",false).put("ownerProofPending",true));
            System.out.println("C_BROWSER_BOOTSTRAP_PASS_OWNER_PROOF_PENDING");
        }catch(Exception safe){
            if(d!=null)try{d.g.save("phase-failure.json",CAnvilContractGate.JSON.createObjectNode().put("stage",stage).put("exceptionType",safe.getClass().getName()));}catch(Exception ignored){}
            System.err.println("C_BROWSER_BOOTSTRAP_STOP_NO_RETRY");System.exit(1);
        }
    }
    static void selfTest(){
        if(!sessionProperties(3600).contains("app.jwt.validity-ms=3600000\n")||!sessionProperties(900).contains("app.c-demo.extended-user-session=false\n"))throw new AssertionError("C_BROWSER_SESSION_CONFIG");
        for(int bad:new int[]{0,29,3601})try{sessionProperties(bad);throw new AssertionError("C_BROWSER_UNBOUNDED_SESSION");}catch(IllegalArgumentException expected){}
        for(var method:CDirectBrowserBootstrap.class.getDeclaredMethods())if(!method.isSynthetic())for(var parameter:method.getParameters())if(!parameter.isNamePresent())throw new AssertionError("C_BROWSER_PARAMETER_METADATA");
        String good="0x1234567890abcdef1234567890abcdef12345678";
        if(!owner(good).equals(good))throw new AssertionError("C_BROWSER_OWNER");
        for(String bad:List.of("0x"+"0".repeat(40),"0x1234","http://owner",good+"\n","")){
            try{owner(bad);throw new AssertionError("C_BROWSER_BAD_OWNER_ACCEPTED");}catch(IllegalArgumentException expected){}
        }
    }
    static String sessionProperties(int seconds){
        if(seconds<30||seconds>3600)throw new IllegalArgumentException("C_BROWSER_SESSION_BOUND");
        return "\nserver.tomcat.max-keep-alive-requests=1\nserver.tomcat.keep-alive-timeout=2s\napp.c-demo.extended-user-session="+(seconds>1800)+"\napp.jwt.validity-ms="+(seconds*1000L)+"\n";
    }
    static void chain(CDirectDemoDriver d,String external)throws Exception {
        var g=d.g;
        for(String role:List.of("treasury","admin","relayer","oracle","userB"))g.keys.put(role,Keys.createEcKeyPair(new SecureRandom()));
        if(g.keys.containsKey("userA")||g.keys.values().stream().map(CAnvilContractGate::address).distinct().count()!=5||g.keys.values().stream().map(CAnvilContractGate::address).anyMatch(external::equals))throw new IllegalStateException("C_BROWSER_ROLE_KEYS");
        ObjectNode keys=CAnvilContractGate.JSON.createObjectNode(),addresses=CAnvilContractGate.JSON.createObjectNode();
        for(var entry:g.keys.entrySet()){keys.put(entry.getKey(),String.format("%064x",entry.getValue().getPrivateKey()));addresses.put(entry.getKey(),CAnvilContractGate.address(entry.getValue()));}
        addresses.put("userA",external);
        Files.write(d.privateBase.resolve("keys.private.json"),CAnvilContractGate.JSON.writeValueAsBytes(keys),StandardOpenOption.CREATE_NEW);
        Files.write(d.privateBase.resolve("addresses.json"),CAnvilContractGate.JSON.writeValueAsBytes(addresses),StandardOpenOption.CREATE_NEW);
        if(!CAnvilContractGate.number(g.rpc("eth_chainId")).equals(CAnvilContractGate.CHAIN)||!g.rpc("eth_getCode",external,"latest").asText().equals("0x"))throw new IllegalStateException("C_BROWSER_CHAIN_OR_EOA");
        g.genesis=g.rpc("eth_getBlockByNumber","0x0",false).path("hash").asText();
        for(var key:g.keys.values())g.rpc("anvil_setBalance",CAnvilContractGate.address(key),CAnvilContractGate.hex(CAnvilContractGate.units(100)));
        g.rpc("anvil_setBalance",external,CAnvilContractGate.hex(CAnvilContractGate.units(100)));
        g.symbol=Hash.sha3String("mSEC");g.policy=Hash.sha3String("TMS_C_FIFO_CLAIMS_MMR500_GRACE60_V1");
        g.token=g.deploy("CMockCollateral",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.addr("treasury"))),g.keys.get("treasury"));
        g.controller=g.deploy("CCollateralizedFuturesController",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.token),CAnvilContractGate.addressWord(g.addr("relayer")),CAnvilContractGate.addressWord(g.addr("oracle")),CAnvilContractGate.addressWord(g.addr("admin")),g.symbol,g.policy),g.keys.get("treasury"));
        g.rules=g.callAddress(g.controller,"rules()");g.vault=g.callAddress(g.controller,"vault()");
        for(var entry:Map.of("CMockCollateral",g.token,"CCollateralizedFuturesController",g.controller,"FuturesEngine",g.rules,"CMarginVault",g.vault).entrySet())g.verifyRuntime(entry.getKey(),entry.getValue());
        d.identity=new CCustodyIdentity(CAnvilContractGate.CHAIN,g.genesis,d.binding(g.token),d.binding(g.controller),d.binding(g.rules),d.binding(g.vault),g.addr("relayer"),g.addr("oracle"),g.addr("admin"),g.symbol,g.policy,d.origin);
        if(!g.callAddress(g.rules,"executor()").equals(g.controller)||!g.callAddress(g.rules,"priceSigner()").equals(d.identity.oracle())||!g.callAddress(g.vault,"controller()").equals(g.controller)||!g.callAddress(g.vault,"asset()").equals(g.token))throw new IllegalStateException("C_BROWSER_ROLE_BINDING");
        g.transfer("treasury",external,CAnvilContractGate.units(1_000_000));g.transfer("treasury",g.addr("userB"),CAnvilContractGate.units(1_000_000));g.cash("treasury",VaultAction.FUND_HOUSE,CAnvilContractGate.units(10_000_000));
        CAnvilContractGate.eq(g.balance(g.addr("treasury")),CAnvilContractGate.units(8_000_000));CAnvilContractGate.eq(g.balance(external),CAnvilContractGate.units(1_000_000));g.cashState(BigInteger.ZERO,BigInteger.ZERO,CAnvilContractGate.units(10_000_000));
        Files.write(d.privateBase.resolve("identity.json"),CAnvilContractGate.JSON.writeValueAsBytes(d.identity),StandardOpenOption.CREATE_NEW);
        g.save("capital.json",CAnvilContractGate.JSON.createObjectNode().put("supply","20000000").put("house","10000000").put("userAllocations","2000000").put("treasuryReserve","8000000").put("externalOwner",external).put("externalOwnerPrivateKeyGenerated",false).put("ownerProofPending",true));
    }
}
