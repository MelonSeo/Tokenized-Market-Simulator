import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ExchangeApplication;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.custody.auth.*;
import com.pricetrack.exchange.custody.runtime.*;
import java.net.*;import java.net.http.*;import java.nio.file.*;import java.time.*;import java.util.*;
import org.springframework.boot.SpringApplication;
import org.web3j.crypto.*;import org.web3j.utils.Numeric;

/** First C Agent STATE probe, before the full frozen coordinator schedule. No provider key or legacy signer. */
public final class PilotV2LinuxSmoke {
 static final ObjectMapper J=new ObjectMapper();
 static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 static void need(boolean b,String code){if(!b)throw new IllegalStateException(code);}
 record Reply(int status,JsonNode body){}
 static Reply post(String path,String jwt,Object body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080"+path)).timeout(Duration.ofSeconds(95)).header("Content-Type","application/json").header("Origin","http://127.0.0.1:8080");
  if(jwt!=null)b.header("Authorization","Bearer "+jwt);
  var r=HTTP.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(J.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
  need(r.body().length<=2_000_000,"LINUX_SMOKE_BODY_LIMIT");return new Reply(r.statusCode(),J.readTree(r.body()));
 }
 public static void main(String[] args)throws Exception{
  need(Arrays.equals(args,new String[]{"/run/pilot/smoke.properties"})&&System.getProperty("os.name").equals("Linux"),"LINUX_SMOKE_SCOPE");
  long first=System.nanoTime();
  try(var rpc=new CCustodyRpcClient(URI.create("http://anvil:8545"))){need(rpc.request("eth_chainId",List.of(),3000).asText().equals("0x7a69"),"LINUX_SMOKE_CHAIN_ID");}
  Files.writeString(Path.of("/run/pilot/first-rpc.json"),J.writeValueAsString(Map.of("method","eth_chainId","success",true,"elapsedMs",(System.nanoTime()-first)/1_000_000,"rpc","anvil:8545","hostPortPublished",false)),StandardOpenOption.CREATE_NEW);
  var properties=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){properties.load(in);}
  need(properties.getProperty("app.ai.api-key").isEmpty()&&properties.getProperty("spring.datasource.url").startsWith("jdbc:postgresql://pg:5432/exchange_ai_pilot_smoke_"),"LINUX_SMOKE_CONFIG");
  try(var ctx=new SpringApplication(ExchangeApplication.class).run("--spring.config.location=classpath:/application.yml,file:"+args[0],"--spring.config.import=")){
   var reads=ctx.getBean(CCustodyRpcReader.class);reads.config();ctx.getBean(CWalletLinkStore.class).installSchema();
   String login="linux_smoke_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
   ctx.getBean(AuthService.class).signup(new AuthController.SignupRequest(login,password,"smoke"));
   var auth=post("/api/auth/login",null,Map.of("loginId",login,"password",password));need(auth.status==200,"LINUX_SMOKE_LOGIN");String jwt=auth.body.path("accessToken").asText();
   var meta=J.readTree(Path.of("/run/pilot/fixture/test-chain.json").toFile());var keys=J.readTree(Path.of("/run/pilot/keys/wallet-keys.private.json").toFile());
   String wallet=meta.path("userA").asText();var challenge=post("/api/c-futures/wallet/challenges",jwt,Map.of("wallet",wallet));need(challenge.status==200,"LINUX_SMOKE_CHALLENGE");
   var key=Credentials.create(keys.path("userA").asText());need(key.getAddress().equalsIgnoreCase(wallet),"LINUX_SMOKE_EOA");
   var sig=Sign.signPrefixedMessage(challenge.body.path("message").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8),key.getEcKeyPair());
   var proof=Map.of("challengeId",challenge.body.path("id").asText(),"signature",Numeric.toHexString(sig.getR())+Numeric.toHexString(sig.getS()).substring(2)+Numeric.toHexString(sig.getV()).substring(2));
   need(post("/api/c-futures/wallet/link",jwt,proof).status==200,"LINUX_SMOKE_LINK");need(post("/api/c-futures/wallet/link",jwt,proof).status==409,"LINUX_SMOKE_REPLAY");
   long start=System.nanoTime();var answer=post("/api/ai/agent/answers",jwt,Map.of("question","C Vault 잔고 조회"));
   Files.writeString(Path.of("/run/pilot/c-agent-smoke-attempt.json"),J.writeValueAsString(Map.of("http",answer.status,"error",answer.body.path("error").asText(),"modelCalls",answer.body.at("/metrics/modelCalls").asInt(),"toolCalls",answer.body.at("/metrics/toolCalls").asInt(),"elapsedMs",(System.nanoTime()-start)/1_000_000)),StandardOpenOption.CREATE_NEW);
   need(answer.status==200&&answer.body.path("error").isNull()&&answer.body.at("/metrics/modelCalls").asInt()==0&&answer.body.path("toolEvidence").size()==1&&answer.body.at("/toolEvidence/0/tool").asText().equals("getCAccount")&&answer.body.at("/toolEvidence/0/status").asText().equals("SUCCESS"),"LINUX_SMOKE_C_AGENT");
   Files.writeString(Path.of("/run/pilot/c-agent-smoke.json"),J.writeValueAsString(Map.of("http",answer.status,"modelCalls",0,"tool","getCAccount","toolStatus","SUCCESS","elapsedMs",(System.nanoTime()-start)/1_000_000,"scope","STATE_CONNECTIVITY_NOT_PCa_MIXED_OR_MODEL_QUALITY")),StandardOpenOption.CREATE_NEW);
  }
 }
}
