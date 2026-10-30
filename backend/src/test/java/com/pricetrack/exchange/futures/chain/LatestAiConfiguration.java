package com.pricetrack.exchange.futures.chain;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.evaluation.GuardedAiEvaluationRunner;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;

/** Test classpath only. Product provider remains OFF; only the budgeted stdio transport exists. */
@TestConfiguration
public class LatestAiConfiguration {
    /** The opt-in cold-copy AI experiment suppresses execution, not actual chain/DB reads. */
    @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor latestReadOnlyWorkers(){
        return factory->{if("true".equals(System.getenv("AI_LATEST_REUSE_FIXTURE"))){
            var definition=(org.springframework.beans.factory.support.AbstractBeanDefinition)factory.getBeanDefinition("futuresWorker");
            definition.setInitMethodName(null);definition.setDestroyMethodName(null);
            definition.setInstanceSupplier(()->org.mockito.Mockito.mock(com.pricetrack.exchange.futures.runtime.FuturesWorker.class));
        }};
    }
    @Bean(destroyMethod="close") GuardedAiEvaluationRunner latestRunner(ObjectMapper json)throws Exception {
        Path run=Path.of(System.getenv("AI_LATEST_STUDY_RUN")).toRealPath();
        return new GuardedAiEvaluationRunner(Path.of("..").toAbsolutePath().normalize(),run,
            Path.of(System.getenv().getOrDefault("AI_EVAL_TEST_PYTHON","python")),
            GuardedAiEvaluationRunner.Mode.LIVE,run.resolve("approval.json"),json);
    }
    @Bean Capture latestCapture(GuardedAiEvaluationRunner runner,ObjectMapper json){return new Capture(runner,json);}
    @Bean AgentModelProvider latestModel(Capture capture){return capture.model;}
    @Bean @Primary com.pricetrack.exchange.ai.observability.AiObservability latestTiming(Capture capture,GuardedAiEvaluationRunner runner){
        var observer=new com.pricetrack.exchange.ai.observability.AiObservability(){
            @Override public void record(String stage,String name,String id,long start,boolean success,String code,long docs,Long in,Long out){
                super.record(stage,name,id,start,success,code,docs,in,out);
                capture.stages.add(Map.of("stage",stage,"name",name==null?"UNKNOWN":name,"elapsedMs",Math.max(0,(System.nanoTime()-start)/1_000_000),"success",success));
            }
        };runner.provider().observe(observer);return observer;
    }
    @Bean @Lazy AuthorizedKnowledgeRetrieval latestRetrieval(Capture capture,GuardedAiEvaluationRunner runner,com.pricetrack.exchange.ai.observability.AiObservability observer){
        var delegate=runner.retrieval().observe(observer);
        return new AuthorizedKnowledgeRetrieval(null,null,null,null){
            @Override public Evidence search(AuthenticatedUser p,String q,Duration d){
                capture.query=q;capture.domains=Set.of();
                return capture.hits=delegate.search(p,q,d);
            }
            @Override public Evidence searchScoped(AuthenticatedUser p,String q,Duration d,Set<String> domains){
                capture.query=q;capture.domains=Set.copyOf(domains);
                return capture.hits=delegate.searchScoped(p,q,d,domains);
            }
            @Override public void verify(String version){delegate.verify(version);}
        };
    }
    static class Capture {
        volatile AuthorizedKnowledgeRetrieval.Evidence hits;
        volatile AgentModelProvider.Generated generated;
        volatile JsonNode input;
        volatile String query;volatile Set<String> domains=Set.of();
        final List<Map<String,Object>> stages=Collections.synchronizedList(new ArrayList<>());
        final OpenAiAgentProvider model;
        Capture(GuardedAiEvaluationRunner runner,ObjectMapper json){
            model=new OpenAiAgentProvider(runner.provider(),json){
                @Override public Plan planWithSkills(String q,Subject target,List<String> eligible,Duration timeout){
                    long start=System.nanoTime();try{return super.planWithSkills(q,target,eligible,timeout);}
                    finally{stages.add(Map.of("stage","PLANNING","elapsedMs",(System.nanoTime()-start)/1_000_000));}
                }
                @Override public Generated synthesize(String q,Route route,JsonNode evidence,Duration timeout){
                    long start=System.nanoTime();try{input=evidence.deepCopy();return generated=super.synthesize(q,route,evidence,timeout);}
                    finally{stages.add(Map.of("stage","SYNTHESIS","elapsedMs",(System.nanoTime()-start)/1_000_000));}
                }
            };
        }
        void clear(){hits=null;generated=null;input=null;query=null;domains=Set.of();stages.clear();}
    }
    @Bean Allowance latestAllowance(){return new Allowance();}
    @Bean @Primary AdverseReceipt latestAdverseReceipt(com.pricetrack.exchange.ai.tool.receipt.ReadOnlyReceiptClient actual,ObjectMapper json){return new AdverseReceipt(actual,json);}
    /** Only H06 uses this test-only controlled failure. All positive and H05 reads use actual RPC. */
    static final class AdverseReceipt implements com.pricetrack.exchange.ai.tool.receipt.ReceiptReader {
        final com.pricetrack.exchange.ai.tool.receipt.ReceiptReader actual;final ObjectMapper json;
        volatile long failedOrder=-1;volatile String failedHash;
        volatile long mismatchOrder=-1;
        AdverseReceipt(com.pricetrack.exchange.ai.tool.receipt.ReceiptReader actual,ObjectMapper json){this.actual=actual;this.json=json;}
        public com.fasterxml.jackson.databind.node.ObjectNode read(com.pricetrack.exchange.ai.tool.read.ToolReadFacade.ReceiptInput input){
            if(input.orderId()==mismatchOrder)return actual.read(input).deepCopy().put("eventValidation","MISMATCH")
                .put("captureScope","CONTROLLED_EVENT_MISMATCH_ADAPTER_NOT_ACTUAL_CHAIN_MISMATCH");
            if(input.orderId()!=failedOrder||!Objects.equals(input.txHash(),failedHash))return actual.read(input);
            return json.createObjectNode().put("orderId",input.orderId()).put("databaseStatus",input.databaseStatus())
                .put("linkStatus","LINKED").put("receiptStatus","FOUND").put("executionStatus","FAILED")
                .put("eventValidation","UNKNOWN").put("captureScope","CONTROLLED_HTTP_TEST_ADAPTER_NOT_CANONICAL_CHAIN_FAILURE");
        }
    }
    /** Test-only DTO fault, never a DB relationship mutation. Product ownership read executes first. */
    @Bean @Primary @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.ai.novel-test",havingValue="true")
    NovelReads novelReads(jakarta.persistence.EntityManager em,com.pricetrack.exchange.market.MarketPriceService market,
            ObjectMapper json,com.pricetrack.exchange.ai.tool.ToolProperties p){return new NovelReads(em,market,json,p);}
    static class NovelReads extends com.pricetrack.exchange.ai.tool.read.ToolReadFacade {
        volatile boolean badQuoteLink;
        public void setBadQuoteLink(boolean enabled){this.badQuoteLink=enabled;}
        public boolean badQuoteLinkActive(){return badQuoteLink;}
        NovelReads(jakarta.persistence.EntityManager em,com.pricetrack.exchange.market.MarketPriceService market,ObjectMapper json,
                com.pricetrack.exchange.ai.tool.ToolProperties p){super(em,market,json,p,java.time.Clock.systemUTC());}
        @Override public com.fasterxml.jackson.databind.node.ObjectNode quote(com.pricetrack.exchange.ai.tool.ToolContext ctx,String id){
            var result=super.quote(ctx,id);
            return badQuoteLink?result.deepCopy().put("orderLink","INCONSISTENT_LINK").putNull("orderId")
                .put("captureScope","CONTROLLED_LINK_DTO_NOT_DATABASE_CORRUPTION"):result;
        }
    }
    @Bean FilterRegistrationBean<Filter> latestScope(Allowance allowance){
        Filter filter=(request,response,chain)->{
            var req=(HttpServletRequest)request;
            if(!req.getRequestURI().equals("/api/ai/agent/answers")){chain.doFilter(request,response);return;}
            byte[] bytes=req.getInputStream().readNBytes(4097);
            var auth=SecurityContextHolder.getContext().getAuthentication();
            Object principal=auth==null?null:auth.getPrincipal();
            if(!(principal instanceof AuthenticatedUser user)||!allowance.take(user.userId(),bytes)){
                ((HttpServletResponse)response).sendError(403);return;
            }
            chain.doFilter(new HttpServletRequestWrapper(req){
                @Override public ServletInputStream getInputStream(){
                    var stream=new ByteArrayInputStream(bytes);
                    return new ServletInputStream(){
                        public int read(){return stream.read();}
                        public boolean isFinished(){return stream.available()==0;}
                        public boolean isReady(){return true;}
                        public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
                    };
                }
            },response);
        };
        var bean=new FilterRegistrationBean<>(filter);bean.setOrder(-99);return bean;
    }
    static final class Allowance {
        private final Set<String> allowed=ConcurrentHashMap.newKeySet();
        private String key(long uid,byte[] body){return uid+":"+Base64.getEncoder().encodeToString(body);}
        void add(long uid,byte[] body){allowed.add(key(uid,body));}
        boolean take(long uid,byte[] body){return allowed.remove(key(uid,body));}
    }
}
