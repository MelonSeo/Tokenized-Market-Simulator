import java.math.BigInteger;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import org.web3j.crypto.*;
import org.springframework.boot.SpringApplication;
import com.pricetrack.exchange.ExchangeApplication;
import com.pricetrack.exchange.custody.runtime.CCustodyIdentity;
import com.pricetrack.exchange.custody.direct.CDirectService;
import static com.pricetrack.exchange.custody.abi.CAbiModels.*;

/** Local manual acceptance only. External owner is an address; never a user signing key. */
public final class ProductManualActor {
 static void need(boolean ok,String code){if(!ok)throw new IllegalArgumentException("MANUAL_"+code);}
 static String owner(String s){need(s!=null&&s.matches("0x[0-9a-fA-F]{40}")&&!s.equalsIgnoreCase("0x"+"0".repeat(40)),"OWNER_ADDRESS_ONLY");return s.toLowerCase(Locale.ROOT);}
 static void seed(String external)throws Exception{
  external=owner(external);CAnvilContractGate.verifyTransportPolicy();
  var g=new CAnvilContractGate(Path.of("/artifacts"),Path.of("/private-evidence"));g.verifySourceMetadata();
  g.symbol=Hash.sha3String("mSEC");g.policy=Hash.sha3String("TMS_C_FIFO_CLAIMS_MMR500_GRACE60_V1");
  g.genesis=g.rpc("eth_getBlockByNumber","0x0",false).path("hash").asText();
  CAnvilContractGate.eq(CAnvilContractGate.number(g.rpc("eth_chainId")),CAnvilContractGate.CHAIN);
  need(g.rpc("eth_getCode",external,"latest").asText().equals("0x"),"EXTERNAL_EOA");
  need(CAnvilContractGate.number(g.rpc("eth_getTransactionCount",external,"latest")).equals(BigInteger.ZERO),"FRESH_OWNER_NONCE");
  for(String role:List.of("treasury","admin","relayer","oracle","reference"))g.keys.put(role,Keys.createEcKeyPair(new SecureRandom()));
  need(g.keys.values().stream().map(CAnvilContractGate::address).distinct().count()==5&&!g.keys.values().stream().map(CAnvilContractGate::address).toList().contains(external),"KEY_SEPARATION");
  for(var key:g.keys.values())g.rpc("anvil_setBalance",CAnvilContractGate.address(key),CAnvilContractGate.hex(CAnvilContractGate.units(100)));
  g.rpc("anvil_setBalance",external,CAnvilContractGate.hex(CAnvilContractGate.units(100)));
  Files.writeString(Path.of("/oracle-config/oracle-key.private"),String.format("%064x",g.keys.get("oracle").getPrivateKey()),StandardOpenOption.CREATE_NEW);
  g.token=g.deploy("CMockCollateral",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.addr("treasury"))),g.keys.get("treasury"));
  g.controller=g.deploy("CCollateralizedFuturesController",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.token),CAnvilContractGate.addressWord(g.addr("relayer")),CAnvilContractGate.addressWord(g.addr("oracle")),CAnvilContractGate.addressWord(g.addr("admin")),g.symbol,g.policy),g.keys.get("treasury"));
  g.rules=g.callAddress(g.controller,"rules()");g.vault=g.callAddress(g.controller,"vault()");
  var meta=CAnvilContractGate.JSON.createObjectNode().put("scope","NEW_MANUAL_EXTERNAL_OWNER_NOT_ACCEPTED").put("genesisHash",g.genesis).put("chainId","31337").put("token",g.token).put("controller",g.controller).put("rules",g.rules).put("vault",g.vault).put("userA",external).put("userB",g.addr("reference"));
  var hashes=meta.putObject("codeHashes");
  for(var e:Map.of("token","CMockCollateral","controller","CCollateralizedFuturesController","rules","FuturesEngine","vault","CMarginVault").entrySet()){
   g.verifyRuntime(e.getValue(),meta.path(e.getKey()).asText());hashes.put(e.getKey(),g.read(g.output.resolve("code-"+e.getValue()+".json")).path("runtimeCodeHash").asText());
  }
  CAnvilContractGate.eq(g.callAddress(g.rules,"executor()"),g.controller);CAnvilContractGate.eq(g.callAddress(g.vault,"asset()"),g.token);
  g.cash("treasury",VaultAction.FUND_HOUSE,CAnvilContractGate.units(100000));
  for(String address:List.of(external,g.addr("reference"))){g.transfer("treasury",address,CAnvilContractGate.units(1000000));CAnvilContractGate.eq(g.free(address),BigInteger.ZERO);CAnvilContractGate.eq(g.callNum(g.vault,"lockedBalance(address)",CAnvilContractGate.addressWord(address)),BigInteger.ZERO);}
  need(CAnvilContractGate.number(g.rpc("eth_getTransactionCount",external,"latest")).equals(BigInteger.ZERO),"NO_OWNER_TRANSACTION");
  Files.writeString(Path.of("/fixture/test-chain.json"),meta.toString(),StandardOpenOption.CREATE_NEW);
 }
 static void bootstrap()throws Exception{
  // Same schema/grants/JWT/direct API setup as reviewed CI; no wallet actor is called.
  ProductDirectDriver.bootstrap();
  var path=Path.of("/fixture/c-identity.json");var old=ProductReadDriver.J.readValue(path.toFile(),CCustodyIdentity.class);
  var id=new CCustodyIdentity(old.chainId(),old.genesisHash(),old.token(),old.controller(),old.rules(),old.vault(),old.relayer(),old.oracle(),old.pauseAdmin(),old.symbolHash(),old.policyHash(),"http://127.0.0.1:32175");
  need(id.deploymentId().equals(old.deploymentId()),"ORIGIN_ONLY");ProductReadDriver.J.writeValue(path.toFile(),id);
  ProductReadDriver.J.writeValue(Path.of("/oracle-config/identity.json").toFile(),id);
  var p=new Properties();var config=Path.of("/gate-config/application.properties");try(var in=Files.newInputStream(config)){p.load(in);}
  p.setProperty("app.c-futures.origin",id.origin());p.setProperty("app.websocket.allowed-origin-patterns",id.origin());
  try(var out=Files.newOutputStream(config)){p.store(out,"new local manual acceptance only; unchanged JWT lifetime");}
 }
 static void app()throws Exception{
  var p=new Properties();try(var in=Files.newInputStream(Path.of("/gate-config/application.properties"))){p.load(in);}
  for(String flag:FullAppReadGate.OFF)need("false".equals(p.getProperty(flag)),"OFF_FLAGS");
  need("true".equals(p.getProperty("app.c-futures.enabled"))&&"true".equals(p.getProperty("app.c-futures.direct.enabled")),"DIRECT_FLAGS");
  for(String k:p.stringPropertyNames())need(!k.contains("private-key")&&!k.contains("mnemonic"),"KEYLESS_CONFIG");
  var spring=new SpringApplication(ExchangeApplication.class,ProductDirectGate.Boundary.class);spring.setLogStartupInfo(false);
  try(var ctx=spring.run("--spring.config.location=classpath:/application.yml,file:/gate-config/application.properties")){
   for(String k:List.of("app.blockchain.operator-private-key","app.blockchain.price-report.signer-private-key","app.futures.executor-private-key","app.futures.signer-private-key","READ_TEST_KEY_A","READ_TEST_KEY_B"))need(ctx.getEnvironment().getProperty(k,"").isBlank(),"KEYLESS_ENV");
   need(ctx.getBeansOfType(CDirectService.class).size()==1,"DIRECT_BEAN");
   Files.writeString(Path.of("/gate-evidence/ready.json"),"{\"manual\":true,\"userTransactionsAutomated\":false}",StandardOpenOption.CREATE_NEW);
   while(!Files.exists(Path.of("/gate-control/STOP")))Thread.sleep(200);
  }Files.writeString(Path.of("/gate-evidence/normal-stop.json"),"{\"normalStop\":true}",StandardOpenOption.CREATE_NEW);
 }
 static void selfTest()throws Exception{
  CAnvilContractGate.verifyTransportPolicy();
  need(owner("0x"+"A".repeat(40)).equals("0x"+"a".repeat(40)),"OWNER_NORMALIZATION");
  for(String bad:List.of("0x"+"0".repeat(40),"1".repeat(64),"seed words", "0x"+"1".repeat(64)))try{owner(bad);throw new AssertionError();}catch(IllegalArgumentException expected){}
  for(var method:ProductManualActor.class.getDeclaredMethods())if(!method.isSynthetic())for(var param:method.getParameters())need(param.isNamePresent(),"PARAMETER_METADATA");
 }
 public static void main(String[] args){try{
  if(Arrays.equals(args,new String[]{"--self-test"})){selfTest();System.out.println("MANUAL_COMPILED_OFFLINE_PASS");return;}
  if(args.length==2&&args[0].equals("seed"))seed(args[1]);
  else if(Arrays.equals(args,new String[]{"bootstrap"}))bootstrap();
  else if(Arrays.equals(args,new String[]{"app"}))app();else throw new IllegalArgumentException();
 }catch(Exception e){System.err.println("MANUAL_ACTOR_STOP_NO_RETRY_"+e.getClass().getSimpleName());System.exit(1);}}
}
