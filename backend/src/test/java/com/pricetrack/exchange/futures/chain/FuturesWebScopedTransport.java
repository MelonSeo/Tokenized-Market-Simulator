package com.pricetrack.exchange.futures.chain;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.evaluation.GuardedAiEvaluationRunner;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Presentation test-classpath adapter only; no credential, HTTP or live toggle. */
public final class FuturesWebScopedTransport implements OpenAiProvider.EvaluationTransport,AutoCloseable {
    public static final String QUESTION="내 Long 포지션은 왜 강제청산됐나요? 기록된 근거로 진단해주세요.";
    private final ObjectMapper json;private final Path fixture;private volatile GuardedAiEvaluationRunner runner;
    private long user;private String position;private boolean submitted;private volatile boolean closed;
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"web-scope-watchdog");t.setDaemon(true);return t;});
    public FuturesWebScopedTransport(ObjectMapper json,Path fixture){this.json=json;this.fixture=fixture;watchdog.schedule(this::expire,600,TimeUnit.SECONDS);}
    void expire(){closed=true;var current=runner;if(current!=null)current.abortWeb();watchdog.shutdownNow();}
    public synchronized void bind(long owner,String id)throws Exception {
        if(closed||runner!=null||user!=0)throw new AiFailure("AI_WEB_ALREADY_BOUND");
        Path repo=Path.of("..").toRealPath(),index=fixture.resolve("ai-db"),run=fixture.resolve("ai-web");
        var config=json.readTree(fixture.resolve("web-preparation.json").toFile());
        String mode=config.path("mode").asText("offline");if(!Set.of("offline","live").contains(mode))throw new AiFailure("AI_WEB_MODE_INVALID");
        Path approval=mode.equals("live")?run.resolve("approval.json"):null;
        var command=List.of("python","-B","-S",repo.resolve("deployment/ai_web_demo_broker.py").toString(),"--prepare","--mode",mode,"--run-dir",run.toString(),"--index",index.toString(),"--q12",config.path("q12").asText(),"--registry",config.path("registry").asText(),"--user",Long.toString(owner),"--position",id);
        if(mode.equals("live")){
            // Approval is issued only AFTER a separately prepared identity and dry-run.
            // Never recreate or relabel a simulation ledger as a live execution.
            var manifest=json.readTree(run.resolve("run.json").toFile());
            if(!manifest.path("mode").asText().equals("live")||manifest.path("web").path("userId").asLong()!=owner||!manifest.path("web").path("positionId").asText().equals(id)||!Files.isRegularFile(approval))throw new AiFailure("AI_WEB_APPROVAL_REQUIRED");
        }else{
            var builder=new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).redirectOutput(fixture.resolve("web-prepare.private.log").toFile());
            sanitize(builder);var p=builder.start();if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroyForcibly();throw new AiFailure("AI_WEB_PREPARATION_TIMEOUT");}if(p.exitValue()!=0)throw new AiFailure("AI_WEB_PREPARATION_FAILED");
        }
        runner=new GuardedAiEvaluationRunner(repo,run,Path.of("python"),mode.equals("live")?GuardedAiEvaluationRunner.Mode.LIVE:GuardedAiEvaluationRunner.Mode.OFFLINE,approval,json,repo.resolve("deployment/ai_web_demo_broker.py"),index);
        if(closed){runner.abortWeb();throw new AiFailure("AI_WEB_DEADLINE");}
        user=owner;position=id;
    }
    static void sanitize(ProcessBuilder builder){var old=new HashMap<>(builder.environment());builder.environment().clear();for(String key:List.of("SystemRoot","WINDIR","PATH","Path","TEMP","TMP"))if(old.containsKey(key))builder.environment().put(key,old.get(key));}
    public synchronized void authorize(AuthenticatedUser actor,JsonNode body)throws Exception {
        try {
            if(closed||runner==null||actor==null||actor.userId()!=user||!QUESTION.equals(body.path("question").asText())||!"futures-liquidation-diagnosis".equals(body.path("skillId").asText())||!position.equals(body.path("target").path("positionId").asText())||body.size()!=3||body.path("target").size()!=1)throw new AiFailure("AI_WEB_SCOPE_FORBIDDEN");
            if(submitted)throw new AiFailure("AI_WEB_ALREADY_SUBMITTED");submitted=true;runner.claimWebSubmission();
        }catch(Exception failure){close();throw failure;}
    }
    public synchronized JsonNode post(String path,byte[] body,Duration timeout){if(runner==null||!submitted)throw new AiFailure("AI_WEB_NOT_SUBMITTED");return runner.guardedWebPost(path,body,timeout);}
    public synchronized JsonNode summary(){return runner.summary();}
    public synchronized boolean stopped(){return closed;}
    @Override public synchronized void close(){closed=true;if(runner!=null)runner.stop();watchdog.shutdownNow();}
}
