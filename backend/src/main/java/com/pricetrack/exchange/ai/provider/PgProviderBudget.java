package com.pricetrack.exchange.ai.provider;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.zaxxer.hikari.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Durable pessimistic reservations. Errors/unknown usage never refund or replay a call. AI DB only. */
public final class PgProviderBudget implements ProviderBudget,AutoCloseable {
    public record Limits(String approval,long totalNusd,long dailyNusd,int llmCalls,int embeddingCalls,int actorDailyRequests,int actorMinuteRequests){
        boolean enabled(){return approval!=null&&approval.matches("[A-Za-z0-9_-]{8,80}")&&totalNusd>0&&totalNusd<=100_000_000_000L&&dailyNusd>0&&dailyNusd<=totalNusd
            &&llmCalls>0&&llmCalls<=10000&&embeddingCalls>0&&embeddingCalls<=10000&&actorDailyRequests>0&&actorDailyRequests<=1000&&actorMinuteRequests>0&&actorMinuteRequests<=20;}
    }
    public record Estimate(String kind,long inputTokens,long outputTokens,long reservedNusd){}
    private final Limits limits;private final ObjectMapper json=new ObjectMapper();private final HikariDataSource pool;private final Clock clock;private final String hash;
    public PgProviderBudget(AiProperties ai,Limits limits,Clock clock){
        this.limits=limits;this.clock=clock;
        var cfg=new HikariConfig();cfg.setJdbcUrl(ai.jdbcUrl());cfg.setUsername(ai.dbUser());cfg.setPassword(ai.dbPassword());
        cfg.setMaximumPoolSize(1);cfg.setMinimumIdle(0);cfg.setConnectionTimeout(2000);cfg.setInitializationFailTimeout(-1);cfg.setPoolName("ai-provider-budget");
        cfg.addDataSourceProperty("connectTimeout","2");cfg.addDataSourceProperty("socketTimeout","5");pool=new HikariDataSource(cfg);
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(limits)));}
        catch(Exception e){throw new AiFailure("AI_BUDGET_CONFIG_INVALID");}
    }
    /** Explicit ADMIN initialization, never implicit schema mutation on a paid request. */
    public void initialize(){
        if(!limits.enabled())throw new AiFailure("AI_BUDGET_NOT_CONFIGURED");
        try(var c=pool.getConnection();var s=c.createStatement()){
            s.setQueryTimeout(5);
            try(var db=s.executeQuery("select current_database()")){
                if(!db.next()||!db.getString(1).matches("exchange_ai(_[a-z0-9_]+)?"))throw new AiFailure("AI_DATABASE_TARGET_INVALID");
            }
            s.execute("create schema if not exists ai_provider_budget");
            s.execute("create table if not exists ai_provider_budget.policy (id int primary key check(id=1), fingerprint text not null, reserved bigint not null default 0, llm int not null default 0, embedding int not null default 0)");
            s.execute("create table if not exists ai_provider_budget.request (request_id uuid primary key, actor bigint not null, created_at timestamptz not null)");
            s.execute("create table if not exists ai_provider_budget.reservation (id uuid primary key, request_id uuid not null, day date not null, kind text not null, reserved bigint not null, state text not null, input_tokens bigint, output_tokens bigint)");
            s.execute("create index if not exists budget_actor_window on ai_provider_budget.request(actor,created_at)");
            s.execute("create index if not exists budget_reservation_day on ai_provider_budget.reservation(day)");
            try(var q=c.prepareStatement("insert into ai_provider_budget.policy(id,fingerprint) values(1,?) on conflict(id) do nothing")){q.setString(1,hash);q.executeUpdate();}
            try(var q=s.executeQuery("select fingerprint from ai_provider_budget.policy where id=1")){if(!q.next()||!hash.equals(q.getString(1)))throw new AiFailure("AI_BUDGET_POLICY_MISMATCH");}
        }catch(AiFailure e){throw e;}catch(Exception e){throw new AiFailure("AI_BUDGET_UNAVAILABLE");}
    }
    public static Estimate estimate(String path,byte[] wire){
        try{
            if(wire==null||wire.length>65536)throw new AiFailure("AI_BUDGET_INPUT_LIMIT");
            var n=new ObjectMapper().readTree(wire);long in=wire.length+1024L,out=0;String kind;
            if(path.equals("responses")&&n.path("model").asText().equals("gpt-5.6-terra")&&n.path("service_tier").asText().equals("default")&&n.path("store").isBoolean()&&!n.path("store").asBoolean()){
                kind="LLM";if(!n.path("max_output_tokens").isIntegralNumber())throw new AiFailure("AI_BUDGET_CONFIG_INVALID");out=n.path("max_output_tokens").asLong();
                if(out<=0||out>1600||n.has("tools")||n.has("previous_response_id"))throw new AiFailure("AI_BUDGET_CONFIG_INVALID");
            }else if(path.equals("embeddings")&&n.path("model").asText().equals("text-embedding-3-small"))kind="EMBEDDING";
            else throw new AiFailure("AI_BUDGET_CONFIG_INVALID");
            return new Estimate(kind,in,out,Math.addExact(Math.multiplyExact(in,kind.equals("LLM")?2500:20),Math.multiplyExact(out,12000)));
        }catch(AiFailure e){throw e;}catch(Exception e){throw new AiFailure("AI_BUDGET_CONFIG_INVALID");}
    }
    @Override public String reserve(String path,byte[] body){
        if(!limits.enabled())throw new AiFailure("AI_BUDGET_NOT_CONFIGURED");
        var a=ProviderAccess.actor();var estimate=estimate(path,body);var id=UUID.randomUUID().toString();
        // Global policy row serializes reservations across processes. No check-then-send race.
        try(var c=pool.getConnection()){
            c.setAutoCommit(false);
            try{
                long total;int count;try(var s=c.prepareStatement("select fingerprint,reserved,llm,embedding from ai_provider_budget.policy where id=1 for update")){
                    s.setQueryTimeout(5);try(var r=s.executeQuery()){if(!r.next()||!r.getString(1).equals(hash))throw new AiFailure("AI_BUDGET_POLICY_MISMATCH");total=r.getLong(2);count=r.getInt(estimate.kind.equals("LLM")?3:4);}}
                if(total>limits.totalNusd-estimate.reservedNusd||count>=(estimate.kind.equals("LLM")?limits.llmCalls:limits.embeddingCalls))throw new AiFailure("AI_BUDGET_EXHAUSTED");
                LocalDate day=LocalDate.now(clock.withZone(ZoneOffset.UTC));Instant now=clock.instant();
                try(var s=c.prepareStatement("select coalesce(sum(reserved),0) from ai_provider_budget.reservation where day=?")){s.setObject(1,day);try(var r=s.executeQuery()){r.next();if(r.getLong(1)>limits.dailyNusd-estimate.reservedNusd)throw new AiFailure("AI_BUDGET_DAILY_LIMIT");}}
                boolean known;try(var s=c.prepareStatement("select actor from ai_provider_budget.request where request_id=?::uuid")){s.setString(1,a.requestId());try(var r=s.executeQuery()){known=r.next();if(known&&r.getLong(1)!=a.userId())throw new AiFailure("AI_BUDGET_ACTOR_MISMATCH");}}
                if(!known){
                    try(var s=c.prepareStatement("select count(*) filter(where created_at>=?),count(*) filter(where created_at>=?) from ai_provider_budget.request where actor=?")){
                        s.setTimestamp(1,Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()));s.setTimestamp(2,Timestamp.from(now.minusSeconds(60)));s.setLong(3,a.userId());
                        try(var r=s.executeQuery()){r.next();if(r.getLong(1)>=limits.actorDailyRequests||r.getLong(2)>=limits.actorMinuteRequests)throw new AiFailure("AI_ACCOUNT_RATE_LIMIT");}}
                    try(var s=c.prepareStatement("insert into ai_provider_budget.request values(?::uuid,?,?)")){s.setString(1,a.requestId());s.setLong(2,a.userId());s.setTimestamp(3,Timestamp.from(now));s.executeUpdate();}
                }
                try(var s=c.prepareStatement("insert into ai_provider_budget.reservation values(?::uuid,?::uuid,?,?,?,'UNKNOWN',null,null)")){s.setString(1,id);s.setString(2,a.requestId());s.setObject(3,day);s.setString(4,estimate.kind);s.setLong(5,estimate.reservedNusd);s.executeUpdate();}
                try(var s=c.prepareStatement("update ai_provider_budget.policy set reserved=reserved+?,llm=llm+?,embedding=embedding+? where id=1")){s.setLong(1,estimate.reservedNusd);s.setInt(2,estimate.kind.equals("LLM")?1:0);s.setInt(3,estimate.kind.equals("EMBEDDING")?1:0);s.executeUpdate();}
                c.commit();return id;
            }catch(Exception e){c.rollback();throw e;}
        }catch(AiFailure e){throw e;}catch(Exception e){throw new AiFailure("AI_BUDGET_UNAVAILABLE");}
    }
    @Override public void observed(String id,JsonNode usage){
        if(usage==null)return;
        JsonNode in=usage.has("input_tokens")?usage.get("input_tokens"):usage.get("prompt_tokens"),out=usage.get("output_tokens");
        if(in==null||!in.isIntegralNumber()||in.asLong()<0||out!=null&&(!out.isIntegralNumber()||out.asLong()<0))return;
        try(var c=pool.getConnection();var s=c.prepareStatement("update ai_provider_budget.reservation set state='OBSERVED',input_tokens=?,output_tokens=? where id=?::uuid and state='UNKNOWN'")){
            s.setLong(1,in.asLong());s.setLong(2,out==null?0:out.asLong());s.setString(3,id);s.executeUpdate();
        }catch(Exception ignored){/* Reservation remains UNKNOWN, fully charged; never replay/refund. */}
    }
    @Override public void close(){pool.close();}
}
