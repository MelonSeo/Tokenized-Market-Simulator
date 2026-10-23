package com.pricetrack.exchange.futures.chain;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test-classpath only. No API key, HTTP client, approval reader or live-mode switch exists here. */
@TestConfiguration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.ai.web-stage1",havingValue="true")
public class FuturesWebStage1Configuration {
    public static final class NoPaidTransport implements OpenAiProvider.EvaluationTransport {
        private final ObjectMapper json;private final AtomicInteger rejected=new AtomicInteger();
        public NoPaidTransport(ObjectMapper json){this.json=json;}
        public JsonNode post(String path,byte[] body,Duration timeout){
            rejected.incrementAndGet();
            // Fail closed BEFORE transport. Never record a body, question, ID, credential or fictitious paid reservation.
            throw new AiFailure("AI_WEB_PAID_EXECUTION_NOT_APPROVED");
        }
        public int rejected(){return rejected.get();}
    }
    @Bean NoPaidTransport stage1NoPaidTransport(ObjectMapper json){return new NoPaidTransport(json);}
    @Bean(destroyMethod="close") FuturesWebScopedTransport webScope(ObjectMapper json,org.springframework.core.env.Environment env){
        return new FuturesWebScopedTransport(json,Path.of(System.getenv("FUTURES_BROWSER_E2E_RUN")));
    }
    @Bean OpenAiProvider stage1Provider(AiProperties properties,ObjectMapper json,NoPaidTransport transport,FuturesWebScopedTransport scoped,org.springframework.core.env.Environment env){
        if(properties.enabled()||!properties.apiKey().isEmpty()||!properties.apiBase().equals("guarded:stdio/"))throw new IllegalStateException("STAGE1_NO_PAID_REQUIRED");
        return OpenAiProvider.forGuardedEvaluation(properties,json,"true".equals(env.getProperty("app.ai.web-stage2"))?scoped:transport);
    }
    @Bean AgentModelProvider stage1AgentModel(OpenAiProvider provider,ObjectMapper json){return new OpenAiAgentProvider(provider,json);}
    @Bean(destroyMethod="close") PgKnowledgeStore stage1AiStore(AiProperties p){return new PgKnowledgeStore(p);}
    @Bean AuthorizedKnowledgeRetrieval stage1Retrieval(AiProperties p,PgKnowledgeStore store,OpenAiProvider provider,ObjectMapper json){
        return new AuthorizedKnowledgeRetrieval(p,new KnowledgeLoader(p,json),store,provider);
    }
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> webScopeFilter(FuturesWebScopedTransport scoped,ObjectMapper json,org.springframework.core.env.Environment env){
        var bean=new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();
        bean.setOrder(org.springframework.core.Ordered.LOWEST_PRECEDENCE);bean.addUrlPatterns("/api/ai/*");
        bean.setFilter((request,response,chain)->{
            if(!"true".equals(env.getProperty("app.ai.web-stage2"))){chain.doFilter(request,response);return;}
            var req=(jakarta.servlet.http.HttpServletRequest)request;var res=(jakarta.servlet.http.HttpServletResponse)response;
            if(!req.getRequestURI().equals("/api/ai/agent/answers")){chain.doFilter(request,response);return;}
            byte[] raw=req.getInputStream().readNBytes(4097);
            try{
                var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                scoped.authorize(auth!=null&&auth.getPrincipal() instanceof com.pricetrack.exchange.auth.AuthenticatedUser u?u:null,json.readTree(raw));
            }catch(Exception denied){scoped.close();res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"error\":\"AI_WEB_SCOPE_REJECTED\"}");return;}
            chain.doFilter(new jakarta.servlet.http.HttpServletRequestWrapper(req){
                @Override public jakarta.servlet.ServletInputStream getInputStream(){var in=new java.io.ByteArrayInputStream(raw);return new jakarta.servlet.ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(jakarta.servlet.ReadListener l){throw new UnsupportedOperationException();}};}
            },response);
        });return bean;
    }
}
