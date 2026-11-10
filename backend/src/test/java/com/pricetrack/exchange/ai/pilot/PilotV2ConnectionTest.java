package com.pricetrack.exchange.ai.pilot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.provider.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual provider -> private Python child -> fresh SQLite. PG boundary mocked; no PG/HTTP/Docker/model. */
class PilotV2ConnectionTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    final Path repo=Path.of("..").toAbsolutePath().normalize();
    final String python=System.getenv().getOrDefault("AI_EVAL_TEST_PYTHON","python");
    AiProperties properties(){return new AiProperties(true,"jdbc:postgresql://127.0.0.1:1/exchange_ai_pilot_never_connected","none","none","../docs/ai-knowledge","../docs/ai/ingest-manifest.json","","guarded:pilot-v2/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);}
    Object schema(){return Map.of("type","object","properties",Map.of("ok",Map.of("type","boolean")),"required",List.of("ok"),"additionalProperties",false);}
    Path prepare()throws Exception{
        Path run=temp.resolve(UUID.randomUUID().toString());Files.createDirectory(run);
        Path fixture=temp.resolve(UUID.randomUUID()+".json");
        var actors=new LinkedHashMap<String,Object>();
        for(int n=1;n<=2;n++)actors.put(n==1?"owner":"other",Map.of("uid",n,"quoteId","0x"+Integer.toString(n).repeat(64),"orderId",n,
            "pendingOrderId",n+10,"positionId","00000000-0000-0000-0000-"+String.format("%012d",n),
            "unverifiedPositionId","00000000-0000-0000-0001-"+String.format("%012d",n),"cPositionId","0x"+Integer.toString(n+3).repeat(64),"cWallet","0x"+Integer.toString(n).repeat(40)));
        actors.put("admin",Map.of("uid",3));
        Files.writeString(fixture,json.writeValueAsString(Map.of("bindingVersion",2,"actors",actors,"mode","MOCK_CONTROLLED","scope","NEW_ISOLATED_PILOT_V2_NOT_USER_DATA","financialFingerprint","a".repeat(64))));
        var process=new ProcessBuilder(python,"-B","-S",repo.resolve("deployment/ai_pilot_channel.py").toString(),"--run",run.toString(),"--prepare-unpaid","--fixture",fixture.toString()).directory(repo.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();return run;
    }
    @Test void pgRefusalSendsNothingToTheEvaluationChannel(){
        var pg=mock(PgProviderBudget.class);when(pg.reserve(anyString(),any())).thenThrow(new AiFailure("AI_BUDGET_EXHAUSTED"));
        var calls=new AtomicInteger();var provider=OpenAiProvider.forReservedEvaluation(properties(),json,pg,(p,b,t,r,a)->{calls.incrementAndGet();throw new AssertionError();});
        assertThatThrownBy(()->ProviderAccess.call(1,UUID.randomUUID().toString(),()->provider.embed(List.of("controlled")))).hasMessage("AI_BUDGET_EXHAUSTED");
        assertThat(calls.get()).isZero();verify(pg,never()).observed(any(),any());
    }
    @Test void committedPgReceiptAndActorPrecedePythonReservationAndObservation()throws Exception{
        Path run=prepare();var pg=mock(PgProviderBudget.class);when(pg.reserve(anyString(),any())).thenAnswer(i->UUID.randomUUID().toString());
        try(var channel=new PilotV2Channel(repo,run,Path.of(python),json)){
            channel.control(Map.of("op","claim","phase","INDEX_LEGACY"));
            channel.control(Map.of("op","admit","actor",3,"body",Map.of("indexCorpus","legacy9"),"corpus","legacy9"));
            var provider=channel.provider(properties(),pg,"legacy9");
            assertThat(ProviderAccess.call(3,UUID.randomUUID().toString(),()->provider.embed(List.of("controlled")))).hasSize(1);
            assertThat(channel.control(Map.of("op","summary")).path("embeddingCalls").asInt()).isEqualTo(1);
            var ordered=inOrder(pg);ordered.verify(pg).reserve(eq("embeddings"),any());ordered.verify(pg).observed(anyString(),any());
            assertThat(provider).isNotNull();
        }
    }
    @Test void evaluationRefusalAfterPgCommitKeepsUnknownAndStopsRestart()throws Exception{
        Path run=prepare();var pg=mock(PgProviderBudget.class);when(pg.reserve(anyString(),any())).thenAnswer(i->UUID.randomUUID().toString());
        try(var channel=new PilotV2Channel(repo,run,Path.of(python),json)){
            channel.control(Map.of("op","claim","phase","INDEX_LEGACY"));
            channel.control(Map.of("op","admit","actor",3,"body",Map.of("indexCorpus","legacy9"),"corpus","legacy9"));
            var provider=channel.provider(properties(),pg,"legacy9");
            assertThatThrownBy(()->ProviderAccess.call(3,UUID.randomUUID().toString(),()->provider.structured("probe",schema(),"controlled",Map.of(),1,Duration.ofSeconds(3)))).hasMessage("AI_EVALUATION_GUARD_REJECTED");
            verify(pg,times(1)).reserve(eq("responses"),any());verify(pg,never()).observed(any(),any());
            assertThat(Files.readString(run.resolve("STOP"))).isEqualTo("V2_UNCERTAIN_DO_NOT_REPLAY");
        }
        assertThatThrownBy(()->new PilotV2Channel(repo,run,Path.of(python),json)).hasMessage("AI_EVALUATION_BROKER_NOT_READY");
    }
    @Test void eitherGuardExceptionDoesNotDispatchAgainOrRefund(){
        var pg=mock(PgProviderBudget.class);String id=UUID.randomUUID().toString();when(pg.reserve(anyString(),any())).thenReturn(id);
        var calls=new AtomicInteger();var provider=OpenAiProvider.forReservedEvaluation(properties(),json,pg,(p,b,t,r,a)->{
            assertThat(r).isEqualTo(id);assertThat(a.userId()).isEqualTo(7);calls.incrementAndGet();throw new AiFailure("AI_EVALUATION_GUARD_REJECTED");});
        assertThatThrownBy(()->ProviderAccess.call(7,UUID.randomUUID().toString(),()->provider.embed(List.of("controlled")))).hasMessage("AI_EVALUATION_GUARD_REJECTED");
        assertThat(calls.get()).isEqualTo(1);verify(pg,times(1)).reserve(anyString(),any());verify(pg,never()).observed(any(),any());
    }
    @Test void reservedConnectionCannotReplaceBudgetOrImportAKey(){
        var pg=mock(PgProviderBudget.class);var provider=OpenAiProvider.forReservedEvaluation(properties(),json,pg,(p,b,t,r,a)->json.nullNode());
        assertThatThrownBy(()->provider.budget(ProviderBudget.DENY)).hasMessage("AI_EVALUATION_BUDGET_IMMUTABLE");
        var p=properties();var keyed=new AiProperties(true,p.jdbcUrl(),p.dbUser(),p.dbPassword(),p.knowledgeRoot(),p.manifest(),"UNPAID_NOT_A_KEY",p.apiBase(),p.embeddingModel(),p.chatModel(),1200,60,5,.25,20);
        assertThatThrownBy(()->OpenAiProvider.forReservedEvaluation(keyed,json,pg,(a,b,c,d,e)->json.nullNode())).hasMessage("AI_EVALUATION_CONFIG_INVALID");
    }
    @Test void trialBoundaryAllowsReadsAndProofButNoFinancialWrite(){
        assertThat(PilotV2TestConfiguration.allowed("POST","/api/c-futures/wallet/link")).isTrue();
        assertThat(PilotV2TestConfiguration.allowed("POST","/api/ai/tools/getCPosition")).isTrue();
        assertThat(PilotV2TestConfiguration.allowed("POST","/api/ai/agent/answers")).isTrue();
        for(String path:List.of("/api/c-futures/direct/intents","/api/orders/buy","/api/ai/tools/unknown","/api/auth/signup"))
            assertThat(PilotV2TestConfiguration.allowed("POST",path)).isFalse();
        assertThat(PilotV2TestConfiguration.allowed("DELETE","/api/me")).isFalse();
    }
    @Test void workerMustActuallyExitBeforeCompletionEvenWithoutModelCalls()throws Exception{
        Path run=prepare();
        try(var channel=new PilotV2Channel(repo,run,Path.of(python),json)){
            channel.control(Map.of("op","claim","phase","INDEX_LEGACY"));
            channel.control(Map.of("op","admit","actor",3,"body",Map.of("indexCorpus","legacy9"),"corpus","legacy9"));
            // Same actual worker hook as Agent; no fabricated provider call is needed to bind a denied read.
            String id=UUID.randomUUID().toString();
            ProviderAccess.call(3,id,()->channel.workerObservation("legacy9").correlated(id,()->{
                assertThat(channel.control(Map.of("op","summary")).path("embeddingCalls").asInt()).isZero();return true;
            }));
            var ticket=channel.control(Map.of("op","index-request")).path("serverRequest").asText();
            assertThat(ticket).isEqualTo(id);
        }
    }
}
