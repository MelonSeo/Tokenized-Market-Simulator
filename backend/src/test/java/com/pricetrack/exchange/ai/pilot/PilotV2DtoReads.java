package com.pricetrack.exchange.ai.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.*;
import com.pricetrack.exchange.market.MarketPriceService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** Original D1/B5 controlled DTOs, NEVER a DB mutation, canonical chain failure or auth bypass. */
public class PilotV2DtoReads extends ToolReadFacade {
    private final Supplier<PilotV2Channel> channel;
    private final Map<ReceiptInput,String> receiptModes=Collections.synchronizedMap(new IdentityHashMap<>());
    public PilotV2DtoReads(EntityManager em,MarketPriceService market,ObjectMapper json,ToolProperties properties,Supplier<PilotV2Channel> channel){
        super(em,market,json,properties,Clock.systemUTC());this.channel=channel;
    }
    private String mode(ToolContext ctx,String name){
        if(ctx.agentRunId()==null)return "NONE";
        long start=ReceiptProbeTrace.ENABLED?ReceiptProbeTrace.begin("DTO_PRIVATE_CONTROL"):0;String result="ERROR";
        try{String value=channel.get().control(Map.of("op","dto","actor",ctx.userId(),"serverRequest",ctx.agentRunId(),"tool",name)).path("mode").asText();result="OK";return value;}
        finally{if(ReceiptProbeTrace.ENABLED)ReceiptProbeTrace.end("DTO_PRIVATE_CONTROL",start,result);}
    }
    @Override public ObjectNode quote(ToolContext ctx,String id){
        var result=super.quote(ctx,id); // original authorized resource read FIRST
        return mode(ctx,"getQuote").equals("CONTROLLED_LINK_DTO")?result.deepCopy().put("orderLink","INCONSISTENT_LINK").putNull("orderId")
            .put("captureScope","CONTROLLED_LINK_DTO_NOT_DATABASE_CORRUPTION"):result;
    }
    @Override public ReceiptInput receiptInput(ToolContext ctx,long order){
        long start=ReceiptProbeTrace.ENABLED?ReceiptProbeTrace.begin("DB_AND_INPUT_DTO"):0;String outcome="ERROR";
        try{
        var input=super.receiptInput(ctx,order);String mode=mode(ctx,"getReceiptSummary");
        if(!mode.equals("NONE"))receiptModes.put(input,mode);outcome="OK";return input;
        }finally{if(ReceiptProbeTrace.ENABLED)ReceiptProbeTrace.end("DB_AND_INPUT_DTO",start,outcome);}
    }
    public ReceiptReader receiptReader(ReadOnlyReceiptClient actual){
        return input->{try{
            var result=actual.read(input);return "CONTROLLED_RECEIPT_DTO".equals(receiptModes.get(input))?
                result.deepCopy().put("eventValidation","MISMATCH").put("captureScope","CONTROLLED_EVENT_MISMATCH_ADAPTER_NOT_ACTUAL_CHAIN_MISMATCH"):result;
        }finally{receiptModes.remove(input);}};
    }
}
