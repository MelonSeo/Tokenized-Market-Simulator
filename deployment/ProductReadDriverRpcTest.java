import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pricetrack.exchange.custody.runtime.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.web3j.crypto.Hash;

/** No socket/PG/keys: exercise the actual Driver methods with the actual product parameter validator. */
public final class ProductReadDriverRpcTest {
    static final ObjectMapper J=new ObjectMapper();static int passed;
    static String h(int n){return "0x"+Integer.toHexString(n).repeat(64);}
    static String a(int n){return "0x"+Integer.toHexString(n).repeat(40);}
    static ObjectNode header(String number,String hash,String parent,String timestamp){return J.createObjectNode().put("number",number).put("hash",hash).put("parentHash",parent).put("timestamp",timestamp);}
    static final ObjectNode HEAD=header("0x2",h(2),h(1),"0xa"),GENESIS=header("0x0",h(1),h(0),"0x0");
    static void check(boolean yes){if(!yes)throw new AssertionError();}
    static void policy(String method,List<Object> params){
        try{var m=CCustodyRpcClient.class.getDeclaredMethod("validateRequest",String.class,List.class);m.setAccessible(true);m.invoke(null,method,params);}
        catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    static void rejects(Runnable action){try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError("Expected fail-closed result");}
    static final class Rpc implements CReadRpc {
        int calls,latestReads,genesisReads;boolean reorg,parentChanged,genesisChanged,malformed,missing,wrongChain;
        final List<Map<String,Object>> pins=new ArrayList<>();
        public JsonNode request(String method,List<Object> p,long millis){
            policy(method,p);check(millis>0&&millis<=3000);calls++;
            if(method.equals("eth_chainId"))return TextNode.valueOf(wrongChain?"0x1":"0x7a69");
            if(method.equals("eth_getBlockByNumber")){
                String n=(String)p.get(0);
                if(n.equals("0x0")){genesisReads++;return genesisChanged&&genesisReads>1?header("0x0",h(8),h(0),"0x0"):GENESIS.deepCopy();}
                if(n.equals("latest")){latestReads++;var out=HEAD.deepCopy();if(malformed)out.put("hash","not-a-hash");if(missing)out.remove("timestamp");return out;}
                check(n.equals("0x2"));return header("0x2",reorg?h(3):h(2),parentChanged?h(9):h(1),"0xa");
            }
            @SuppressWarnings("unchecked") Map<String,Object> pin=(Map<String,Object>)p.get(1);pins.add(pin);check(pin.equals(Map.of("blockHash",h(2),"requireCanonical",true)));
            if(method.equals("eth_getCode"))return TextNode.valueOf("0x6001");
            if(method.equals("eth_call")){
                String selector=(String)((Map<?,?>)p.get(0)).get("data");
                for(var entry:Map.of("relayer()",a(5),"priceSigner()",a(6),"pauseAdmin()",a(7),"supportedSymbolHash()",h(8),"policyHash()",h(9)).entrySet())
                    if(Hash.sha3String(entry.getKey()).startsWith(selector)){String v=entry.getValue().substring(2);return TextNode.valueOf("0x"+"0".repeat(64-v.length())+v);}
            }
            throw new AssertionError("Unexpected RPC");
        }
    }
    static ObjectNode meta(){
        var m=J.createObjectNode().put("genesisHash",h(1));var hashes=m.putObject("codeHashes");int n=1;
        for(String role:List.of("token","controller","rules","vault")){m.put(role,a(n++));hashes.put(role,Hash.sha3("0x6001"));}return m;
    }
    static void test(Runnable r){r.run();passed++;}
    public static void main(String[] args){
        check(Arrays.equals(args,new String[]{"--self-test"}));
        test(()->{var rpc=new Rpc();var out=ProductReadDriver.observe(rpc,meta());check(out.block().hash().equals(h(2))&&out.identity().chainId().intValueExact()==31337);check(rpc.pins.size()==9&&rpc.latestReads==1&&rpc.genesisReads==2);});
        test(()->rejects(()->policy("eth_getCode",List.of(a(1),"latest"))));
        test(()->rejects(()->policy("eth_call",List.of(Map.of("to",a(1),"data","0x12345678"),"latest"))));
        test(()->rejects(()->policy("eth_blockNumber",List.of())));
        test(()->{var rpc=new Rpc();var s=new ProductReadDriver.Snapshot(rpc,h(1));s.code(a(1));s.call(a(2),"relayer()");s.finish();check(rpc.pins.size()==2&&rpc.pins.get(0).equals(rpc.pins.get(1)));});
        test(()->{var rpc=new Rpc();var s=new ProductReadDriver.Snapshot(rpc,h(1));rpc.reorg=true;rejects(s::finish);});
        test(()->{var rpc=new Rpc();var s=new ProductReadDriver.Snapshot(rpc,h(1));rpc.parentChanged=true;rejects(s::finish);});
        test(()->{var rpc=new Rpc();var s=new ProductReadDriver.Snapshot(rpc,h(1));rpc.genesisChanged=true;rejects(s::finish);});
        test(()->{var rpc=new Rpc();rpc.malformed=true;rejects(()->new ProductReadDriver.Snapshot(rpc,h(1)));});
        test(()->{var rpc=new Rpc();rpc.missing=true;rejects(()->new ProductReadDriver.Snapshot(rpc,h(1)));});
        test(()->{var rpc=new Rpc();rpc.wrongChain=true;rejects(()->new ProductReadDriver.Snapshot(rpc,h(1)));});
        test(()->{var b=J.createObjectNode();b.set("head",HEAD.deepCopy());ProductReadDriver.verifyBaseline(new Rpc(),b,h(1));});
        test(()->{var b=J.createObjectNode();b.set("head",header("0x2",h(4),h(1),"0xa"));rejects(()->ProductReadDriver.verifyBaseline(new Rpc(),b,h(1)));});
        test(()->{var m=meta();((ObjectNode)m.get("codeHashes")).put("token",h(0));rejects(()->ProductReadDriver.observe(new Rpc(),m));});
        test(()->{var nano=new AtomicLong();var rpc=new Rpc();var s=new ProductReadDriver.Snapshot(rpc,h(1),nano::get);nano.set(CCustodyRpcReader.SNAPSHOT_NANOS);rejects(()->s.code(a(1)));check(rpc.calls==3);});
        test(()->{var rpc=new Rpc();var s=new ProductReadDriver.Snapshot(rpc,h(1));rejects(()->{for(int n=0;n<70;n++)s.code(a(1));});check(rpc.calls==CCustodyRpcReader.MAX_CALLS);});
        System.out.println("{\"scope\":\"PURE_DRIVER_PRODUCT_RPC_VALIDATOR_NOT_NETWORK\",\"passed\":"+passed+",\"failed\":0}");
    }
}
