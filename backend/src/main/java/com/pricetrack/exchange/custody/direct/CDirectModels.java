package com.pricetrack.exchange.custody.direct;

import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.authorization.CUserIntentTypedData;
import com.pricetrack.exchange.custody.runtime.CCustodyIdentity;
import com.pricetrack.exchange.futures.runtime.FuturesMarketGate;
import java.util.*;

/** API numbers are exact decimal strings, never JavaScript Number-sized JSON integers. */
public final class CDirectModels {
    private CDirectModels(){}
    public record Request(String action,String positionId,String side,String leverage,String collateralAfterE18,
        String priceMinE8,String priceMaxE8,String quantityMinE18,String quantityMaxE18) {}
    public record DraftView(String intentId,Map<String,String> intent,Map<String,Object> typedData,String scope) {}
    public record Transaction(String from,String to,String chainId,String value,String data) {}
    public record Market(String priceE8,String observedAt,String provider,String generation) {}
    public record ExecutionView(String intentId,Map<String,String> intent,Map<String,String> report,String priceSignature,
        String reportDigest,Transaction transaction,Market market,String scope) {}
    public record Execution(Intent intent,Report report,String priceSignature,String reportDigest,FuturesMarketGate.Mark market) {
        @Override public String toString(){return "CDirectExecution[operationId="+intent.operationId()+"]";}
    }
    @SuppressWarnings("unchecked") public static DraftView draft(CCustodyIdentity c,Intent i){
        var typed=CUserIntentTypedData.format(i,c.chainId(),c.controller().address());
        return new DraftView(i.operationId(),(Map<String,String>)typed.get("message"),typed,"OWNER_DIRECT_DRAFT_NOT_USER_SIGNED_OR_EXECUTABLE");
    }
    public static ExecutionView execution(CCustodyIdentity c,Execution e,String calldata){
        var r=e.report();Object[] values={r.operationId(),r.positionId(),r.accountRef(),r.symbolHash(),r.action(),r.expectedVersion(),r.expectedCallEpoch(),r.side(),r.leverage(),r.quantity(),r.entryPriceE8(),r.collateralBefore(),r.collateralAfter(),r.priceE8(),r.observedAt(),r.validUntil(),r.policyHash(),r.executor()};
        String[] names={"operationId","positionId","accountRef","symbolHash","action","expectedVersion","expectedCallEpoch","side","leverage","quantity","entryPriceE8","collateralBefore","collateralAfter","priceE8","observedAt","validUntil","policyHash","executor"};
        var report=new LinkedHashMap<String,String>();for(int n=0;n<names.length;n++)report.put(names[n],values[n].toString());
        var m=e.market();return new ExecutionView(e.intent().operationId(),draft(c,e.intent()).intent(),Collections.unmodifiableMap(report),e.priceSignature(),e.reportDigest(),
            new Transaction(e.intent().owner(),c.controller().address(),c.chainId().toString(),"0x0",calldata),
            new Market(m.price().toString(),m.observedAt().toString(),m.provider(),m.generation()==null?null:m.generation().toString()),
            "OWNER_DIRECT_TRANSACTION_REQUIRES_USER_WALLET_APPROVAL_NOT_CANONICAL_CONFIRMATION");
    }
}
