package com.pricetrack.exchange.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import java.nio.file.*;
import java.time.*;
import java.util.Map;

/** Evaluation-only best-effort, bounded metadata. No exception messages, request/response bodies or credentials. */
final class EvaluationStageTrace {
    enum Phase { FRAME_SEND_BEGIN, FRAME_FLUSHED, BROKER_REPLIED, RESPONSE_DECODED, GUARD_REJECTED, ABORTED }
    enum Code { OK, BROKER_REPLY_TIMEOUT, INTERRUPTED, IO_OR_PROTOCOL_FAILURE, GUARD_REJECTED }
    private static final ObjectMapper JSON=new ObjectMapper();
    static void transport(Path run,String id,Phase phase,Code code,long start,Duration timeout) {
        write(run.resolve("java-transport-diagnostics.jsonl"),Map.of("version",1,
            "requestId",id!=null&&id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")?id:"UNVERIFIED",
            "phase",phase.name(),"resultCode",code.name(),"elapsedMillis",Math.max(0,(System.nanoTime()-start)/1_000_000),
            "timeoutMillis",Math.max(0,timeout.toMillis()),"observedAt",Instant.now().toString()));
    }
    static void retrieval(Path run,AuthorizedKnowledgeRetrieval.Diagnostic step) {
        write(run.resolve("retrieval-diagnostics.jsonl"),Map.of("version",1,"stage",step.stage().name(),"success",step.success(),
            "resultCode",step.code(),"elapsedMillis",step.elapsedMillis(),"remainingMillis",step.remainingMillis(),
            "budgetMillis",step.budgetMillis(),"observedAt",Instant.now().toString()));
    }
    private static synchronized void write(Path file,Map<String,Object> value) {
        try {
            if(Files.isSymbolicLink(file) || Files.exists(file)&&Files.size(file)>1_000_000)return;
            Files.writeString(file,JSON.writeValueAsString(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS);
        } catch(Exception ignored) { /* A diagnostic failure must not replace the original result or STOP policy. */ }
    }
}
