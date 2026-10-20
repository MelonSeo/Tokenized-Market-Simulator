package com.pricetrack.exchange.ai.evaluation;

import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.*;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared positive fixtures: real dispatcher/handlers, mocked read facade, no DB/RPC. */
final class EvaluationToolFixture {
    final ObjectMapper json;
    final AuthenticatedUser user=new AuthenticatedUser(910001L,null,UserRole.USER),admin=new AuthenticatedUser(910002L,null,UserRole.ADMIN);
    final String quote="0x"+"aa".repeat(32),pid;
    final AtomicBoolean unverified=new AtomicBoolean();
    final ToolReadFacade reads=mock(ToolReadFacade.class);
    final ReceiptReader receipts=mock(ReceiptReader.class);
    final FuturesToolReader futures=mock(FuturesToolReader.class);
    ObjectNode node(Object... fields){var n=json.createObjectNode();for(int i=0;i<fields.length;i+=2)n.set(fields[i].toString(),json.valueToTree(fields[i+1]));return n;}
    void own(ToolContext ctx){ctx.requireAuthenticated();if(!ctx.admin()&&!Objects.equals(ctx.userId(),user.userId()))throw new ToolFailure("RESOURCE_NOT_FOUND");}
    EvaluationToolFixture(ObjectMapper json,ObjectNode position,ObjectNode evidence)throws Exception {
        this.json=json;pid=evidence.path("positionId").asText();
        if(!evidence.path("verification").asText().equals("VERIFIED")||!pid.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("FIXTURE_EVIDENCE_INVALID");
        var order=node("orderId",1,"symbol","mSEC","side","BUY","status","PENDING_ONCHAIN","quoteId",quote,"trade",null,
            "inputAmount","75000","inputSymbol","mKRW","outputSymbol","mSEC");
        var q=order.deepCopy();q.remove("status");q.put("storedStatus","CONSUMED").put("expiredByTime",true).put("orderLink","LINKED").put("price","75000").put("fee","75");
        when(reads.order(any(),eq(1L))).thenAnswer(c->{own(c.getArgument(0));return order.deepCopy();});
        when(reads.quote(any(),eq(quote))).thenAnswer(c->{own(c.getArgument(0));return q.deepCopy();});
        when(reads.transaction(any(),eq(1L))).thenAnswer(c->{own(c.getArgument(0));return node("orderId",1,"linkStatus","LINKED","databaseStatus","REVIEW_REQUIRED","type","BUY");});
        when(reads.receiptInput(any(),eq(1L))).thenAnswer(c->{own(c.getArgument(0));return new ToolReadFacade.ReceiptInput(1L,null,null,null,null,"REVIEW_REQUIRED",Instant.now());});
        when(receipts.read(any())).thenReturn(node("receiptStatus","NOT_FOUND","databaseStatus","REVIEW_REQUIRED","verification","INCONCLUSIVE"));
        when(reads.market(any(),anyBoolean())).thenAnswer(c->{var n=node("symbol","mSEC","provider","SIMULATED","marketStatus","OPEN","priceStatus","LIVE","observedAt",Instant.now().toString());if(c.getArgument(1,Boolean.class))n.put("price","75000").put("priceKind","MARKET_REFERENCE_NOT_EXECUTION_PRICE");return n;});
        when(reads.portfolio(any())).thenAnswer(c->{own(c.getArgument(0));var n=node("totalValue","250000","tokenValue","150000","unrealizedProfit","10000","valuationSymbol","mKRW");n.putArray("balances").add(node("symbol","mKRW","total","100000","locked","75000","available","25000")).add(node("symbol","mSEC","total","2","locked","0","available","2"));return n;});
        when(reads.abnormal(any(),any())).thenAnswer(c->{c.getArgument(0,ToolContext.class).requireAdmin();var n=node("filter","REVIEW_REQUIRED","nextCursor",null);n.putArray("items").add(node("databaseStatus","REVIEW_REQUIRED","order",order));return n;});
        when(futures.position(any(),eq(pid))).thenAnswer(c->{own(c.getArgument(0));return position.deepCopy();});
        when(futures.evidence(any(),eq(pid))).thenAnswer(c->{own(c.getArgument(0));var n=evidence.deepCopy();if(unverified.get())n.put("verification","INCONCLUSIVE").put("pending",true);return n;});
    }
    static EvaluationToolFixture preserved(ObjectMapper json,JsonNode capture)throws Exception {
        ObjectNode position=null;
        for(var tool:capture.path("answer").path("toolEvidence"))if(tool.path("tool").asText().equals("getFuturesPosition"))position=(ObjectNode)tool.path("data").deepCopy();
        if(position==null)throw new IllegalArgumentException("PRESERVED_POSITION_MISSING");
        var evidence=(ObjectNode)capture.path("liquidation").deepCopy();
        evidence.put("captureScope","PRESERVED_E2E_SNAPSHOT_NOT_FRESH_RPC").put("freshChainVerification",false);
        position.put("captureScope","PRESERVED_E2E_SNAPSHOT_NOT_FRESH_RPC");
        return new EvaluationToolFixture(json,position,evidence);
    }
    static EvaluationToolFixture offline(ObjectMapper json)throws Exception {
        String id="11111111-1111-1111-1111-111111111111";
        var p=json.createObjectNode().put("id",id).put("status","LIQUIDATED").put("collateral","100000.000000000000000000")
            .put("riskStatus","GRACE").put("version",3).put("callEpoch",1).put("deadline",100).putNull("pendingOperation")
            .put("realized","-100000.000000000000000000").put("badDebt","0.000000000000000000");
        var pos=json.createObjectNode();pos.set("position",p);
        var evidence=json.createObjectNode().put("positionId",id).put("verification","VERIFIED");evidence.set("positionSnapshot",p.deepCopy());
        evidence.putArray("operations").addObject().put("action","LIQUIDATE").put("verification","VERIFIED");
        return new EvaluationToolFixture(json,pos,evidence);
    }
    ToolDispatcher dispatcher(){return new ToolDispatcher(new ToolProperties(true),new ToolRegistry(json),reads,receipts,new ToolAudit(),json).futures(()->futures);}
}
