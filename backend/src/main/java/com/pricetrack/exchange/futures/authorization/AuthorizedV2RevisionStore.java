package com.pricetrack.exchange.futures.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.runtime.FuturesMarketGate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** One atomic append + financial revision. Never edits original approval or revision zero. */
public class AuthorizedV2RevisionStore {
    private final JdbcTemplate jdbc;private final FuturesLedger ledger;private final AuthorizedFuturesStore common;
    private final ObjectMapper json;private final FuturesMarketGate market;
    public AuthorizedV2RevisionStore(DataSource ds,FuturesLedger ledger,AuthorizedFuturesStore common,ObjectMapper json,FuturesMarketGate market){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(3);this.ledger=ledger;this.common=common;this.json=json;this.market=market;
    }
    @Transactional(timeout=5)
    public void replaceUnsigned(String id,FuturesReport old,FuturesReport next,String priceSignature,FuturesMarketGate.Mark mark){
        var a=common.operation(id);
        if(!a.chain().v2() || a.intentV2()==null || !UserIntentCodec.userAction(next.action()))throw new Failure("REVISION_NOT_ALLOWED");
        UserIntentV2Codec.verifyConsent(a.chain(),a.intentV2(),a.userSignature());
        UserIntentV2Codec.verifyReport(next,priceSignature,a);
        if(!next.priceE8().equals(mark.price()) || next.observedAt().longValueExact()!=mark.observedAt().getEpochSecond())throw new Failure("AUTHORIZED_REPORT_CONFLICT");
        var op=ledger.reviseAuthorizedV2(id,old,next); // locks user/position/op/head, checks count=0 and no signed row
        if(!common.wallet(op.userId()).equals(a.owner()))throw new Failure("WALLET_OWNER_CONFLICT");
        var original=jdbc.queryForMap("SELECT provider,generation FROM futures_v2_execution_revisions WHERE operation_id=? AND revision=0",id);
        if(!mark.provider().equalsIgnoreCase((String)original.get("provider")) || !java.util.Objects.equals(mark.generation(),original.get("generation")))throw new Failure("MARKET_CONNECTION_CHANGED");
        UserIntentV2Codec.time(a.intentV2(),market.now().getEpochSecond());market.validate(mark);
        try{jdbc.update("INSERT INTO futures_v2_execution_revisions VALUES(?,1,?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,json.writeValueAsString(next),priceSignature,new BigDecimal(mark.price()),Timestamp.from(mark.observedAt()),mark.provider(),mark.generation());}
        catch(com.fasterxml.jackson.core.JsonProcessingException x){throw new Failure("AUTHORIZATION_ENCODING_FAILED");}
        if(jdbc.update("UPDATE futures_v2_execution_heads SET active_revision=1 WHERE operation_id=? AND active_revision=0",id)!=1)throw new Failure("REVISION_CONFLICT");
    }
}
