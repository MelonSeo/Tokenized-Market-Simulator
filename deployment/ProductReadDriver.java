import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.custody.runtime.*;
import java.math.BigInteger;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** Independent TEST EOA actor. It signs wallet-link messages only and never broadcasts a transaction. */
public final class ProductReadDriver {
    static final ObjectMapper J=new ObjectMapper().findAndRegisterModules();
    static void check(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    record Header(String number,String hash,String parentHash,String timestamp){
        Header {
            check(number!=null&&number.matches("0x(?:0|[1-9a-f][0-9a-f]{0,63})"),"HEADER_NUMBER");
            check(hash!=null&&hash.matches("0x[0-9a-f]{64}")&&parentHash!=null&&parentHash.matches("0x[0-9a-f]{64}"),"HEADER_HASH");
            check(timestamp!=null&&timestamp.matches("0x(?:0|[1-9a-f][0-9a-f]{0,63})"),"HEADER_TIMESTAMP");
        }
        static Header from(JsonNode n){check(n!=null&&n.isObject(),"HEADER_OBJECT");return new Header(n.path("number").asText(),n.path("hash").asText(),n.path("parentHash").asText(),n.path("timestamp").asText());}
    }
    /** Same single-provider pin/recheck and 64-call/15-second bounds as the product reader. */
    static final class Snapshot {
        final CReadRpc rpc;final String genesis;final Header point;final LongSupplier nano;final long started;int calls;
        Snapshot(CReadRpc rpc,String genesis){this(rpc,genesis,System::nanoTime);}
        Snapshot(CReadRpc rpc,String genesis,LongSupplier nano){
            this.rpc=rpc;this.genesis=genesis;this.nano=nano;started=nano.getAsLong();
            check(read("eth_chainId",List.of()).asText().equals("0x7a69"),"CHAIN_ID");
            var g=Header.from(read("eth_getBlockByNumber",List.of("0x0",false)));
            check(g.number().equals("0x0")&&g.hash().equals(genesis),"TEST_GENESIS");
            point=Header.from(read("eth_getBlockByNumber",List.of("latest",false)));
        }
        JsonNode read(String method,List<Object> params){
            long remaining=CCustodyRpcReader.SNAPSHOT_NANOS-(nano.getAsLong()-started);
            check(++calls<=CCustodyRpcReader.MAX_CALLS&&remaining>0,"RPC_SNAPSHOT_LIMIT");
            var out=rpc.request(method,params,Math.min(3000,Math.max(1,remaining/1_000_000)));
            check(nano.getAsLong()-started<CCustodyRpcReader.SNAPSHOT_NANOS,"RPC_SNAPSHOT_LIMIT");return out;
        }
        Map<String,Object> pinned(){return Map.of("blockHash",point.hash(),"requireCanonical",true);}
        String code(String owner){return read("eth_getCode",List.of(owner,pinned())).asText();}
        String call(String to,String sig){return read("eth_call",List.of(Map.of("to",to,"data",Hash.sha3String(sig).substring(0,10)),pinned())).asText();}
        void finish(){
            check(point.equals(Header.from(read("eth_getBlockByNumber",List.of(point.number(),false)))),"HEADER_CHANGED");
            var g=Header.from(read("eth_getBlockByNumber",List.of("0x0",false)));
            check(g.number().equals("0x0")&&g.hash().equals(genesis),"GENESIS_CHANGED");
        }
    }
    record BootstrapObservation(CCustodyIdentity identity,Header block){}
    static String address(String data){return "0x"+data.substring(data.length()-40);}
    static BootstrapObservation observe(CReadRpc rpc,JsonNode meta){
        var contracts=new LinkedHashMap<String,CCustodyIdentity.Contract>();var snapshot=new Snapshot(rpc,meta.path("genesisHash").asText());
        for(String n:List.of("token","controller","rules","vault")){
            String a=meta.path(n).asText(),code=snapshot.code(a);check(code.length()>2,"CODE");
            check(Hash.sha3(code).equals(meta.path("codeHashes").path(n).asText()),"TEST_CODE_HASH");contracts.put(n,new CCustodyIdentity.Contract(a,Hash.sha3(code)));
        }
        String c=meta.path("controller").asText(),r=meta.path("rules").asText();
        var id=new CCustodyIdentity(BigInteger.valueOf(31337),snapshot.genesis,contracts.get("token"),contracts.get("controller"),contracts.get("rules"),contracts.get("vault"),address(snapshot.call(c,"relayer()")),address(snapshot.call(r,"priceSigner()")),address(snapshot.call(c,"pauseAdmin()")),snapshot.call(r,"supportedSymbolHash()"),snapshot.call(r,"policyHash()"),"http://127.0.0.1:32171");
        snapshot.finish();return new BootstrapObservation(id,snapshot.point);
    }
    static void verifyBaseline(CReadRpc rpc,JsonNode baseline,String genesis){
        Header expected=J.convertValue(baseline.path("head"),Header.class);var snapshot=new Snapshot(rpc,genesis);
        // Stronger test-fixture condition only: this no-transaction clone must not advance its head.
        check(snapshot.point.equals(expected),"READ_ONLY_CHAIN_UNCHANGED");snapshot.finish();
    }
    static void bootstrap(Path fixture,Path config)throws Exception{
        var meta=J.readTree(fixture.resolve("test-chain.json").toFile());
        try(var rpc=new CCustodyRpcClient(java.net.URI.create("http://anvil:8545"))){
            var observed=observe(rpc,meta);
            J.writeValue(fixture.resolve("c-identity.json").toFile(),observed.identity());
            J.writeValue(fixture.resolve("chain-read-baseline.json").toFile(),Map.of("head",observed.block()));
            var manifest=new Properties();manifest.setProperty("runId",System.getenv("READ_RUN_ID"));FullAppReadDriver.bootstrap(manifest,fixture,config);
            var p=new Properties();try(var in=Files.newInputStream(config.resolve("application.properties"))){p.load(in);}
            p.setProperty("app.c-futures.direct.enabled","false");
            p.setProperty("server.address","0.0.0.0");
            try(var out=Files.newOutputStream(config.resolve("application.properties"))){p.store(out,"new isolated product read only");}
        }
    }
    static FullAppReadDriver.Reply http(HttpClient c,String method,String path,String token,Object data)throws Exception{return ProductReadConnection.http(c,method,path,token,data);}
    static String signature(String message,Credentials key){var s=Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8),key.getEcKeyPair());return Numeric.toHexString(s.getR())+Numeric.toHexString(s.getS()).substring(2)+Numeric.toHexString(s.getV()).substring(2);}
    static void verify(Path fixture,Path evidence)throws Exception{
        var meta=J.readTree(fixture.resolve("test-chain.json").toFile());var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var users=new ArrayList<Map<String,String>>();String password=FullAppReadDriver.secret("FULL_READ_TEST_PASSWORD");
        check(http(client,"GET","/api/c-futures/account",null,null).status()==401,"ANONYMOUS_401");
        for(int n=0;n<2;n++){
            String label=n==0?"A":"B",login="read-user-"+label.toLowerCase(),wallet=meta.path("user"+label).asText();
            var signup=http(client,"POST","/api/auth/signup",null,Map.of("loginId",login,"password",password,"nickname","Read User "+label));check(signup.status()==200||signup.status()==201,"SIGNUP");
            var loginReply=http(client,"POST","/api/auth/login",null,Map.of("loginId",login,"password",password));check(loginReply.status()==200,"LOGIN");String token=loginReply.body().path("accessToken").asText();check(!token.isBlank(),"JWT");
            check(http(client,"GET","/api/c-futures/account",token,null).status()==404,"UNLINKED_NO_ASSETS");
            var challenge=http(client,"POST","/api/c-futures/wallet/challenges",token,Map.of("wallet",wallet));check(challenge.status()==200,"CHALLENGE");
            var key=Credentials.create(FullAppReadDriver.secret("READ_TEST_KEY_"+label));check(key.getAddress().equalsIgnoreCase(wallet),"TEST_ACTOR_OWNER");
            var body=Map.of("challengeId",challenge.body().path("id").asText(),"signature",signature(challenge.body().path("message").asText(),key));
            check(http(client,"POST","/api/c-futures/wallet/link",token,body).status()==200,"LINK");
            check(http(client,"POST","/api/c-futures/wallet/link",token,body).status()==409,"PROOF_REPLAY");
            var account=http(client,"GET","/api/c-futures/account",token,null);check(account.status()==200&&account.body().path("value").path("owner").asText().equals(wallet),"OWN_ACCOUNT");
            var position=http(client,"GET","/api/c-futures/positions/"+meta.path("position"+label).asText(),token,null);check(position.status()==200&&position.body().path("value").path("owner").asText().equals(wallet),"OWN_POSITION");
            // New CI recipe has independently chain-checked economics, unlike historical inputs.
            if(meta.has("expected"+label)){
                var expected=meta.path("expected"+label);
                for(String field:List.of("walletBalance","free","locked"))check(account.body().path("value").path(field).asText().equals(expected.path(field).asText()),"FIXTURE_ACCOUNT_VALUE");
                check(position.body().path("value").path("status").asText().equals(expected.path("positionStatus").asText()),"FIXTURE_POSITION_STATUS");
            }
            var foreign=http(client,"GET","/api/c-futures/positions/"+meta.path(n==0?"positionB":"positionA").asText(),token,null);check(foreign.status()==404,"FOREIGN_POSITION");
            var cfg=http(client,"GET","/api/c-futures/config",token,null);check(cfg.status()==200&&cfg.body().path("value").path("readOnly").asText().equals("true"),"READ_ONLY");
            check(account.body().path("block").path("hash").asText().equals(position.body().path("block").path("hash").asText()),"SAME_TEST_BLOCK");
            users.add(Map.of("login",login,"wallet",wallet,"positionId",meta.path("position"+label).asText(),"walletBalance",account.body().path("value").path("walletBalance").asText(),"free",account.body().path("value").path("free").asText(),"locked",account.body().path("value").path("locked").asText(),"blockHash",account.body().path("block").path("hash").asText(),"positionStatus",position.body().path("value").path("status").asText()));
        }
        var market=http(client,"GET","/api/markets/mSEC",null,null);check(market.status()==200&&market.body().path("priceStatus").asText().equals("SIMULATED"),"ACTUAL_SYNTHETIC");
        check(http(client,"GET","/api/markets/mSEC/candles?interval=1m&limit=20",null,null).status()==200,"CANDLES");
        check(http(client,"POST","/api/c-futures/direct/intents",null,Map.of()).status()==403,"FINANCIAL_DENIED");
        try(var rpc=new CCustodyRpcClient(java.net.URI.create("http://anvil:8545"))){verifyBaseline(rpc,J.readTree(fixture.resolve("chain-read-baseline.json").toFile()),meta.path("genesisHash").asText());}
        var report=Map.of("status","ACTUAL_HTTP_PG_RPC_READ_PASS_NOT_METAMASK_OR_TRADING","users",users,"anonymous401",true,"unlinked404",true,"foreignPosition404",true,"proofReplay409",true,"financialCalls",0);
        J.writeValue(evidence.resolve("api-result.json").toFile(),report);
        // Private browser-only credentials, never written to a public report.
        J.writeValue(evidence.resolve("browser.private.json").toFile(),Map.of("users",users,"password",password,"chainId","31337","genesisHash",meta.path("genesisHash").asText(),"origin","http://127.0.0.1:32171"));
    }
    public static void main(String[] args){try{Path f=Path.of("/fixture"),e=Path.of("/evidence");if(args[0].equals("bootstrap"))bootstrap(f,Path.of("/gate-config"));else if(args[0].equals("verify"))verify(f,e);else throw new IllegalArgumentException();System.out.println("PRODUCT_READ_"+args[0].toUpperCase()+"_PASS");}catch(Exception failure){String m=failure.getMessage();System.err.println("PRODUCT_READ_FAILED_"+(m!=null&&m.matches("[A-Z_]{1,80}")?m:failure.getClass().getSimpleName()));System.exit(1);}}
}
