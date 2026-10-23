package com.pricetrack.exchange.futures.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.runtime.FuturesMarketGate.Mark;
import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V2 consent drafts contain NO price report/evidence. Reuses V1 wallet locks and financial ledger. */
public class AuthorizedV2Store {
    public record Metadata(FuturesChainIdentity chain,AuthorizedV2Service.Request request){}
    public record Draft(String id,long userId,String position,String operation,String key,UserIntentV2Codec.Intent intent,Metadata metadata,String state){
        @Override public String toString(){return "V2Draft[id="+id+",state="+state+"]";}
    }
    private final JdbcTemplate jdbc;private final FuturesLedger ledger;private final AuthorizedFuturesStore common;private final ObjectMapper json;
    public AuthorizedV2Store(DataSource ds,FuturesLedger ledger,AuthorizedFuturesStore common,ObjectMapper json){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(3);this.ledger=ledger;this.common=common;this.json=json;}
    public Draft draft(long uid,String id,boolean lock){return jdbc.query("SELECT * FROM futures_authorized_v2_drafts WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),(r,n)->new Draft(r.getString("id"),uid,r.getString("position_id"),r.getString("operation_id"),r.getString("request_key"),read(r.getString("intent_json"),UserIntentV2Codec.Intent.class),read(r.getString("metadata_json"),Metadata.class),r.getString("state")),id,uid).stream().findFirst().orElseThrow(()->new Failure("AUTHORIZED_V2_DRAFT_NOT_FOUND"));}
    public Optional<Draft> byKey(long uid,String key){return jdbc.queryForList("SELECT id FROM futures_authorized_v2_drafts WHERE user_id=? AND request_key=?",String.class,uid,FuturesLedger.fingerprint(key)).stream().findFirst().map(id->draft(uid,id,false));}
    @Transactional(timeout=5)
    public Draft create(long uid,String key,String pid,String op,UserIntentV2Codec.Intent i,Metadata meta,Instant now){
        ledger.lockUserIntent(uid);String current=jdbc.queryForObject("SELECT inflight_draft FROM futures_wallet_links WHERE user_id=? FOR UPDATE",String.class,uid);
        if(!common.wallet(uid).equals(i.owner()))throw new Failure("WALLET_OWNER_CONFLICT");
        var replay=byKey(uid,key);if(replay.isPresent()){if(!replay.get().metadata().equals(meta))throw new Failure("IDEMPOTENCY_CONFLICT");return replay.get();}
        common.ensureDraftAvailable(uid,current,now);UserIntentV2Codec.validate(meta.chain(),i);UserIntentV2Codec.time(i,now.getEpochSecond());
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO futures_authorized_v2_drafts VALUES(?,?,?,?,?,?,?,?,'DRAFT')",id,uid,pid,op,FuturesLedger.fingerprint(key),write(i),write(meta),Timestamp.from(Instant.ofEpochSecond(i.deadline().longValueExact())));
        jdbc.update("UPDATE futures_wallet_links SET inflight_draft=? WHERE user_id=?",id,uid);return draft(uid,id,false);
    }
    public Operation replay(Draft d,String key,String signature){if(!d.key().equals(FuturesLedger.fingerprint(key)))throw new Failure("IDEMPOTENCY_CONFLICT");var a=common.operation(d.operation());if(!Objects.equals(a.userSignature(),signature)||!Objects.equals(a.intentV2(),d.intent())||!a.chain().equals(d.metadata().chain()))throw new Failure("IDEMPOTENCY_CONFLICT");return ledger.operation(d.operation());}
    @Transactional(timeout=5)
    public Operation accept(long uid,String id,String key,String signature,FuturesReport report,String priceSignature,Mark mark,Instant now){
        ledger.lockUserIntent(uid);var d=draft(uid,id,true);
        if(d.state().equals("ACCEPTED"))return replay(d,key,signature);
        if(!d.state().equals("DRAFT"))throw new Failure("AUTHORIZED_DRAFT_NOT_ACCEPTABLE");
        if(!d.key().equals(FuturesLedger.fingerprint(key)))throw new Failure("IDEMPOTENCY_CONFLICT");
        UserIntentV2Codec.time(d.intent(),now.getEpochSecond());
        String current=jdbc.queryForObject("SELECT inflight_draft FROM futures_wallet_links WHERE user_id=? FOR UPDATE",String.class,uid);
        if(!id.equals(current)||!common.wallet(uid).equals(d.intent().owner()))throw new Failure("WALLET_OWNER_CONFLICT");
        var a=new Authorization(d.metadata().chain(),d.intent().owner(),null,signature,d.intent());UserIntentV2Codec.verifyReport(report,priceSignature,a);
        if(!report.priceE8().equals(mark.price())||!report.observedAt().equals(BigInteger.valueOf(mark.observedAt().getEpochSecond())))throw new Failure("AUTHORIZED_REPORT_CONFLICT");
        Operation op;
        if(report.action()==0){op=ledger.prepareAuthorizedOpen(uid,key,com.pricetrack.exchange.futures.math.FuturesMath.Side.values()[report.side()],report.collateralAfter(),report.leverage(),report.priceE8(),report.observedAt().longValueExact(),d.position(),d.operation(),report.accountRef());
            jdbc.update("INSERT INTO futures_authorized_positions VALUES(?,?,?)",d.position(),uid,write(new Authorization(a.chain(),a.owner(),null,null)));
            jdbc.update("INSERT INTO futures_authorized_v2_positions VALUES(?,?)",d.position(),a.chain().engine());
        }else{if(!common.position(d.position(),uid).chain().equals(a.chain()))throw new Failure("AUTHORIZED_DEPLOYMENT_CONFLICT");op=ledger.prepareAuthorized(uid,d.position(),key,Action.values()[report.action()],report.expectedVersion().longValueExact(),report.priceE8(),report.observedAt().longValueExact(),report.collateralAfter().subtract(report.collateralBefore()),d.operation());}
        if(!op.id().equals(d.operation())||!report.equals(FuturesReport.from(op,ledger.position(op.positionId(),uid),a.chain().reportExecutor())))throw new Failure("AUTHORIZED_REPORT_CONFLICT");
        jdbc.update("INSERT INTO futures_authorized_operations VALUES(?,?,?,?)",op.id(),write(a),write(report),priceSignature);
        jdbc.update("INSERT INTO futures_authorized_v2_operations VALUES(?,?)",op.id(),a.chain().engine());
        jdbc.update("INSERT INTO futures_v2_execution_revisions VALUES(?,0,?,?,?,?,?,?,CURRENT_TIMESTAMP)",op.id(),write(report),priceSignature,new BigDecimal(mark.price()),Timestamp.from(mark.observedAt()),mark.provider(),mark.generation());
        jdbc.update("INSERT INTO futures_v2_execution_heads VALUES(?,0)",op.id());
        jdbc.update("INSERT INTO futures_market_evidence VALUES(?,?,?,?,?)",op.id(),new BigDecimal(mark.price()),Timestamp.from(mark.observedAt()),mark.provider(),mark.generation());
        jdbc.update("UPDATE futures_authorized_v2_drafts SET state='ACCEPTED' WHERE id=?",id);return op;
    }
    @Transactional(timeout=5)
    public Operation automatic(long uid,String pid,String key,Action action,long version,Mark mark,FuturesChainIdentity c){
        if(!common.position(pid,uid).chain().equals(c))throw new Failure("AUTHORIZED_DEPLOYMENT_CONFLICT");
        var op=common.automatic(uid,pid,key,action,version,mark);
        if(jdbc.queryForObject("SELECT count(*) FROM futures_authorized_v2_operations WHERE operation_id=?",Long.class,op.id())==0)jdbc.update("INSERT INTO futures_authorized_v2_operations VALUES(?,?)",op.id(),c.engine());return op;
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new Failure("AUTHORIZATION_ENCODING_FAILED");}}
    private <T>T read(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new Failure("AUTHORIZATION_METADATA_INVALID");}}
}
