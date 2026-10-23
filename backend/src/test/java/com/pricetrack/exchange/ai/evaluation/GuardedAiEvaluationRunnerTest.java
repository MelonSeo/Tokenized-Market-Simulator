package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual Java adapter -> Python child -> durable guard; all upstream responses synthetic. */
class GuardedAiEvaluationRunnerTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    final Path repo=Path.of("..").toAbsolutePath().normalize();
    final Path python=Path.of(System.getenv().getOrDefault("AI_EVAL_TEST_PYTHON","python"));
    Path prepare(String fixture)throws Exception {
        Path folder=temp.resolve(UUID.randomUUID().toString());
        var command=List.of(python.toString(),"-B","-S",repo.resolve("deployment/ai_evaluation_broker.py").toString(),"--prepare","--run-dir",folder.toString(),"--db-port","59991","--fixture",fixture);
        var p=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(p.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
        return folder;
    }
    GuardedAiEvaluationRunner start(Path run)throws Exception {
        return new GuardedAiEvaluationRunner(repo,run,python,GuardedAiEvaluationRunner.Mode.OFFLINE,null,json);
    }
    Object schema(){return Map.of("type","object","properties",Map.of("ok",Map.of("type","boolean")),"required",List.of("ok"),"additionalProperties",false);}
    @Test void actualEmbeddingRagAndAgentMethodsAllReserveThroughBroker()throws Exception {
        try(var runner=start(prepare("success"))){
            assertThat(runner.properties().apiKey()).isEmpty();assertThat(runner.properties().apiBase()).isEqualTo("guarded:stdio/");
            var preflight=runner.preflight();assertThat(preflight.get("documents")).isEqualTo(9);
            assertThat(preflight.get("embeddingTokenBound")).isLessThanOrEqualTo(100000);
            System.out.println("AI_EVAL_CORPUS_PREFLIGHT "+preflight);
            assertThat(runner.provider().embed(List.of("문서1","문서2"))).hasSize(2);
            assertThat(runner.provider().answer("정책",List.of()).status()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(runner.model().planWithSkills("정책",Subject.NONE,List.of("futures-liquidation-diagnosis"),Duration.ofSeconds(3)).route()).isEqualTo(Route.KNOWLEDGE);
            assertThat(runner.model().synthesize("정책",Route.KNOWLEDGE,json.createObjectNode(),Duration.ofSeconds(3)).status()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(runner.summary().path("llm_calls").asInt()).isEqualTo(3);
            assertThat(runner.summary().path("embedding_calls").asInt()).isEqualTo(1);
            assertThat(runner.summary().path("reserved_nusd").asLong()).isGreaterThan(0);
        }
    }
    @Test void defaultProviderCannotSilentlyReplaceGuardedTransport()throws Exception {
        try(var runner=start(prepare("success"))){
            var accidental=new OpenAiProvider(runner.properties(),json);
            assertThatThrownBy(()->accidental.embed(List.of("x"))).hasMessage("AI_API_KEY_MISSING");
            var http=OpenAiProvider.class.getDeclaredField("http");http.setAccessible(true);
            assertThat(http.get(runner.provider())).isNull();
            assertThat(runner.summary().path("embedding_calls").asInt()).isZero();
            assertThatThrownBy(()->runner.postWithId("https://api.openai.com/v1/responses",new byte[]{123,125},Duration.ofSeconds(1),UUID.randomUUID().toString()))
                    .hasMessage("AI_EVALUATION_GUARD_REJECTED");
        }
    }
    @Test void llmLimitCannotBeResetByNewJavaRunner()throws Exception {
        Path run=prepare("success");
        try(var runner=start(run)){
            for(int i=0;i<32;i++)assertThat(runner.provider().structured("probe",schema(),"test",Map.of("q","x"),1,Duration.ofSeconds(2)).output().path("ok").asBoolean()).isTrue();
        }
        try(var resumed=start(run)){
            assertThat(resumed.summary().path("llm_calls").asInt()).isEqualTo(32);
            assertThatThrownBy(()->resumed.provider().structured("probe",schema(),"test",Map.of(),1,Duration.ofSeconds(2)))
                    .hasMessage("AI_EVALUATION_GUARD_REJECTED");
            assertThat(resumed.summary().path("llm_calls").asInt()).isEqualTo(32);
        }
    }
    @Test void uncertainUpstreamDoesNotRetryOrRefundAndRestartStaysBlocked()throws Exception {
        Path run=prepare("timeout");
        try(var runner=start(run)){
            assertThatThrownBy(()->runner.provider().embed(List.of("x"))).hasMessage("AI_EVALUATION_GUARD_REJECTED");
            assertThat(runner.summary().path("embedding_calls").asInt()).isEqualTo(1);
        }
        try(var resumed=start(run)){
            assertThatThrownBy(()->resumed.provider().embed(List.of("x"))).hasMessage("AI_EVALUATION_GUARD_REJECTED");
            assertThat(resumed.summary().path("embedding_calls").asInt()).isEqualTo(1);
        }
    }
    @Test void duplicateUuidAtJavaBoundaryCannotSpendAgain()throws Exception {
        try(var runner=start(prepare("success"))){
            byte[] raw=json.writeValueAsBytes(Map.of("model","text-embedding-3-small","input",List.of("x"),"dimensions",1536,"encoding_format","float"));
            String id=UUID.randomUUID().toString();runner.postWithId("embeddings",raw,Duration.ofSeconds(2),id);
            assertThatThrownBy(()->runner.postWithId("embeddings",raw,Duration.ofSeconds(2),id)).hasMessage("AI_EVALUATION_GUARD_REJECTED");
            assertThat(runner.summary().path("embedding_calls").asInt()).isEqualTo(1);
        }
    }
    @Test void explicitStopIsDurableAfterRunnerRestart()throws Exception {
        Path run=prepare("success");try(var runner=start(run)){runner.stop();}
        try(var resumed=start(run)){
            assertThatThrownBy(()->resumed.provider().embed(List.of("x"))).hasMessage("AI_EVALUATION_STOPPED");
            assertThat(resumed.summary().path("embedding_calls").asInt()).isZero();
        }
    }
    @Test void jvmTimeoutKillsOnlyBrokerAndPreservesReservation()throws Exception {
        Path run=prepare("slow");
        try(var runner=start(run)){
            assertThatThrownBy(()->runner.provider().embed(List.of("x"),Duration.ofMillis(200))).hasMessage("AI_EVALUATION_TRANSPORT_UNCERTAIN");
            assertThat(Files.exists(run.resolve("STOP"))).isTrue();
            String trace=Files.readString(run.resolve("java-transport-diagnostics.jsonl"));
            assertThat(trace).contains("FRAME_FLUSHED","BROKER_REPLY_TIMEOUT").doesNotContain("Bearer","offline-sentinel","\"body\"");
        }
        try(var resumed=start(run)){
            assertThat(resumed.summary().path("embedding_calls").asInt()).isEqualTo(1);
            assertThatThrownBy(()->resumed.provider().embed(List.of("x"))).hasMessage("AI_EVALUATION_STOPPED");
        }
    }
    @Test void liveModeRequiresSeparateRunBoundApproval()throws Exception {
        Path run=prepare("success");
        assertThatThrownBy(()->new GuardedAiEvaluationRunner(repo,run,python,GuardedAiEvaluationRunner.Mode.LIVE,null,json))
                .hasMessage("AI_EVALUATION_APPROVAL_REQUIRED");
        assertThatThrownBy(()->new GuardedAiEvaluationRunner(repo,run,python,GuardedAiEvaluationRunner.Mode.LIVE,temp.resolve("absent-approval.json"),json))
                .hasMessage("AI_EVALUATION_BROKER_NOT_READY");
    }
    @Test void offlineRunnerCannotOpenExistingOrNewAiDatabase()throws Exception {
        try(var runner=start(prepare("success"))){
            assertThatThrownBy(()->runner.prepareIndex("not-read",true,new AuthenticatedUser(1L,null,UserRole.ADMIN)))
                    .hasMessage("AI_EVALUATION_OFFLINE_DB_DISABLED");
            assertThatThrownBy(runner::retrieval).hasMessage("AI_EVALUATION_INDEX_NOT_READY");
            assertThat(runner.summary().path("embedding_calls").asInt()).isZero();
        }
    }
    @Test void diagnosticOutputFailureDoesNotReplaceValidatedResponseOrExposeRequestBody()throws Exception {
        Path run=prepare("success");
        Files.createDirectory(run.resolve("java-transport-diagnostics.jsonl"));
        try(var runner=start(run)) {
            assertThat(runner.provider().embed(List.of("SECRET_INPUT"))).hasSize(1);
            assertThat(runner.summary().path("embedding_calls").asInt()).isEqualTo(1);
        }
    }
}
