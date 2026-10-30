package com.pricetrack.exchange.ai.evaluation;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.OpenAiAgentProvider;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.provider.CallDeadline;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Evaluation entry point; never use legacy environment-gated live tests.
 * All RAG/Agent/Skill calls share this one provider/broker/ledger. No API key in JVM.
 */
public final class GuardedAiEvaluationRunner implements AutoCloseable {
    public enum Mode { OFFLINE, LIVE }
    private final ObjectMapper json;
    private final Path run;
    private final JsonNode meta;
    private final Process broker;
    private final ExecutorService reader=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"evaluation-broker-reader");t.setDaemon(true);return t;});
    private final AiProperties properties;
    private final OpenAiProvider provider;
    private final Mode mode;
    private PgKnowledgeStore verifiedStore;
    private com.pricetrack.exchange.ai.provider.QueryVectorCache queryVectors;
    public void queryVectorCache(boolean enabled){if(queryVectors==null)throw new AiFailure("AI_EVALUATION_INDEX_NOT_READY");queryVectors.enabled(enabled);}
    public void clearQueryVectorCache(){if(queryVectors==null)throw new AiFailure("AI_EVALUATION_INDEX_NOT_READY");queryVectors.clear();}
    private volatile boolean closed;
    Runnable afterFrameFlushed=()->{}; // deterministic test seam; never configured by runtime input

    public GuardedAiEvaluationRunner(Path repo, Path run, Path python, Mode mode, Path approval, ObjectMapper json) throws Exception {
        this(repo,run,python,mode,approval,json,null,null);
    }
    public GuardedAiEvaluationRunner(Path repo, Path run, Path python, Mode mode, Path approval, ObjectMapper json,Path webScript,Path index) throws Exception {
        this.json=json;this.run=run.toRealPath();this.mode=mode;
        meta=json.readTree(Files.readAllBytes(this.run.resolve("run.json")));
        String db=meta.path("dbName").asText();int port=meta.path("dbPort").asInt();
        if(!db.matches("exchange_ai_eval_[0-9a-f]{32}")||!db.equals(meta.path("dbUser").asText())||port<49152||port>65535)
            throw new AiFailure("AI_EVALUATION_TARGET_INVALID");
        if(mode==null || (mode==Mode.LIVE)!=(approval!=null))throw new AiFailure("AI_EVALUATION_APPROVAL_REQUIRED");
        properties=new AiProperties(true,"jdbc:postgresql://127.0.0.1:"+port+"/"+db,db,"",
                repo.resolve("docs/ai-knowledge").toString(),repo.resolve("docs/ai/ingest-manifest.json").toString(),
                "","guarded:stdio/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        if(!new KnowledgeLoader(properties,json).load().fingerprint().equals(meta.path("corpusHash").asText()))
            throw new AiFailure("AI_EVALUATION_CORPUS_MISMATCH");
        if(webScript!=null && (!webScript.toRealPath().equals(repo.resolve("deployment/ai_web_demo_broker.py").toRealPath())||index==null))throw new AiFailure("AI_WEB_MODE_INVALID");
        var command=new ArrayList<>(List.of(python.toString(),"-B","-S",(webScript==null?repo.resolve("deployment/ai_evaluation_broker.py"):webScript).toString(),"--run-dir",this.run.toString()));
        if(webScript!=null){command.add("--index");command.add(index.toString());}
        if(mode==Mode.LIVE){command.add("--live");command.add("--approval");command.add(approval.toString());}
        var builder=new ProcessBuilder(command).directory(repo.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        // Do not import .env, live flags, OpenAI key, proxy, PYTHONPATH/startup hooks.
        var old=new HashMap<>(builder.environment());builder.environment().clear();
        for(String key:List.of("SystemRoot","WINDIR","PATH","Path","TEMP","TMP"))
            if(old.containsKey(key))builder.environment().put(key,old.get(key));
        broker=builder.start();
        try {
            var ready=receive(Duration.ofSeconds(10));
            if(!ready.path("ready").asBoolean()||!ready.path("mode").asText().equals(mode==Mode.LIVE?"live":"offline")
                    ||!ready.path("sourceHash").asText().equals(meta.path("sourceHash").asText())
                    ||!ready.path("corpusHash").asText().equals(meta.path("corpusHash").asText()))
                throw new AiFailure("AI_EVALUATION_BROKER_NOT_READY");
            provider=OpenAiProvider.forGuardedEvaluation(properties,json,this::post);
        } catch(Exception failure){close();throw new AiFailure("AI_EVALUATION_BROKER_NOT_READY");}
    }
    public OpenAiProvider provider(){return provider;}
    public synchronized void claimWebSubmission() throws Exception {
        send(Map.of("claimSubmission",true));if(!receive(Duration.ofSeconds(3)).path("claimed").asBoolean())throw new AiFailure("AI_WEB_ALREADY_SUBMITTED");
    }
    public JsonNode guardedWebPost(String path,byte[] raw,Duration timeout){return post(path,raw,timeout);}
    /** Deadline thread must not wait for the synchronized in-flight stdio call. */
    public void abortWeb(){
        closed=true;
        try{Files.writeString(run.resolve("STOP"),"WEB_DEADLINE",StandardOpenOption.CREATE_NEW);}catch(IOException ignored){}
        broker.destroyForcibly();reader.shutdownNow();
    }
    public OpenAiAgentProvider model(){return new OpenAiAgentProvider(provider,json);}
    public AiProperties properties(){return properties;}
    public JsonNode identity(){return meta.deepCopy();}
    public Map<String,Long> preflight(){
        var corpus=new KnowledgeLoader(properties,json).load();
        long bytes=corpus.chunks().stream().mapToLong(c->c.content().getBytes(StandardCharsets.UTF_8).length+8).sum();
        if(corpus.chunks().size()>500||bytes>100000)throw new AiFailure("AI_EVALUATION_CORPUS_LIMIT");
        return Map.of("documents",(long)corpus.documents().size(),"chunks",(long)corpus.chunks().size(),
                "indexEmbeddingCalls",(corpus.chunks().size()+15L)/16L,"embeddingTokenBound",bytes);
    }
    public synchronized JsonNode summary(){
        if(closed)throw new AiFailure("AI_EVALUATION_STOPPED");
        try{send(Map.of("summary",true));return receive(Duration.ofSeconds(3)).path("summary");}
        catch(Exception failure){halt();throw new AiFailure("AI_EVALUATION_TRANSPORT_UNCERTAIN");}
    }

    private synchronized JsonNode post(String path,byte[] raw,Duration timeout){
        return postWithId(path,raw,timeout,UUID.randomUUID().toString());
    }
    synchronized JsonNode postWithId(String path,byte[] raw,Duration timeout,String id){
        if(closed||Files.exists(run.resolve("STOP")))throw new AiFailure("AI_EVALUATION_STOPPED");
        if(raw.length>100000 || timeout.isNegative()||timeout.isZero()||timeout.toMillis()>60000)
            throw new AiFailure("AI_EVALUATION_INPUT_LIMIT");
        long started=System.nanoTime();
        var deadline=new CallDeadline(timeout);
        boolean sent=false,consumed=false;
        try {
            EvaluationStageTrace.transport(run,id,EvaluationStageTrace.Phase.FRAME_SEND_BEGIN,EvaluationStageTrace.Code.OK,started,timeout);
            var frame=new LinkedHashMap<String,Object>();
            frame.put("id",id);frame.put("path",path);frame.put("body",Base64.getEncoder().encodeToString(raw));
            long remainingMs=Math.max(1,deadline.remaining().toMillis());frame.put("timeoutMs",remainingMs);
            if(meta.path("deadlineProtocol").asInt()==2)frame.put("deadlineEpochMs",System.currentTimeMillis()+remainingMs);
            sent=true;send(frame);
            EvaluationStageTrace.transport(run,id,EvaluationStageTrace.Phase.FRAME_FLUSHED,EvaluationStageTrace.Code.OK,started,timeout);
            afterFrameFlushed.run();
            var response=receive(deadline.remaining());
            consumed=true;
            if(meta.path("deadlineProtocol").asInt()==2&&!response.path("requestId").asText().equals(id))throw new IOException();
            EvaluationStageTrace.transport(run,id,EvaluationStageTrace.Phase.BROKER_REPLIED,EvaluationStageTrace.Code.OK,started,timeout);
            if(!response.path("ok").asBoolean()){
                EvaluationStageTrace.transport(run,id,EvaluationStageTrace.Phase.GUARD_REJECTED,EvaluationStageTrace.Code.GUARD_REJECTED,started,timeout);
                if(response.path("observed").asBoolean()&&response.path("code").asText().equals("DEADLINE_EXCEEDED_OBSERVED"))
                    throw new AiFailure("AI_PROVIDER_DEADLINE_OBSERVED");
                throw new AiFailure("AI_EVALUATION_GUARD_REJECTED");
            }
            byte[] bytes=Base64.getDecoder().decode(response.path("body").asText());
            if(bytes.length>2000000)throw new IOException();
            var result=json.readTree(bytes);
            deadline.remaining();
            EvaluationStageTrace.transport(run,id,EvaluationStageTrace.Phase.RESPONSE_DECODED,EvaluationStageTrace.Code.OK,started,timeout);
            return result;
        } catch(AiFailure failure){if(sent&&!consumed)halt();throw failure;}
        catch(Exception failure){
            EvaluationStageTrace.transport(run,id,EvaluationStageTrace.Phase.ABORTED,
                failure instanceof TimeoutException?EvaluationStageTrace.Code.BROKER_REPLY_TIMEOUT:
                failure instanceof InterruptedException?EvaluationStageTrace.Code.INTERRUPTED:EvaluationStageTrace.Code.IO_OR_PROTOCOL_FAILURE,started,timeout);
            halt();throw new AiFailure("AI_EVALUATION_TRANSPORT_UNCERTAIN");
        }
    }
    private void send(Object value)throws Exception {
        byte[] bytes=json.writeValueAsBytes(value);
        broker.getOutputStream().write(bytes);broker.getOutputStream().write('\n');broker.getOutputStream().flush();
    }
    private JsonNode receive(Duration timeout)throws Exception {
        var pending=reader.submit(()->{
            var bytes=new ByteArrayOutputStream();int next;
            while((next=broker.getInputStream().read())!='\n'){
                if(next<0||bytes.size()>=2800000)throw new IOException();
                bytes.write(next);
            }
            return json.readTree(bytes.toByteArray());
        });
        try{return pending.get(timeout.toNanos(),TimeUnit.NANOSECONDS);}
        catch(Exception failure){pending.cancel(true);throw failure;}
    }
    /** Explicit stop is durable; ordinary clean detach permits verified resume. */
    public synchronized void stop(){
        if(!closed)try{send(Map.of("stop",true));receive(Duration.ofSeconds(3));}catch(Exception ignored){}
        halt();
    }
    private void halt(){
        try{Files.writeString(run.resolve("STOP"),"STOPPED",StandardOpenOption.CREATE_NEW);}catch(IOException ignored){}
        stopTransport();
    }
    private synchronized void stopTransport(){
        if(closed)return;closed=true;
        try{broker.getOutputStream().close();}catch(IOException ignored){}
        reader.shutdownNow();
        // Billing observations may finish, but never extend the caller's deadline for cleanup.
        var cleanup=new Thread(()->{
            try{if(!broker.waitFor(2,TimeUnit.SECONDS))broker.destroyForcibly();}
            catch(InterruptedException failure){Thread.currentThread().interrupt();broker.destroyForcibly();}
        },"evaluation-broker-cleanup");cleanup.setDaemon(true);cleanup.start();
    }
    @Override public synchronized void close(){
        stopTransport();
        if(verifiedStore!=null){verifiedStore.close();verifiedStore=null;}
    }

    /** Verify new DB ownership *before* creating schema or contacting provider. */
    public void prepareIndex(String databasePassword, boolean create, AuthenticatedUser admin) throws Exception {
        if(mode!=Mode.LIVE)throw new AiFailure("AI_EVALUATION_OFFLINE_DB_DISABLED");
        if(admin==null||admin.role()!=com.pricetrack.exchange.user.UserRole.ADMIN)throw new AiFailure("AI_EVALUATION_ADMIN_REQUIRED");
        if(verifiedStore!=null)throw new AiFailure("AI_EVALUATION_INDEX_ALREADY_OPEN");
        var p=new AiProperties(true,properties.jdbcUrl(),properties.dbUser(),databasePassword,properties.knowledgeRoot(),properties.manifest(),
                "","guarded:stdio/",properties.embeddingModel(),properties.chatModel(),1200,60,5,.25,20);
        var corpus=new KnowledgeLoader(p,json).load();
        // No corpus truncation to fit a budget. Fail before any provider request.
        preflight();
        String state;
        try(var connection=db(p)){
            state=EvaluationIndexLease.claim(connection,meta,create);
            if(state.equals("PREPARED"))EvaluationIndexLease.begin(connection);
        }
        var store=new PgKnowledgeStore(p);
        try {
            if(state.equals("PREPARED"))new RagService(p,new KnowledgeLoader(p,json),store,provider,provider).ingest(admin);
            try(var connection=db(p)){
                EvaluationIndexLease.verifyReady(connection,meta,corpus.documents().size(),corpus.chunks().size(),state.equals("PREPARED"));
            }
            verifiedStore=store;
            queryVectors=new com.pricetrack.exchange.ai.provider.QueryVectorCache(provider,properties.embeddingModel(),()->meta.path("corpusHash").asText());
        }catch(Exception failure){store.close();throw failure;}
    }
    private java.sql.Connection db(AiProperties p)throws java.sql.SQLException{
        var options=new Properties();options.setProperty("user",p.dbUser());options.setProperty("password",p.dbPassword());
        options.setProperty("connectTimeout","2");options.setProperty("socketTimeout","5");
        return java.sql.DriverManager.getConnection(p.jdbcUrl(),options);
    }
    public AuthorizedKnowledgeRetrieval retrieval(){
        if(verifiedStore==null)throw new AiFailure("AI_EVALUATION_INDEX_NOT_READY");
        return new AuthorizedKnowledgeRetrieval(properties,new KnowledgeLoader(properties,json),verifiedStore,queryVectors)
            .diagnose(step->EvaluationStageTrace.retrieval(run,step));
    }
}
