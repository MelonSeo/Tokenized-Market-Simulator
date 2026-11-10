package com.pricetrack.exchange.ai.pilot;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.observability.AiObservability;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.core.context.SecurityContextHolder;

/** Test-classpath private child connection. No key, paid mode, URL, phase or actor from browser JSON. */
public final class PilotV2Channel implements AutoCloseable {
    private final ObjectMapper json;
    private final Process child;
    private final Path folder;
    private final ReentrantLock wire=new ReentrantLock();
    private final ExecutorService reader=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"v2-private-reader");t.setDaemon(true);return t;});
    private volatile boolean closed;
    public PilotV2Channel(Path repo,Path run,Path python,ObjectMapper json)throws Exception{
        this(repo,run,python,json,"UNPAID_CONTROLLED_ONLY");
    }
    public PilotV2Channel(Path repo,Path run,Path python,ObjectMapper json,String expectedMode)throws Exception{
        this.json=json;this.folder=run.toRealPath();
        var meta=json.readTree(folder.resolve("session.json").toFile());
        if(!Set.of("UNPAID_CONTROLLED_ONLY","PAID_EXPLICIT_V2").contains(expectedMode)||!meta.path("mode").asText().equals(expectedMode))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        var errorLog=Files.createFile(folder.resolve("broker-"+UUID.randomUUID()+".private.log"));
        var b=new ProcessBuilder(python.toString(),"-B","-S",repo.resolve("deployment/ai_pilot_channel.py").toString(),"--run",folder.toString()).directory(repo.toFile()).redirectError(errorLog.toFile());
        var old=new HashMap<>(b.environment());b.environment().clear();
        for(String k:List.of("PATH","Path","SystemRoot","WINDIR","TEMP","TMP"))if(old.containsKey(k))b.environment().put(k,old.get(k));
        child=b.start();
        try{var ready=receive(Duration.ofSeconds(10));if(!ready.path("ready").asBoolean()||!ready.path("mode").asText().equals(expectedMode))throw new IOException();}
        catch(Exception e){close();throw new AiFailure("AI_EVALUATION_BROKER_NOT_READY");}
    }
    private JsonNode receive(Duration timeout)throws Exception{
        var pending=reader.submit(()->{
            var bytes=new ByteArrayOutputStream();int n;
            while((n=child.getInputStream().read())!='\n'){if(n<0||bytes.size()>=2_800_000)throw new IOException();bytes.write(n);}
            return json.readTree(bytes.toByteArray());
        });
        try{return pending.get(timeout.toNanos(),TimeUnit.NANOSECONDS);}
        catch(Exception e){pending.cancel(true);throw e;}
    }
    public JsonNode control(Map<String,Object> frame){return exchange(frame,Duration.ofSeconds(5));}
    public String corpus(){return control(Map.of("op","active")).path("corpus").asText();}
    private JsonNode exchange(Map<String,Object> frame,Duration timeout){
        var deadline=new CallDeadline(timeout);boolean locked=false;
        try{
            // One bounded provider dispatch at a time. Waiting counts against the caller's deadline.
            locked=wire.tryLock(deadline.remaining().toNanos(),TimeUnit.NANOSECONDS);
            if(!locked||closed||Files.exists(folder.resolve("STOP")))throw new IOException();
            var outgoing=new LinkedHashMap<>(frame);
            if("dispatch".equals(frame.get("op"))){
                long remainingMs=Math.max(1,deadline.remaining().toMillis());
                outgoing.put("timeoutMs",remainingMs);outgoing.put("deadlineEpochMs",System.currentTimeMillis()+remainingMs);
            }
            byte[] raw=json.writeValueAsBytes(outgoing);if(raw.length>100000)throw new IOException();
            child.getOutputStream().write(raw);child.getOutputStream().write('\n');child.getOutputStream().flush();
            var r=receive(deadline.remaining());deadline.remaining();
            if(!r.path("ok").asBoolean())throw new AiFailure("AI_EVALUATION_GUARD_REJECTED");
            return r.path("result");
        }catch(AiFailure e){throw e;}
        catch(Exception e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            halt();throw new AiFailure("AI_EVALUATION_TRANSPORT_UNCERTAIN");
        }finally{if(locked)wire.unlock();}
    }
    public OpenAiProvider provider(AiProperties p,PgProviderBudget sharedBudget,String corpus){
        if(!Set.of("legacy9","pilot10","ACTIVE").contains(corpus))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        return OpenAiProvider.forReservedEvaluation(p,json,sharedBudget,(path,body,timeout,reservation,actor)->{
            try{
            var call=new CallDeadline(timeout);
            var current=exchange(Map.of("op","active"),call.remaining());
            String actualCorpus=corpus.equals("ACTIVE")?current.path("corpus").asText():corpus;
            if(current.path("phase").asText().startsWith("INDEX_"))exchange(Map.of("op","index-bind","actor",actor.userId(),"serverRequest",actor.requestId(),"corpus",actualCorpus),call.remaining());
            var frame=new LinkedHashMap<String,Object>();frame.put("op","dispatch");frame.put("path",path);
            frame.put("body",Base64.getEncoder().encodeToString(body));frame.put("timeoutMs",Math.max(1,call.remaining().toMillis()));
            frame.put("pgReservation",reservation);frame.put("actor",actor.userId());frame.put("serverRequest",actor.requestId());frame.put("corpus",actualCorpus);
            // A guard rejection after PG reserve retains its UNKNOWN reservation. No compensating refund/retry.
            return exchange(frame,call.remaining()).path("response");
            }
            catch(RuntimeException failure){halt();throw failure;}
        });
    }
    public AiObservability workerObservation(String corpus){
        return new AiObservability(){
            @Override public <T>T correlated(String id,Supplier<T> work){
                var a=ProviderAccess.actor();
                if(!a.requestId().equals(id))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
                control(Map.of("op","bind","actor",a.userId(),"serverRequest",id,"corpus",corpus.equals("ACTIVE")?corpus():corpus));
                try{return super.correlated(id,work);}
                finally{control(Map.of("op","exit","actor",a.userId(),"serverRequest",id));}
            }
        };
    }
    public FilterRegistrationBean<Filter> admission(String corpus){
        var filter=new FilterRegistrationBean<Filter>();filter.setOrder(-99);
        filter.setFilter((request,response,chain)->{
            var req=(HttpServletRequest)request;
            if(!req.getRequestURI().equals("/api/ai/agent/answers")){chain.doFilter(request,response);return;}
            byte[] bytes=req.getInputStream().readNBytes(4097);
            var auth=SecurityContextHolder.getContext().getAuthentication();Object principal=auth==null?null:auth.getPrincipal();
            try{
                if(!(principal instanceof AuthenticatedUser u)||bytes.length>4096)throw new IOException();
                var body=json.readTree(bytes);if(body==null||!body.isObject())throw new IOException();
                control(Map.of("op","admit","actor",u.userId(),"body",body,"corpus",corpus.equals("ACTIVE")?corpus():corpus));
            }catch(Exception e){((HttpServletResponse)response).sendError(403);return;}
            chain.doFilter(new HttpServletRequestWrapper(req){
                @Override public ServletInputStream getInputStream(){
                    var in=new ByteArrayInputStream(bytes);return new ServletInputStream(){
                        public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}
                        public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
                    };
                }
            },response);
        });
        return filter;
    }
    private void halt(){
        // Only this channel's newly-created evidence directory; existing STOP is never overwritten.
        try{Files.writeString(folder.resolve("STOP"),"V2_UNCERTAIN_DO_NOT_REPLAY",StandardOpenOption.CREATE_NEW);}catch(IOException ignored){}
        closed=true;child.destroyForcibly();reader.shutdownNow();
    }
    @Override public void close(){
        closed=true;try{child.getOutputStream().close();}catch(IOException ignored){}child.destroy();reader.shutdownNow();
        try{if(!child.waitFor(2,TimeUnit.SECONDS))child.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();child.destroyForcibly();}
    }
}
