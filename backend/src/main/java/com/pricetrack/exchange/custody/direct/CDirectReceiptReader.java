package com.pricetrack.exchange.custody.direct;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.*;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.Transfer;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.util.*;

/** Single-RPC inclusion and exact owned calldata/events only. Never writes projection or claims full audit/finality. */
public final class CDirectReceiptReader {
    public static final String SCOPE="SINGLE_RPC_RECEIPT_AND_CALLDATA_NOT_FULL_AUDIT_OR_FINALITY";
    private final CCustodyRpcReader reads;private final CReadRpc rpc;private final CCustodyIdentity id;
    public CDirectReceiptReader(CCustodyRpcReader reads,CReadRpc rpc){this.reads=Objects.requireNonNull(reads);this.rpc=Objects.requireNonNull(rpc);id=reads.identity();}
    public Map<String,Object> inspect(String owner,String txHash,CDirectModels.ExecutionView expected){
        try{return inspectChecked(owner,txHash,expected);}
        catch(CCustodyFailure safe){throw safe;}catch(RuntimeException invalid){throw fail();}
    }
    private Map<String,Object> inspectChecked(String owner,String txHash,CDirectModels.ExecutionView expected){
        owner=CCustodyIdentity.address(owner);txHash=CCustodyIdentity.hash(txHash);
        reads.config();Budget b=new Budget();
        JsonNode tx=b.get("eth_getTransactionByHash",txHash),receipt=b.get("eth_getTransactionReceipt",txHash);
        if(tx==null||tx.isNull()){if(receipt!=null&&!receipt.isNull())throw fail();return result("PENDING",txHash);}
        require(text(tx,"hash").equals(txHash)&&address(tx,"from").equals(owner)&&q(tx,"value").signum()==0);
        String target=address(tx,"to"),data=text(tx,"input");
        CAbiModels.Call call;
        if(target.equals(id.controller().address())){
            require(expected!=null&&expected.transaction().from().equals(owner)&&expected.transaction().to().equals(target)
                &&expected.transaction().data().equals(data)&&expected.transaction().chainId().equals(id.chainId().toString()));
            call=new CAbiCodec().decode(Target.CONTROLLER,data);require(call instanceof Direct);
            Direct direct=(Direct)call;
            require(expected.intentId().equals(direct.intent().operationId())&&direct.intent().owner().equals(owner)
                &&expected.intent().equals(stringFields(direct.intent()))&&expected.report().equals(stringFields(direct.report()))
                &&expected.priceSignature().equals(direct.priceSignature()));
            require(expected.reportDigest().equals(new CEip712Verifier().verifyReport(direct.report(),id.chainId(),id.rules().address(),id.oracle(),direct.priceSignature()).digest()));
        }else if(target.equals(id.token().address())){
            require(expected==null);call=new CAbiCodec().decode(Target.TOKEN,data);
            require(call instanceof Approve a&&a.spender().equals(id.vault().address())&&a.amount().signum()>0);
        }else if(target.equals(id.vault().address())){
            require(expected==null);call=new CAbiCodec().decode(Target.VAULT,data);
            require(call instanceof VaultAmount a&&(a.action()==VaultAction.DEPOSIT||a.action()==VaultAction.WITHDRAW)&&a.amount().signum()>0);
        }else throw fail();
        if(receipt==null||receipt.isNull())return result("PENDING",txHash);
        require(text(receipt,"transactionHash").equals(txHash)&&address(receipt,"from").equals(owner)&&address(receipt,"to").equals(target));
        String blockHash=CCustodyIdentity.hash(text(receipt,"blockHash"));BigInteger block=q(receipt,"blockNumber"),index=q(receipt,"transactionIndex");
        require(text(tx,"blockHash").equals(blockHash)&&q(tx,"blockNumber").equals(block)&&q(tx,"transactionIndex").equals(index));
        JsonNode header=b.get("eth_getBlockByNumber",hex(block),false);
        require(header!=null&&text(header,"hash").equals(blockHash)&&q(header,"number").equals(block));
        JsonNode hashes=header.path("transactions");require(hashes.isArray()&&index.bitLength()<31&&index.intValue()<hashes.size()&&txHash.equals(hashes.get(index.intValue()).asText()));
        BigInteger status=q(receipt,"status");require(status.equals(BigInteger.ZERO)||status.equals(BigInteger.ONE));
        var events=new ArrayList<Event>();BigInteger previous=BigInteger.valueOf(-1);
        JsonNode logs=receipt.path("logs");require(logs.isArray()&&logs.size()<=16);
        for(JsonNode log:logs){
            require(log.path("removed").isBoolean()&&!log.path("removed").asBoolean()
                &&text(log,"transactionHash").equals(txHash)&&text(log,"blockHash").equals(blockHash)
                &&q(log,"blockNumber").equals(block)&&q(log,"transactionIndex").equals(index));
            BigInteger li=q(log,"logIndex");require(li.compareTo(previous)>0);previous=li;
            String emitter=address(log,"address");EmitterKind kind=emitter.equals(id.rules().address())?EmitterKind.RULES:
                emitter.equals(id.controller().address())?EmitterKind.CONTROLLER:emitter.equals(id.vault().address())?EmitterKind.VAULT:
                emitter.equals(id.token().address())?EmitterKind.TOKEN:null;require(kind!=null);
            JsonNode topics=log.path("topics");require(topics.isArray()&&topics.size()>0&&topics.size()<=4);
            List<String> ts=new ArrayList<>();for(var topic:topics){require(topic.isTextual());ts.add(topic.asText());}
            events.add(new CAbiEventCodec().decode(kind,new CAbiEventModels.Log(emitter,ts,text(log,"data"))));
        }
        var out=result(status.signum()==0?"REVERTED":"CONFIRMED",txHash);
        out.put("blockNumber",block.toString());out.put("blockHash",blockHash);out.put("blockTimestamp",q(header,"timestamp").toString());
        if(status.signum()==0)require(events.isEmpty());else verifyEvents(owner,call,events,out);
        JsonNode again=b.get("eth_getBlockByNumber",hex(block),false);
        require(again!=null&&text(again,"hash").equals(blockHash)&&q(again,"number").equals(block));
        JsonNode againReceipt=b.get("eth_getTransactionReceipt",txHash);require(receipt.equals(againReceipt));reads.config();
        return Collections.unmodifiableMap(out);
    }
    private void verifyEvents(String owner,Call call,List<Event> es,Map<String,Object> out){
        if(call instanceof Approve a){require(es.equals(List.of(new Approval(owner,id.vault().address(),a.amount()))));out.put("kind","APPROVE");return;}
        if(call instanceof VaultAmount a){
            boolean deposit=a.action()==VaultAction.DEPOSIT;
            require(es.equals(List.of(new Transfer(deposit?owner:id.vault().address(),deposit?id.vault().address():owner,a.amount()),
                deposit?new Deposited(owner,a.amount()):new Withdrawn(owner,a.amount()))));out.put("kind",deposit?"DEPOSIT":"WITHDRAW");return;
        }
        Direct d=(Direct)call;Report r=d.report();Intent i=d.intent();
        require(es.size()>=4&&es.get(0) instanceof OperationApplied&&es.get(1).equals(new FuturesReportConsumed(r.operationId(),r.observedAt(),r.validUntil())));
        var mark=FuturesMath.mark(r.side().signum()==0?FuturesMath.Side.LONG:FuturesMath.Side.SHORT,r.quantity(),r.entryPriceE8(),r.collateralAfter(),r.priceE8());
        OperationApplied applied=(OperationApplied)es.get(0);boolean open=r.action().signum()==0;require(open||r.action().equals(BigInteger.valueOf(4)));
        require(applied.operationId().equals(r.operationId())&&applied.positionId().equals(r.positionId())&&applied.action().equals(r.action())
            &&applied.version().equals(r.expectedVersion().add(BigInteger.ONE))&&applied.priceE8().equals(r.priceE8())&&applied.collateral().equals(r.collateralAfter())
            &&applied.unrealized().equals(mark.unrealized())&&applied.realized().equals(open?BigInteger.ZERO:mark.realized())
            &&applied.badDebt().equals(open?BigInteger.ZERO:mark.badDebt())&&applied.callEpoch().equals(r.expectedCallEpoch()));
        if(open){require(applied.deadline().signum()==0&&es.size()==4&&es.get(2).equals(new CollateralLocked(r.operationId(),r.positionId(),owner,r.collateralAfter(),r.collateralAfter())));}
        else{
            int settlement=es.size()-2;require(es.size()==4||es.size()==5);require(es.get(settlement) instanceof PositionSettled);
            var settled=(PositionSettled)es.get(settlement);require(settled.operationId().equals(r.operationId())&&settled.positionId().equals(r.positionId())&&settled.owner().equals(owner)
                &&settled.collateral().equals(r.collateralAfter())&&settled.realizedPnl().equals(mark.realized())&&settled.badDebt().equals(mark.badDebt()));
            BigInteger positive=mark.realized().max(BigInteger.ZERO);require(settled.paidProfit().compareTo(positive)<=0);
            BigInteger unpaid=positive.subtract(settled.paidProfit());
            if(unpaid.signum()==0)require(es.size()==4&&settled.claimId().signum()==0);
            else require(es.size()==5&&settled.claimId().signum()>0&&es.get(2).equals(new ProfitClaimCreated(settled.claimId(),owner,r.positionId(),r.operationId(),unpaid)));
            out.put("realizedPnl",mark.realized().toString());out.put("badDebt",mark.badDebt().toString());out.put("paidProfit",settled.paidProfit().toString());out.put("unpaidProfit",unpaid.toString());
        }
        require(es.getLast().equals(new CUserIntentExecuted(r.operationId(),r.positionId(),owner,
            new CEip712Verifier().intentDigest(i,id.chainId(),id.controller().address()),new CEip712Verifier().reportDigest(r,id.chainId(),id.rules().address()),r.action(),i.nonce(),true)));
        out.put("kind",open?"OPEN":"CLOSE");out.put("positionId",r.positionId());out.put("operationId",r.operationId());out.put("verifiedEvents",Integer.toString(es.size()));
    }
    private static Map<String,String> stringFields(Object record){
        Map<String,String> out=new LinkedHashMap<>();try{for(var f:record.getClass().getRecordComponents())out.put(f.getName(),f.getAccessor().invoke(record).toString());}
        catch(Exception invalid){throw fail();}return out;
    }
    private final class Budget {final long began=System.nanoTime();int calls;
        JsonNode get(String method,Object...args){long left=15_000_000_000L-(System.nanoTime()-began);require(++calls<=8&&left>0);return rpc.request(method,List.of(args),Math.min(3000,Math.max(1,left/1_000_000)));}}
    private static LinkedHashMap<String,Object> result(String status,String tx){var m=new LinkedHashMap<String,Object>();m.put("status",status);m.put("txHash",tx);m.put("scope",SCOPE);m.put("completeCoverage",false);return m;}
    private static String text(JsonNode n,String name){require(n!=null&&n.path(name).isTextual());return n.path(name).asText();}
    private static String address(JsonNode n,String name){return CCustodyIdentity.address(text(n,name));}
    private static BigInteger q(JsonNode n,String name){String s=text(n,name);require(s.matches("0x(?:0|[1-9a-f][0-9a-f]{0,63})"));return new BigInteger(s.substring(2),16);}
    private static String hex(BigInteger n){return "0x"+n.toString(16);}
    private static void require(boolean ok){if(!ok)throw fail();}
    private static CCustodyFailure fail(){return new CCustodyFailure("C_DIRECT_RECEIPT_REVIEW_REQUIRED",409);}
}
