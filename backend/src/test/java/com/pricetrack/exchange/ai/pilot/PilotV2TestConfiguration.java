package com.pricetrack.exchange.ai.pilot;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.observability.AiObservability;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

/** Explicit @Import on an owned fixture only. Never discovered in production/main classpath. */
@TestConfiguration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.ai.pilot-v2.unpaid-tests",havingValue="true")
public class PilotV2TestConfiguration {
    @Bean JsonNode pilotV2Settings(ObjectMapper json)throws Exception{
        String path=System.getenv("AI_PILOT_V2_CONFIG");
        if(path==null)throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        var file=Path.of(path);if(Files.isSymbolicLink(file)||Files.size(file)>32768)throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        var s=json.readTree(file.toFile());
        if(!s.isObject()||!s.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(Set.of("mode","repo","run","python","knowledgeRoot","legacyManifest","pilotManifest","budget","legacy9","pilot10"))
            ||!Set.of("UNPAID_CONTROLLED_ONLY","PAID_EXPLICIT_V2").contains(s.path("mode").asText()))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");return s;
    }
    static String jdbcHost(String mode,String optIn,String platform){
        String host="127\\.0\\.0\\.1:[1-9][0-9]{3,4}";
        return "true".equals(optIn)&&"Linux".equals(platform)&&"UNPAID_CONTROLLED_ONLY".equals(mode)?"(?:"+host+"|pg:5432)":host;
    }
    static AiProperties properties(JsonNode s,String key){
        var d=s.path(key);
        if(!d.isObject()||!d.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(Set.of("jdbcUrl","dbUser","dbPassword")))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        String host=jdbcHost(s.path("mode").asText(),System.getenv("AI_PILOT_V2_LINUX_INTERNAL"),System.getProperty("os.name"));
        if(!d.path("jdbcUrl").asText().matches("jdbc:postgresql://"+host+"/exchange_ai_pilot_[a-z0-9_]+(?:\\?.*)?"))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        if(key.equals("budget")&&!d.path("jdbcUrl").asText().matches("jdbc:postgresql://"+host+"/exchange_ai_pilot_budget_[a-z0-9_]+(?:\\?.*)?"))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        return new AiProperties(true,d.path("jdbcUrl").asText(),d.path("dbUser").asText(),d.path("dbPassword").asText(),
            s.path("knowledgeRoot").asText(),s.path(key.equals("pilot10")?"pilotManifest":"legacyManifest").asText(),"","guarded:pilot-v2/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
    }
    @Bean(destroyMethod="close") @Primary
    public PgProviderBudget pilotV2SharedBudget(JsonNode pilotV2Settings,ObjectMapper json)throws Exception{
        // An UNPAID counter, explicitly initialized through the existing ADMIN API, never a paid permit.
        var p=properties(pilotV2Settings,"budget");
        if(p.jdbcUrl().equals(properties(pilotV2Settings,"legacy9").jdbcUrl())||p.jdbcUrl().equals(properties(pilotV2Settings,"pilot10").jdbcUrl()))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        String approval="UNPAID_PILOT_V2_NOT_APPROVAL";
        if(pilotV2Settings.path("mode").asText().equals("PAID_EXPLICIT_V2")){
            var meta=json.readTree(Path.of(pilotV2Settings.path("run").asText(),"session.json").toFile());
            String authority=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((p.jdbcUrl()+"\n"+p.dbUser()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            approval=meta.path("approvalId").asText();
            if(!meta.path("mode").asText().equals("PAID_EXPLICIT_V2")||!authority.equals(meta.path("budgetAuthority").asText())||!approval.matches("tms_pilot_v2_[0-9a-f]{32}"))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        }
        return new PgProviderBudget(p,new PgProviderBudget.Limits(approval,18_000_000_000L,18_000_000_000L,92,70,100,5),Clock.systemUTC());
    }
    @Bean(destroyMethod="close") @Lazy
    public PilotV2Channel pilotV2Channel(JsonNode pilotV2Settings,ObjectMapper json)throws Exception{
        return new PilotV2Channel(Path.of(pilotV2Settings.path("repo").asText()),Path.of(pilotV2Settings.path("run").asText()),Path.of(pilotV2Settings.path("python").asText()),json,pilotV2Settings.path("mode").asText());
    }
    @Bean(destroyMethod="close") @Lazy
    public PilotV2Runtime pilotV2Runtime(JsonNode pilotV2Settings,PgProviderBudget sharedBudget,PilotV2Channel channel,ObjectMapper json,AiObservability observation){
        return new PilotV2Runtime(Map.of("legacy9",properties(pilotV2Settings,"legacy9"),"pilot10",properties(pilotV2Settings,"pilot10")),sharedBudget,channel,json).observe(observation);
    }
    @Bean @Primary
    public AgentModelProvider pilotV2Model(ObjectProvider<PilotV2Runtime> runtime,ObjectMapper json){
        return new AgentModelProvider(){
            public Plan plan(String q,Subject subject,Duration timeout){return runtime.getObject().model(json).plan(q,subject,timeout);}
            public Plan planWithSkills(String q,Subject subject,List<String> eligible,Duration timeout){return runtime.getObject().model(json).planWithSkills(q,subject,eligible,timeout);}
            public Generated synthesize(String q,Route route,JsonNode evidence,Duration timeout){
                var result=runtime.getObject().model(json).synthesize(q,route,evidence,timeout);
                PilotV2ModelAudit.record(json,result,route,evidence);return result;
            }
        };
    }
    @Bean @Primary
    public AuthorizedKnowledgeRetrieval pilotV2Retrieval(ObjectProvider<PilotV2Runtime> runtime){
        return new AuthorizedKnowledgeRetrieval(null,null,null,null){
            public Evidence search(AuthenticatedUser u,String q,Duration d){return runtime.getObject().retrieval().search(u,q,d);}
            public Evidence searchScoped(AuthenticatedUser u,String q,Duration d,Set<String> domains){return runtime.getObject().retrieval().searchScoped(u,q,d,domains);}
            public void verify(String v){runtime.getObject().retrieval().verify(v);}
        };
    }
    @Bean @Primary
    public RagService pilotV2Rag(ObjectProvider<PilotV2Runtime> runtime){
        return new RagService(null,null,null,null,null){
            public IndexResult ingest(AuthenticatedUser user){return runtime.getObject().rag().ingest(user);}
            public List<com.pricetrack.exchange.ai.store.KnowledgeHit> search(AuthenticatedUser u,String q){throw new AiFailure("AI_EVALUATION_ENDPOINT_NOT_ALLOWED");}
            public Answer answer(AuthenticatedUser u,String q){throw new AiFailure("AI_EVALUATION_ENDPOINT_NOT_ALLOWED");}
        };
    }
    @Bean @Primary
    public AiObservability pilotV2Observation(ObjectProvider<PilotV2Channel> channel){
        return new AiObservability(){public <T>T correlated(String id,Supplier<T> work){
            return channel.getObject().workerObservation("ACTIVE").correlated(id,()->super.correlated(id,work));
        }};
    }
    @Bean
    public FilterRegistrationBean<Filter> pilotV2Admission(ObjectProvider<PilotV2Channel> channel){
        var filter=new FilterRegistrationBean<Filter>();filter.setOrder(-99);
        filter.setFilter((r,s,c)->{if(((HttpServletRequest)r).getRequestURI().equals("/api/ai/agent/answers"))channel.getObject().admission("ACTIVE").getFilter().doFilter(r,s,c);else c.doFilter(r,s);});return filter;
    }
    static boolean allowed(String method,String path){
        if(method.equals("GET"))return true; // existing product JWT/role rules still execute
        if(!method.equals("POST"))return false;
        return Set.of("/api/auth/login","/api/c-futures/wallet/challenges","/api/c-futures/wallet/link","/api/ai/index","/api/ai/agent/answers","/api/ai/provider-budget/initialize").contains(path)
            ||path.startsWith("/api/ai/tools/")&&com.pricetrack.exchange.ai.tool.ToolRegistry.NAMES.contains(path.substring("/api/ai/tools/".length()));
    }
    @Bean
    public FilterRegistrationBean<Filter> pilotV2ReadBoundary(){
        var f=new FilterRegistrationBean<Filter>();f.setOrder(Integer.MIN_VALUE);
        f.setFilter((r,s,c)->{var req=(HttpServletRequest)r;if(!allowed(req.getMethod(),req.getRequestURI())){((HttpServletResponse)s).sendError(403);return;}c.doFilter(r,s);});return f;
    }
    @Bean @Primary
    public PilotV2DtoReads pilotV2Reads(jakarta.persistence.EntityManager em,com.pricetrack.exchange.market.MarketPriceService market,ObjectMapper json,
            com.pricetrack.exchange.ai.tool.ToolProperties properties,ObjectProvider<PilotV2Channel> channel){
        return new PilotV2DtoReads(ReceiptProbeTrace.wrap(em),market,json,properties,channel::getObject);
    }
    @Bean @Primary
    public com.pricetrack.exchange.ai.tool.receipt.ReceiptReader pilotV2Receipt(PilotV2DtoReads reads,com.pricetrack.exchange.ai.tool.receipt.ReadOnlyReceiptClient actual){if(ReceiptProbeTrace.ENABLED)actual.observeTiming(ReceiptProbeTrace::event);return reads.receiptReader(actual);}
}
