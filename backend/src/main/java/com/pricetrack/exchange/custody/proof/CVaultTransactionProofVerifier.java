package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CVaultTransactionProofModels.*;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventModels;
import com.pricetrack.exchange.custody.model.CCustodyModels;
import com.pricetrack.exchange.custody.projection.CCustodyReducer;
import com.pricetrack.exchange.custody.token.CTokenLedgerModels;
import com.pricetrack.exchange.custody.token.CTokenLedgerReducer;
import java.math.BigInteger;
import java.util.*;

/** Pure supported Vault call linkage. No external Proof, user/Oracle auth requirement or financial formula copy. */
public final class CVaultTransactionProofVerifier {
    private final CReceiptEnvelopeVerifier envelopes=new CReceiptEnvelopeVerifier();
    private final CCustodyReducer vaults=new CCustodyReducer(new CCustodyReducer.Limits(2048,128,4096,2048,2048));
    private final CTokenLedgerReducer tokens=new CTokenLedgerReducer(new CTokenLedgerReducer.Limits(2048,128,4096,4096));
    private static final class Stop extends RuntimeException {
        final Decision decision;final String code;
        Stop(Decision decision,String code){super(code,null,false,false);this.decision=decision;this.code=code;}
    }
    public Result verify(Input input){
        try {
            need(input!=null&&input.envelope()!=null,"INPUT");
            var envelope=envelopes.verify(input.envelope());
            if(envelope.decision()!=CReceiptEnvelopeModels.Decision.LINKED_IN_SCOPE)return new Result(Decision.valueOf(envelope.decision().name()),envelope.reason(),null);
            var linked=envelope.linked();var call=linked.call();
            if(!(call instanceof CAbiModels.VaultAmount)&&!(call instanceof CAbiModels.ProcessClaim))stop(Decision.UNSUPPORTED,"CALL_SCOPE");
            Context c=input.context();need(c!=null&&c.point()!=null&&c.vault()!=null&&c.token()!=null&&c.parentBlockHash()!=null,"CONTEXT");
            need(c.vault().deployment()!=null&&c.token().deployment()!=null&&c.vault().cursor()!=null&&c.token().cursor()!=null&&c.token().lastTransactionIndex()!=null,"STATE_CONTEXT");
            need(c.vault().totalFree()!=null&&c.vault().totalLocked()!=null&&c.vault().house()!=null&&c.vault().actualAssets()!=null
                &&c.vault().totalUnpaid()!=null&&c.vault().head()!=null&&c.vault().next()!=null,"STATE_FIELDS");
            var receipt=linked.receipt();var d=c.vault().deployment();var td=c.token().deployment();var ed=input.envelope().deployment();
            var point=new CAuthorizationModels.ObservationPoint(receipt.blockNumber(),receipt.blockHash(),receipt.transactionHash(),receipt.transactionIndex());
            need(point.equals(c.point()),"PRESTATE_POINT");
            check(d.chainId().equals(ed.chainId())&&d.token().equals(CAbiModels.address(ed.token()))&&d.vault().equals(CAbiModels.address(ed.vault()))
                &&d.controller().equals(CAbiModels.address(ed.controller()))&&d.rules().equals(CAbiModels.address(ed.rules())),"DEPLOYMENT");
            check(td.chainId().equals(d.chainId())&&td.token().equals(d.token())&&td.genesisHash().equals(d.genesisHash()),"TOKEN_IDENTITY");
            long blockNumber;int transactionIndex;
            try {
                blockNumber=receipt.blockNumber().longValueExact();transactionIndex=receipt.transactionIndex().intValueExact();
                need(c.vault().cursor().number()==c.token().cursor().number().longValueExact()
                    &&c.vault().cursor().hash().equals(c.token().cursor().hash())&&c.vault().lastTransactionIndex()==c.token().lastTransactionIndex().intValueExact(),"PROJECTION_POINT");
            }catch(ArithmeticException range){stop(Decision.UNSUPPORTED,"METADATA_RANGE");return null;}
            vaults.validateState(c.vault());check(c.token().balanceOf(d.vault()).equals(c.vault().actualAssets()),"ASSETS_PRE");
            var block=new CCustodyModels.Block(blockNumber,receipt.blockHash(),c.parentBlockHash());
            var tokenBlock=new CTokenLedgerModels.Block(receipt.blockNumber(),receipt.blockHash(),c.parentBlockHash());
            CCustodyModels.Call reducedCall;
            if(call instanceof CAbiModels.VaultAmount amount)reducedCall=new CCustodyModels.VaultCall(CCustodyModels.VaultAction.valueOf(amount.action().name()),linked.transaction().sender(),amount.amount());
            else reducedCall=new CCustodyModels.ProcessClaimCall();
            List<CCustodyModels.Log> reduced=new ArrayList<>();List<CTokenLedgerModels.Log> tokenLogs=new ArrayList<>();
            boolean reverted=receipt.status()==0;
            if(!reverted){
                if(call instanceof CAbiModels.VaultAmount amount){
                    if(amount.action()!=CAbiModels.VaultAction.WITHDRAW)allowance(c,point,linked.transaction().sender(),d.vault(),amount.amount());
                    check(linked.logs().size()==2,"CASH_EVENT_COUNT");
                    var first=linked.logs().getFirst();var last=linked.logs().getLast();
                    check(first.emitter().equals(d.token())&&first.event() instanceof CAbiEventModels.Transfer&&last.emitter().equals(d.vault()),"CASH_EVENT_EMITTER");
                    check(switch(amount.action()){
                        case DEPOSIT -> last.event() instanceof CAbiEventModels.Deposited;
                        case WITHDRAW -> last.event() instanceof CAbiEventModels.Withdrawn;
                        case FUND_HOUSE -> last.event() instanceof CAbiEventModels.HouseFunded;
                    },"CASH_EVENT_TYPE");
                }else {
                    check(linked.logs().size()<=1,"PROCESS_EVENT_COUNT");
                    if(!linked.logs().isEmpty())check(linked.logs().getFirst().emitter().equals(d.vault())&&linked.logs().getFirst().event() instanceof CAbiEventModels.ProfitClaimPaid,"PROCESS_EVENT_TYPE");
                }
                for(var log:linked.logs()){
                    int index;try{index=log.logIndex().intValueExact();}catch(ArithmeticException range){stop(Decision.UNSUPPORTED,"LOG_INDEX_RANGE");return null;}
                    reduced.add(new CCustodyModels.Log(index,log.emitter(),reduce(log.event())));
                    if(log.event() instanceof CAbiEventModels.Transfer transfer)tokenLogs.add(new CTokenLedgerModels.Log(log.logIndex(),log.emitter(),transfer));
                }
            }
            var bundle=new CCustodyModels.Bundle(d,block,receipt.transactionHash(),transactionIndex,receipt.from(),receipt.to(),
                reverted?CCustodyModels.Outcome.REVERTED:CCustodyModels.Outcome.SUCCESS,reducedCall,reduced);
            var newVault=vaults.apply(c.vault(),bundle);
            var newToken=tokens.apply(c.token(),new CTokenLedgerModels.Transaction(td,tokenBlock,receipt.transactionHash(),receipt.transactionIndex(),
                reverted?CTokenLedgerModels.Outcome.REVERTED:CTokenLedgerModels.Outcome.SUCCESS,tokenLogs));
            check(newToken.balanceOf(d.vault()).equals(newVault.actualAssets()),"ASSETS_POST");
            return new Result(Decision.VERIFIED_IN_SCOPE,reverted?"C_VTX_REVERTED_NO_CAUSE_INFERENCE":"C_VTX_CASH_OR_FIFO_CONTEXT_ONLY",new Verified(newVault,newToken,reverted));
        }catch(Stop stop){return new Result(stop.decision,"C_VTX_"+stop.code,null);}
        catch(IllegalArgumentException|ArithmeticException invalid){return new Result(Decision.REJECTED,"C_VTX_ACCOUNTING_OR_TOKEN",null);}
    }
    private void allowance(Context c,CAuthorizationModels.ObservationPoint point,String owner,String vault,BigInteger amount){
        var a=c.allowance();need(a!=null&&a.point()!=null&&a.owner()!=null&&a.spender()!=null&&a.value()!=null,"ALLOWANCE_MISSING");
        need(point.equals(a.point())&&owner.equals(CAbiModels.address(a.owner()))&&vault.equals(CAbiModels.address(a.spender())),"ALLOWANCE_POINT_OR_KEY");
        CAbiModels.uint(a.value());check(a.value().compareTo(amount)>=0,"ALLOWANCE_AMOUNT");
    }
    private CCustodyModels.Event reduce(CAbiEventModels.Event event){
        if(event instanceof CAbiEventModels.Transfer e)return new CCustodyModels.Transfer(e.from(),e.to(),e.value());
        if(event instanceof CAbiEventModels.Deposited e)return new CCustodyModels.Deposited(e.owner(),e.amount());
        if(event instanceof CAbiEventModels.Withdrawn e)return new CCustodyModels.Withdrawn(e.owner(),e.amount());
        if(event instanceof CAbiEventModels.HouseFunded e)return new CCustodyModels.HouseFunded(e.contributor(),e.amount());
        if(event instanceof CAbiEventModels.ProfitClaimPaid e)return new CCustodyModels.ClaimPaid(e.claimId(),e.owner(),e.paidAmount(),e.remainingAmount());
        stop(Decision.REJECTED,"UNEXPECTED_EVENT");return null;
    }
    private static void need(boolean condition,String code){if(!condition)stop(Decision.INSUFFICIENT,code);}
    private static void check(boolean condition,String code){if(!condition)stop(Decision.REJECTED,code);}
    private static void stop(Decision decision,String code){throw new Stop(decision,code);}
}
