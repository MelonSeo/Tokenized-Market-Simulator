import java.nio.file.*;
import java.util.*;
import java.net.http.HttpClient;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;

/** Test EOA proof/read only. Same real JWT/PG/Anvil; no model or signing key in App. */
public final class ProductAiCReadDriver {
    static final ObjectMapper J=new ObjectMapper();
    static void check(boolean b,String code){if(!b)throw new IllegalStateException(code);}
    static void bootstrap(Path f,Path config)throws Exception{
        ProductReadDriver.bootstrap(f,config);var p=new Properties();Path file=config.resolve("application.properties");try(var in=Files.newInputStream(file)){p.load(in);}
        for(String k:List.of("app.ai.enabled","app.ai.agent.enabled","app.ai.tools.enabled"))p.setProperty(k,"true");
        p.setProperty("app.ai.api-key","");p.setProperty("app.ai.jdbc-url",p.getProperty("spring.datasource.url"));p.setProperty("app.ai.db-user",p.getProperty("spring.datasource.username"));p.setProperty("app.ai.db-password",p.getProperty("spring.datasource.password"));
        try(var out=Files.newOutputStream(file)){p.store(out,"isolated C AI reads, no provider key or financial API");}
    }
    static void verify(Path f,Path evidence)throws Exception{
        ProductReadDriver.verify(f,evidence);var meta=J.readTree(f.resolve("test-chain.json").toFile());var c=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        for(int i=0;i<2;i++){
            String label=i==0?"A":"B";var login=ProductReadDriver.http(c,"POST","/api/auth/login",null,Map.of("loginId","read-user-"+label.toLowerCase(),"password",FullAppReadDriver.secret("FULL_READ_TEST_PASSWORD")));
            check(login.status()==200,"AI_C_LOGIN");String token=login.body().path("accessToken").asText();
            var account=ProductReadDriver.http(c,"GET","/api/c-futures/account",token,null);
            var ai=ProductReadDriver.http(c,"POST","/api/ai/tools/getCAccount",token,Map.of("arguments",Map.of()));check(ai.status()==200,"AI_C_ACCOUNT");
            var data=ai.body().path("data");for(String k:List.of("walletBalance","free","locked"))check(new BigDecimal(data.path(k).asText()).movePointRight(18).toBigIntegerExact().toString().equals(account.body().path("value").path(k).asText()),"AI_C_AMOUNTS");
            check(data.path("observation").path("hash").asText().equals(account.body().path("block").path("hash").asText()),"AI_C_FIXED_BLOCK");
            var own=ProductReadDriver.http(c,"POST","/api/ai/agent/answers",token,Map.of("question","C 온체인 포지션 상태 조회","target",Map.of("cPositionId",meta.path("position"+label).asText())));
            check(own.status()==200&&own.body().path("status").asText().equals("ANSWERED")&&own.body().path("metrics").path("modelCalls").asInt()==0,"AI_C_REAL_AGENT_READ");
            check(own.body().path("answer").asText().contains("전체 감사 결과는 아닙니다")&&own.body().path("knowledgeSources").isEmpty(),"AI_C_SCOPE");
            var other=ProductReadDriver.http(c,"POST","/api/ai/tools/getCPosition",token,Map.of("arguments",Map.of("positionId",meta.path(i==0?"positionB":"positionA").asText())));check(other.status()==404,"AI_C_FOREIGN");
        }
        var cfg=J.readTree(evidence.resolve("browser.private.json").toFile());((com.fasterxml.jackson.databind.node.ObjectNode)cfg).put("aiReadMode",true);J.writeValue(evidence.resolve("browser.private.json").toFile(),cfg);
        var report=J.readTree(evidence.resolve("api-result.json").toFile());((com.fasterxml.jackson.databind.node.ObjectNode)report).put("cAiOwnAccountFixedBlock",true).put("cAiForeign404",true).put("cAiAgentModelCalls",0).put("cAiPolicySupport",false);J.writeValue(evidence.resolve("api-result.json").toFile(),report);
    }
    public static void main(String[] args){try{if(args[0].equals("bootstrap"))bootstrap(Path.of("/fixture"),Path.of("/gate-config"));else if(args[0].equals("verify"))verify(Path.of("/fixture"),Path.of("/evidence"));else throw new IllegalArgumentException();System.out.println("AI_C_READ_DRIVER_PASS");}catch(Exception e){System.err.println("AI_C_READ_DRIVER_FAILED_"+e.getClass().getSimpleName());System.exit(1);}}
}
