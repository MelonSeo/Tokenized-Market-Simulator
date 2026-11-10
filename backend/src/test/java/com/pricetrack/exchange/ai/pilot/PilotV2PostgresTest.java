package com.pricetrack.exchange.ai.pilot;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.provider.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual owned PostgreSQL -> normal provider -> private Python -> SQLite. No external sender/key. */
@EnabledIfEnvironmentVariable(named="AI_PILOT_V2_PG_TESTS",matches="true")
class PilotV2PostgresTest extends PilotV2ConnectionTest {
    final Properties owned=new Properties();
    @BeforeEach void ownedConfig()throws Exception{
        try(var in=Files.newInputStream(Path.of(System.getenv("AI_PILOT_V2_RUN")))){owned.load(in);}
        assertThat(owned.getProperty("identity")).matches("pilot-v2-[a-f0-9]{16}");
        assertThat(owned.getProperty("container")).isEqualTo(owned.getProperty("identity")+"-pg");
        assertThat(owned.getProperty("db.url")).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/exchange_ai_pilot_[a-f0-9]+\\?.*");
    }
    AiProperties database()throws Exception{
        String name="exchange_ai_pilot_budget_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(owned.getProperty("db.url"),owned.getProperty("db.user"),owned.getProperty("db.password"));var s=c.createStatement()){s.execute("create database "+name);}
        var p=properties();return new AiProperties(true,owned.getProperty("db.url").replaceFirst("/exchange_ai_pilot_[a-f0-9]+\\?","/"+name+"?"),owned.getProperty("db.user"),owned.getProperty("db.password"),p.knowledgeRoot(),p.manifest(),"",p.apiBase(),p.embeddingModel(),p.chatModel(),1200,60,5,.25,20);
    }
    PgProviderBudget.Limits limits(){return new PgProviderBudget.Limits("UNPAID_V2_PG_NOT_APPROVAL",18_000_000_000L,18_000_000_000L,92,70,100,5);}
    List<Map<String,Object>> snapshot(AiProperties p)throws Exception{
        try(var c=DriverManager.getConnection(p.jdbcUrl(),p.dbUser(),p.dbPassword())){return PilotV2BudgetSnapshot.read(c,p.jdbcUrl().split("/")[3].split("\\?")[0]);}
    }
    void admitIndex(PilotV2Channel channel){channel.control(Map.of("op","claim","phase","INDEX_LEGACY"));channel.control(Map.of("op","admit","actor",3,"body",Map.of("indexCorpus","legacy9"),"corpus","legacy9"));}
    @Test void actualPgBothGuardsAndRestartHaveOneReservation()throws Exception{
        var p=database();Path run=prepare();String server=UUID.randomUUID().toString();
        try(var pg=new PgProviderBudget(p,limits(),Clock.systemUTC());var channel=new PilotV2Channel(repo,run,Path.of(python),json)){
            pg.initialize();admitIndex(channel);
            var first=channel.provider(p,pg,"legacy9");var second=channel.provider(p,pg,"ACTIVE");
            ProviderAccess.call(3,server,()->first.embed(List.of("controlled first corpus")));
            ProviderAccess.call(3,server,()->second.embed(List.of("same authority second provider")));
            assertThat(channel.control(Map.of("op","reconcile","pgRows",snapshot(p))).path("matching").asBoolean()).isTrue();
            assertThat(snapshot(p)).hasSize(2).allMatch(r->r.get("state").equals("OBSERVED"));
        }
        try(var pg=new PgProviderBudget(p,limits(),Clock.systemUTC());var channel=new PilotV2Channel(repo,run,Path.of(python),json)){
            pg.initialize();assertThat(channel.control(Map.of("op","summary")).path("embeddingCalls").asInt()).isEqualTo(2);
            ProviderAccess.call(3,server,()->channel.provider(p,pg,"legacy9").embed(List.of("restart keeps allocation")));
            assertThat(channel.control(Map.of("op","reconcile","pgRows",snapshot(p))).path("matching").asBoolean()).isTrue();
            assertThat(snapshot(p)).hasSize(3);
        }
    }
    @Test void actualPgCommitThenPythonRefusalIsUnknownNotRefundOrReplay()throws Exception{
        var p=database();Path run=prepare();
        try(var pg=new PgProviderBudget(p,limits(),Clock.systemUTC());var channel=new PilotV2Channel(repo,run,Path.of(python),json)){
            pg.initialize();admitIndex(channel);
            assertThatThrownBy(()->ProviderAccess.call(3,UUID.randomUUID().toString(),()->channel.provider(p,pg,"legacy9").structured("probe",schema(),"controlled",Map.of(),1,Duration.ofSeconds(4)))).hasMessage("AI_EVALUATION_GUARD_REJECTED");
            var rows=snapshot(p);assertThat(rows).hasSize(1);assertThat(rows.getFirst().get("state")).isEqualTo("UNKNOWN");
            assertThat((Long)rows.getFirst().get("reserved")).isPositive();assertThat(Files.exists(run.resolve("STOP"))).isTrue();
        }
        try(var pg=new PgProviderBudget(p,limits(),Clock.systemUTC())){pg.initialize();assertThat(snapshot(p)).hasSize(1);}
        assertThatThrownBy(()->new PilotV2Channel(repo,run,Path.of(python),json)).hasMessage("AI_EVALUATION_BROKER_NOT_READY");
        // SQLite contains no dispatch reservation: Python rejected the phase before supplier invocation.
        var check=new ProcessBuilder(python,"-B","-c","import sqlite3,sys; c=sqlite3.connect('file:'+sys.argv[1]+'?mode=ro',uri=True); print(c.execute('select count(*) from reservations').fetchone()[0])",run.resolve("pilot-v2.sqlite").toString()).start();
        assertThat(check.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(new String(check.getInputStream().readAllBytes()).strip()).isEqualTo("0");
    }
    @Test void actualPgRejectionDispatchesZeroAndConcurrentGlobalCapIsDurable()throws Exception{
        var p=database();var sent=new AtomicInteger();byte[] wire=json.writeValueAsBytes(Map.of("model","text-embedding-3-small","input",List.of("controlled"),"dimensions",1536,"encoding_format","float"));
        try(var pg=new PgProviderBudget(p,limits(),Clock.systemUTC())){
            pg.initialize();
            for(int i=0;i<69;i++){long actor=1000+i;ProviderAccess.call(actor,UUID.randomUUID().toString(),()->pg.reserve("embeddings",wire));}
            var pool=Executors.newFixedThreadPool(8);var success=new AtomicInteger();
            try{var futures=new ArrayList<Future<?>>();for(int i=0;i<8;i++){long actor=2000+i;futures.add(pool.submit(()->{try{ProviderAccess.call(actor,UUID.randomUUID().toString(),()->pg.reserve("embeddings",wire));success.incrementAndGet();}catch(AiFailure e){assertThat(e.code()).isEqualTo("AI_BUDGET_EXHAUSTED");}}));}for(var f:futures)f.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
            assertThat(success.get()).isEqualTo(1);assertThat(snapshot(p)).hasSize(70);
            var provider=OpenAiProvider.forReservedEvaluation(p,json,pg,(a,b,c,d,e)->{sent.incrementAndGet();throw new AssertionError();});
            assertThatThrownBy(()->ProviderAccess.call(3,UUID.randomUUID().toString(),()->provider.embed(List.of("blocked")))).hasMessage("AI_BUDGET_EXHAUSTED");assertThat(sent.get()).isZero();
        }
        try(var pg=new PgProviderBudget(p,limits(),Clock.systemUTC())){pg.initialize();assertThat(snapshot(p)).hasSize(70);}
    }
}
