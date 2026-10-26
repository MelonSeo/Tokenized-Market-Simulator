import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.runtime.*;
import com.sun.net.httpserver.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** Isolated trial EOA wallet actor, never Spring, a user wallet, or production relay. */
public final class ProductDirectDriver {
 static final ObjectMapper J=ProductReadDriver.J;static void need(boolean b,String c){ProductReadDriver.check(b,"DIRECT_"+c);}
 static String secret(String name){return FullAppReadDriver.secret(name);}
 static void bootstrap()throws Exception{
  Path fixture=Path.of("/fixture"),config=Path.of("/gate-config");ProductReadDriver.bootstrap(fixture,config);
  var p=new Properties();try(var in=Files.newInputStream(config.resolve("application.properties"))){p.load(in);}
  String run=System.getenv("READ_RUN_ID"),suffix=run.substring(5),owner="ccowner_"+suffix,api="ccapi_"+suffix;
  try(var c=DriverManager.getConnection("jdbc:postgresql://pg:5432/ccpg_"+suffix+"?connectTimeout=3&socketTimeout=8",owner,secret("FULL_READ_OWNER_PASSWORD"));var s=c.createStatement()){
   s.setQueryTimeout(5);for(String name:List.of("c-consent-schema.sql","c-direct-execution-schema.sql")){
    try(var in=ProductDirectDriver.class.getResourceAsStream("/"+name)){need(in!=null,"SQL_RESOURCE");s.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
   }
   s.execute("GRANT USAGE ON SCHEMA tms_c_execution TO "+api);
   s.execute("GRANT SELECT,INSERT ON tms_c_execution.drafts,tms_c_execution.consents,tms_c_execution.direct_execution_claims,tms_c_execution.direct_executions TO "+api);
  }
  p.setProperty("app.c-futures.direct.enabled","true");p.setProperty("app.c-futures.direct.oracle-token-file","/gate-config/oracle-token.private");
  try(var out=Files.newOutputStream(config.resolve("application.properties"))){p.store(out,"fresh owner-direct product integration; keyless Spring");}
  Files.writeString(config.resolve("oracle-token.private"),secret("DIRECT_ORACLE_TOKEN"),StandardOpenOption.CREATE_NEW);
  Files.writeString(Path.of("/oracle-config/auth-token.private"),secret("DIRECT_ORACLE_TOKEN"),StandardOpenOption.CREATE_NEW);
  Files.copy(fixture.resolve("c-identity.json"),Path.of("/oracle-config/identity.json"));
 }
 final Path evidence=Path.of("/wallet-evidence");final CAnvilContractGate g=new CAnvilContractGate(Path.of("/app-fixture/artifacts"),evidence);
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 final List<Map<String,String>> transactions=new ArrayList<>();final Set<String> sent=new HashSet<>();
 CCustodyIdentity identity;JsonNode meta;String tokenA,tokenB;volatile boolean finished;String position;
 FullAppReadDriver.Reply api(String method,String path,String token,Object body)throws Exception{return ProductReadDriver.http(http,method,path,token,body);}
 void initialize()throws Exception{
  g.verifySourceMetadata();identity=J.readValue(Path.of("/fixture/c-identity.json").toFile(),CCustodyIdentity.class);meta=J.readTree(Path.of("/fixture/test-chain.json").toFile());
  var keys=J.readTree(Path.of("/keys/wallet-keys.private.json").toFile());for(String role:List.of("userA","userB"))g.keys.put(role,ECKeyPair.create(new BigInteger(keys.path(role).asText(),16)));
  g.token=identity.token().address();g.controller=identity.controller().address();g.rules=identity.rules().address();g.vault=identity.vault().address();
  need(api("GET","/api/c-futures/account",null,null).status()==401,"ANONYMOUS");
  for(String role:List.of("a","b")){
   String login="direct-user-"+role;var signup=api("POST","/api/auth/signup",null,Map.of("loginId",login,"password",secret("FULL_READ_TEST_PASSWORD"),"nickname","Direct Trial "+role));need(signup.status()==201,"SIGNUP");
   var loginReply=api("POST","/api/auth/login",null,Map.of("loginId",login,"password",secret("FULL_READ_TEST_PASSWORD")));need(loginReply.status()==200,"LOGIN");
   if(role.equals("a"))tokenA=loginReply.body().path("accessToken").asText();else tokenB=loginReply.body().path("accessToken").asText();
  }
  need(api("GET","/api/c-futures/config",tokenA,null).body().path("value").path("readOnly").asText().equals("false"),"DIRECT_ON");
  // Second user's ownership proof is made here; first user's proof must come from actual browser UI.
  var challenge=api("POST","/api/c-futures/wallet/challenges",tokenB,Map.of("wallet",g.addr("userB")));
  var proof=Map.of("challengeId",challenge.body().path("id").asText(),"signature",ProductReadDriver.signature(challenge.body().path("message").asText(),Credentials.create(g.keys.get("userB"))));
  need(api("POST","/api/c-futures/wallet/link",tokenB,proof).status()==200,"OTHER_LINK");need(api("POST","/api/c-futures/wallet/link",tokenB,proof).status()==409,"PROOF_REPLAY");
  J.writeValue(evidence.resolve("browser.private.json").toFile(),Map.of("origin",identity.origin(),"login","direct-user-a","password",secret("FULL_READ_TEST_PASSWORD"),"wallet",g.addr("userA"),"walletToken",secret("DIRECT_WALLET_TOKEN"),"chainId","31337","genesisHash",identity.genesisHash()));
 }
 synchronized Object wallet(JsonNode req)throws Exception{
  String method=req.path("method").asText();JsonNode params=req.path("params");
  if(Set.of("eth_accounts","eth_requestAccounts").contains(method))return List.of(g.addr("userA"));
  if(method.equals("eth_chainId"))return "0x7a69";
  if(method.equals("eth_getBlockByNumber")){need(params.size()==2&&(params.get(0).asText().equals("0x0")||params.get(0).asText().matches("0x[0-9a-f]+")),"BLOCK");return g.rpc(method,params.get(0).asText(),false);}
  if(Set.of("eth_getTransactionByHash","eth_getTransactionReceipt").contains(method)){need(params.size()==1&&params.get(0).asText().matches("0x[0-9a-f]{64}"),"HASH");return g.rpc(method,params.get(0).asText());}
  if(method.equals("personal_sign")){
   need(params.size()==2&&params.get(1).asText().equals(g.addr("userA")),"PROOF_OWNER");String message=new String(Numeric.hexStringToByteArray(params.get(0).asText()),StandardCharsets.UTF_8);
   need(message.startsWith("TMS C wallet link only. NOT a trade approval or token allowance.\n")&&message.contains("\nWallet: "+g.addr("userA")+"\n")&&message.contains("\nDeployment: "+identity.deploymentId()+"\n"),"PROOF_SCOPE");
   return ProductReadDriver.signature(message,Credentials.create(g.keys.get("userA")));
  }
  need(method.equals("eth_sendTransaction")&&params.size()==1&&transactions.size()<5,"METHOD_OR_LIMIT");JsonNode tx=params.get(0);
  need(tx.path("from").asText().equals(g.addr("userA"))&&tx.path("chainId").asText().equals("0x7a69")&&tx.path("value").asText().equals("0x0"),"TX_OWNER_CHAIN");
  String to=tx.path("to").asText(),data=tx.path("data").asText(),kind;Call call=g.abi.decode(to.equals(g.token)?Target.TOKEN:to.equals(g.vault)?Target.VAULT:Target.CONTROLLER,data);
  if(call instanceof Approve a){need(to.equals(g.token)&&a.spender().equals(g.vault)&&a.amount().equals(CAnvilContractGate.units(3000)),"APPROVE");kind="APPROVE";}
  else if(call instanceof VaultAmount a){need(to.equals(g.vault),"VAULT");kind=a.action().name();need(kind.equals("DEPOSIT")&&a.amount().equals(CAnvilContractGate.units(3000))||kind.equals("WITHDRAW")&&a.amount().equals(g.free(g.addr("userA"))),"ASSET_AMOUNT");}
  else{need(call instanceof Direct&&to.equals(g.controller),"DIRECT_CALL");Direct d=(Direct)call;need(d.intent().owner().equals(g.addr("userA")),"INTENT_OWNER");g.crypto.verifyReport(d.report(),identity.chainId(),g.rules,identity.oracle(),d.priceSignature());position=d.intent().positionId();kind=d.report().action().signum()==0?"OPEN":"CLOSE";}
  need(kind.equals(List.of("APPROVE","DEPOSIT","OPEN","CLOSE","WITHDRAW").get(transactions.size()))&&sent.add(to+data),"ORDER_OR_DUPLICATE");
  var receipt=g.send(g.keys.get("userA"),to,data,true);String hash=receipt.path("transactionHash").asText();
  var row=new LinkedHashMap<String,String>();row.put("kind",kind);row.put("txHash",hash);row.put("blockHash",receipt.path("blockHash").asText());
  if(call instanceof Direct d)row.put("intentId",d.intent().operationId());transactions.add(row);
  // Actual on-chain checkpoint after each wallet transaction, not UI-only success.
  need(g.balance(g.addr("userA")).add(g.free(g.addr("userA"))).add(g.callNum(g.vault,"lockedBalance(address)",CAnvilContractGate.addressWord(g.addr("userA"))))
   .add(g.callNum(g.vault,"houseBalance()")).equals(CAnvilContractGate.units(1100000)),"USER_HOUSE_CONSERVATION");
  if(kind.equals("OPEN"))need(g.free(g.addr("userA")).equals(CAnvilContractGate.units(2000))&&g.callNum(g.vault,"lockedBalance(address)",CAnvilContractGate.addressWord(g.addr("userA"))).equals(CAnvilContractGate.units(1000)),"OPEN_LOCK");
  return hash;
 }
 void finish()throws Exception{
  need(transactions.size()==5,"FIVE_TRANSACTIONS");BigInteger pnl=BigInteger.ZERO;
  for(var row:transactions){var r=api("GET","/api/c-futures/direct/transactions/"+row.get("txHash")+(row.containsKey("intentId")?"?intentId="+row.get("intentId"):""),tokenA,null);need(r.status()==200&&r.body().path("status").asText().equals("CONFIRMED")&&r.body().path("blockHash").asText().equals(row.get("blockHash")),"CANONICAL_RECEIPT");
   need(api("GET","/api/c-futures/direct/transactions/"+row.get("txHash"),tokenB,null).status()!=200,"FOREIGN_RECEIPT");if(row.get("kind").equals("CLOSE"))pnl=new BigInteger(r.body().path("realizedPnl").asText());
  }
  var account=api("GET","/api/c-futures/account",tokenA,null);need(account.status()==200,"FINAL_ACCOUNT");var v=account.body().path("value");
  need(v.path("free").asText().equals("0")&&v.path("locked").asText().equals("0")&&v.path("totalUnpaidProfit").asText().equals("0"),"FINAL_ZERO_LOCKS");
  need(new BigInteger(v.path("walletBalance").asText()).equals(CAnvilContractGate.units(1000000).add(pnl)),"WALLET_PNL");
  g.cashState(BigInteger.ZERO,BigInteger.ZERO,new BigInteger(v.path("house").asText()));
  need(g.balance(g.addr("userB")).equals(CAnvilContractGate.units(1000000))&&g.free(g.addr("userB")).signum()==0,"OTHER_USER_ASSETS");
  need(g.callNum(g.token,"totalSupply()").equals(CAnvilContractGate.SUPPLY)&&g.callNum(g.controller,"nonces(address)",CAnvilContractGate.addressWord(g.addr("userA"))).equals(BigInteger.TWO),"SUPPLY_CONTROLLER_NONCE");
  var pos=api("GET","/api/c-futures/positions/"+position,tokenA,null);need(pos.status()==200&&pos.body().path("value").path("status").asText().equals("CLOSED"),"CLOSED");need(api("GET","/api/c-futures/positions/"+position,tokenB,null).status()==404,"FOREIGN_POSITION");
  String suffix=System.getenv("READ_RUN_ID").substring(5);try(var c=DriverManager.getConnection("jdbc:postgresql://pg:5432/ccpg_"+suffix+"?connectTimeout=3&socketTimeout=8","ccowner_"+suffix,secret("FULL_READ_OWNER_PASSWORD"));var s=c.createStatement()){
   try(var r=s.executeQuery("SELECT (SELECT count(*) FROM tms_c_execution.drafts),(SELECT count(*) FROM tms_c_execution.direct_executions),(SELECT count(*) FROM public.orders),(SELECT count(*) FROM public.user_balances WHERE amount<>0 OR locked_amount<>0)")){need(r.next()&&r.getInt(1)==2&&r.getInt(2)==2&&r.getInt(3)==0&&r.getInt(4)==0,"PG_METADATA_NOT_LEGACY_ASSETS");}
  }
  J.writeValue(evidence.resolve("direct-result.json").toFile(),Map.of("status","PASS_TRIAL_EOA_NOT_METAMASK","transactions",transactions,"walletBalance",v.path("walletBalance").asText(),"free","0","locked","0","realizedPnl",pnl.toString(),"positionStatus","CLOSED","foreignPosition404",true,"keylessSpring",true,"realMetaMask",false));
 }
 void serve()throws Exception{
  initialize();var pool=Executors.newFixedThreadPool(2);var server=HttpServer.create(new InetSocketAddress("0.0.0.0",8092),4);server.setExecutor(pool);
  server.createContext("/",x->{try{need(x.getRequestMethod().equals("POST")&&secret("DIRECT_WALLET_TOKEN").equals(x.getRequestHeaders().getFirst("X-Trial-Wallet")),"WALLET_CAPABILITY");byte[] bytes=x.getRequestBody().readNBytes(65537);need(bytes.length<=65536,"WALLET_BODY");Object result;
    if(x.getRequestURI().getPath().equals("/finish")){finish();result=Map.of("status","PASS");finished=true;}else{need(x.getRequestURI().getPath().equals("/rpc"),"WALLET_PATH");result=wallet(J.readTree(bytes));}
    byte[] body=J.writeValueAsBytes(Map.of("result",result));x.sendResponseHeaders(200,body.length);try(var out=x.getResponseBody()){out.write(body);}
   }catch(Exception e){
    try{String code=e.getMessage();var safe=Map.of("safeCode",code!=null&&code.matches("[A-Z_]{1,100}")?code:"TRIAL_WALLET_FAILURE","causeType",e.getClass().getSimpleName());Files.write(evidence.resolve("wallet-failure.json"),J.writeValueAsBytes(safe),StandardOpenOption.CREATE_NEW);}catch(Exception preserved){}
    byte[] body="{\"error\":\"TRIAL_WALLET_STOP_NO_RETRY\"}".getBytes(StandardCharsets.UTF_8);try{x.sendResponseHeaders(409,body.length);try(var out=x.getResponseBody()){out.write(body);}}catch(Exception ignored){x.close();}}});
  try{server.start();Files.writeString(evidence.resolve("ready.json"),"{}",StandardOpenOption.CREATE_NEW);long end=System.nanoTime()+Duration.ofSeconds(180).toNanos();while(!finished&&System.nanoTime()<end&&!Files.exists(Path.of("/gate-control/STOP")))Thread.sleep(100);need(finished,"WALLET_INCOMPLETE");}finally{server.stop(1);pool.shutdownNow();}
 }
 public static void main(String[] args){try{CAnvilContractGate.verifyTransportPolicy();if(Arrays.equals(args,new String[]{"bootstrap"}))bootstrap();else if(Arrays.equals(args,new String[]{"wallet"}))new ProductDirectDriver().serve();else throw new IllegalArgumentException();}catch(Exception e){String m=e.getMessage();System.err.println(m!=null&&m.matches("[A-Z_]{1,100}")?m:"PRODUCT_DIRECT_DRIVER_STOP_NO_RETRY");System.exit(1);}}
}
