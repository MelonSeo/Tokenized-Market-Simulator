package com.pricetrack.exchange.ai.readiness;
import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.provider.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.sun.net.httpserver.HttpServer;

@EnabledIfEnvironmentVariable(named="AI_READINESS_TESTS",matches="true")
class AiProviderBudgetPostgresTest {
    private AiProperties ownedDatabase(String prefix)throws Exception{
        var p=new Properties();try(var in=Files.newInputStream(Path.of(System.getenv("AI_READINESS_RUN")))){p.load(in);}
        assertThat(p.getProperty("container")).isEqualTo("tms-ai-readiness-"+p.getProperty("identity"));
        String name=prefix+UUID.randomUUID().toString().replace("-","");assertThat(name).matches("[a-z0-9_]+");
        try(var c=DriverManager.getConnection(p.getProperty("db.url"),p.getProperty("db.user"),p.getProperty("db.password"));var s=c.createStatement()){s.execute("create database "+name);}
        String url=p.getProperty("db.url").replaceFirst("/[^/?]+\\?","/"+name+"?");
        return new AiProperties(true,url,p.getProperty("db.user"),p.getProperty("db.password"),".",".","","https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
    }
    private static final class MutableClock extends Clock {
        private Instant now=Instant.parse("2026-10-31T12:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}void nextDay(){now=now.plus(Duration.ofDays(1));}
    }
    @Test void wrongDatabaseIsRejectedBeforeAnySchemaMutation()throws Exception{
        var ai=ownedDatabase("tms_budget_denied_");var limits=new PgProviderBudget.Limits("UNPAID_DENIED_DATABASE",1_000_000L,1_000_000L,10,10,10,10);
        try(var b=new PgProviderBudget(ai,limits,Clock.systemUTC())){assertThatThrownBy(b::initialize).hasMessage("AI_DATABASE_TARGET_INVALID");}
        try(var c=DriverManager.getConnection(ai.jdbcUrl(),ai.dbUser(),ai.dbPassword());var s=c.createStatement();var r=s.executeQuery("select exists(select 1 from pg_namespace where nspname='ai_provider_budget')")){r.next();assertThat(r.getBoolean(1)).isFalse();}
    }
    @Test void monetaryBoundariesDailyRolloverAndConcurrentReservationsFailBeforeSend()throws Exception{
        var json=new ObjectMapper();byte[] body=json.writeValueAsBytes(Map.of("model","text-embedding-3-small","input",List.of("test")));long cost=PgProviderBudget.estimate("embeddings",body).reservedNusd();
        var clock=new MutableClock();var ai=ownedDatabase("exchange_ai_budget_day_");
        var limits=new PgProviderBudget.Limits("UNPAID_MONEY_BOUNDARY",cost*2,cost,99,99,99,20);
        try(var b=new PgProviderBudget(ai,limits,clock)){
            b.initialize();ProviderAccess.call(1,UUID.randomUUID().toString(),()->b.reserve("embeddings",body));
            assertThatThrownBy(()->ProviderAccess.call(2,UUID.randomUUID().toString(),()->b.reserve("embeddings",body))).hasMessage("AI_BUDGET_DAILY_LIMIT");
            clock.nextDay();ProviderAccess.call(1,UUID.randomUUID().toString(),()->b.reserve("embeddings",body));
            assertThatThrownBy(()->ProviderAccess.call(2,UUID.randomUUID().toString(),()->b.reserve("embeddings",body))).hasMessage("AI_BUDGET_EXHAUSTED");
        }
        var tiny=ownedDatabase("exchange_ai_budget_tiny_");
        try(var b=new PgProviderBudget(tiny,new PgProviderBudget.Limits("UNPAID_ONE_NANO_TOO_LOW",cost-1,cost-1,99,99,99,20),clock)){
            b.initialize();var sent=new AtomicInteger();var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{sent.incrementAndGet();e.sendResponseHeaders(500,-1);e.close();});server.start();
            try{var local=new AiProperties(true,tiny.jdbcUrl(),tiny.dbUser(),tiny.dbPassword(),".",".","unpaid-test-only","http://127.0.0.1:"+server.getAddress().getPort()+"/",tiny.embeddingModel(),tiny.chatModel(),1200,60,5,.25,20);var provider=new OpenAiProvider(local,json).budget(b);
                assertThatThrownBy(()->ProviderAccess.call(1,UUID.randomUUID().toString(),()->provider.embed(List.of("test")))).hasMessage("AI_BUDGET_EXHAUSTED");assertThat(sent.get()).isZero();
            }finally{server.stop(0);}
        }
        var concurrent=ownedDatabase("exchange_ai_budget_race_");
        try(var b=new PgProviderBudget(concurrent,new PgProviderBudget.Limits("UNPAID_MONEY_CONCURRENT",cost*2,cost*2,99,99,99,20),clock)){
            b.initialize();var pool=Executors.newFixedThreadPool(8);var ok=new AtomicInteger();try{var fs=new ArrayList<Future<?>>();for(int i=0;i<8;i++){long actor=100+i;fs.add(pool.submit(()->{try{ProviderAccess.call(actor,UUID.randomUUID().toString(),()->b.reserve("embeddings",body));ok.incrementAndGet();}catch(AiFailure e){assertThat(e.code()).isEqualTo("AI_BUDGET_EXHAUSTED");}}));}for(var f:fs)f.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertThat(ok.get()).isEqualTo(2);
        }
    }
    @Test void newOwnedPostgresEnforcesReservationsAcrossRestartAndConcurrentRequests()throws Exception{
        var p=new Properties();try(var in=Files.newInputStream(Path.of(System.getenv("AI_READINESS_RUN")))){p.load(in);}
        assertThat(p.getProperty("container")).isEqualTo("tms-ai-readiness-"+p.getProperty("identity"));
        var ai=new AiProperties(true,p.getProperty("db.url"),p.getProperty("db.user"),p.getProperty("db.password"),".",".","","https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        var limits=new PgProviderBudget.Limits("UNPAID_TEST_NOT_APPROVAL",500_000_000L,500_000_000L,2,3,3,2);var clock=Clock.systemUTC();var json=new ObjectMapper();
        byte[] llm=json.writeValueAsBytes(Map.of("model","gpt-5.6-terra","service_tier","default","store",false,"max_output_tokens",400,"input","test"));
        byte[] emb=json.writeValueAsBytes(Map.of("model","text-embedding-3-small","input",List.of("test")));
        String req=UUID.randomUUID().toString();
        try(var b=new PgProviderBudget(ai,limits,clock)){
            b.initialize();String observed=ProviderAccess.call(1,req,()->b.reserve("responses",llm));
            for(String invalidUsage:List.of("{\"input_tokens\":10}","{\"prompt_tokens\":10}","{\"input_tokens\":18446744073709551616,\"output_tokens\":3}"))b.observed(observed,json.readTree(invalidUsage));
            try(var c=DriverManager.getConnection(ai.jdbcUrl(),ai.dbUser(),ai.dbPassword());var s=c.prepareStatement("select state,input_tokens,output_tokens from ai_provider_budget.reservation where id=?::uuid")){s.setString(1,observed);try(var r=s.executeQuery()){r.next();assertThat(r.getString(1)).isEqualTo("UNKNOWN");assertThat(r.getObject(2)).isNull();assertThat(r.getObject(3)).isNull();}}
            b.observed(observed,json.readTree("{\"input_tokens\":10,\"output_tokens\":3}"));
            ProviderAccess.call(1,req,()->b.reserve("responses",llm)); // UNKNOWN: full reservation retained
            ProviderAccess.call(1,UUID.randomUUID().toString(),()->b.reserve("embeddings",emb));
            assertThatThrownBy(()->ProviderAccess.call(1,UUID.randomUUID().toString(),()->b.reserve("embeddings",emb))).hasMessage("AI_ACCOUNT_RATE_LIMIT");
        }
        try(var b=new PgProviderBudget(ai,limits,clock)){
            b.initialize();assertThatThrownBy(()->ProviderAccess.call(2,UUID.randomUUID().toString(),()->b.reserve("responses",llm))).hasMessage("AI_BUDGET_EXHAUSTED");
            var pool=Executors.newFixedThreadPool(8);var successes=new AtomicInteger();try{var fs=new ArrayList<Future<?>>();for(int i=0;i<8;i++){long actor=100+i;fs.add(pool.submit(()->{try{ProviderAccess.call(actor,UUID.randomUUID().toString(),()->b.reserve("embeddings",emb));successes.incrementAndGet();}catch(AiFailure e){assertThat(e.code()).isEqualTo("AI_BUDGET_EXHAUSTED");}}));}for(var f:fs)f.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
            assertThat(successes.get()).isEqualTo(2);
            var sent=new AtomicInteger();var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{sent.incrementAndGet();e.sendResponseHeaders(500,-1);e.close();});server.start();
            try{
                var local=new AiProperties(true,ai.jdbcUrl(),ai.dbUser(),ai.dbPassword(),".",".","unpaid-test-only","http://127.0.0.1:"+server.getAddress().getPort()+"/",ai.embeddingModel(),ai.chatModel(),1200,60,5,.25,20);
                var provider=new OpenAiProvider(local,json).budget(b);
                assertThatThrownBy(()->ProviderAccess.call(2,UUID.randomUUID().toString(),()->provider.embed(List.of("test")))).hasMessage("AI_BUDGET_EXHAUSTED");assertThat(sent.get()).isZero();
            }finally{server.stop(0);}
        }
        try(var c=DriverManager.getConnection(ai.jdbcUrl(),ai.dbUser(),ai.dbPassword());var s=c.createStatement();var r=s.executeQuery("select count(*),sum(reserved),count(*) filter(where state='UNKNOWN') from ai_provider_budget.reservation")){
            assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(5);assertThat(r.getLong(2)).isEqualTo(PgProviderBudget.estimate("responses",llm).reservedNusd()*2+PgProviderBudget.estimate("embeddings",emb).reservedNusd()*3);assertThat(r.getInt(3)).isEqualTo(4);
        }
        try(var mismatch=new PgProviderBudget(ai,new PgProviderBudget.Limits("DIFFERENT_APPROVAL",500_000_000L,500_000_000L,2,3,3,2),clock)){assertThatThrownBy(mismatch::initialize).hasMessage("AI_BUDGET_POLICY_MISMATCH");}
    }
}
