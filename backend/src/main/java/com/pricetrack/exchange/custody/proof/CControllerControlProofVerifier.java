package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CControllerControlProofModels.*;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventModels;
import com.pricetrack.exchange.custody.model.CCustodyModels;
import com.pricetrack.exchange.custody.projection.CCustodyReducer;
import com.pricetrack.exchange.custody.token.CTokenLedgerModels;
import com.pricetrack.exchange.custody.token.CTokenLedgerReducer;
import java.math.BigInteger;
import java.util.*;

/** Cancel/pause only; no external Proof, execution, financial calculation or implicit code/nonce assumption. */
public final class CControllerControlProofVerifier {
    private final CReceiptEnvelopeVerifier envelopes=new CReceiptEnvelopeVerifier();
    private final CCustodyReducer vaults=new CCustodyReducer(new CCustodyReducer.Limits(2048,128,4096,2048,2048));
    private final CTokenLedgerReducer tokens=new CTokenLedgerReducer(new CTokenLedgerReducer.Limits(2048,128,4096,4096));
    private static final class Stop extends RuntimeException {
        final Decision decision;final String code;
        Stop(Decision decision,String code){super(code,null,false,false);this.decision=decision;this.code=code;}
    }
    public Result verify(Input input){
        try{
            need(input!=null&&input.envelope()!=null,"INPUT");var envelope=envelopes.verify(input.envelope());
            if(envelope.decision()!=CReceiptEnvelopeModels.Decision.LINKED_IN_SCOPE)return new Result(Decision.valueOf(envelope.decision().name()),envelope.reason(),null);
            var linked=envelope.linked();var call=linked.call();
            if(!(call instanceof CAbiModels.Cancel)&&!(call instanceof CAbiModels.Pause))stop(Decision.UNSUPPORTED,"CALL_SCOPE");
            Context c=input.context();need(c!=null&&c.point()!=null&&c.vault()!=null&&c.token()!=null&&c.parentBlockHash()!=null,"CONTEXT");
            need(c.vault().deployment()!=null&&c.token().deployment()!=null&&c.vault().cursor()!=null&&c.token().cursor()!=null&&c.token().lastTransactionIndex()!=null,"STATE_CONTEXT");
            need(c.vault().totalFree()!=null&&c.vault().totalLocked()!=null&&c.vault().house()!=null&&c.vault().actualAssets()!=null
                &&c.vault().totalUnpaid()!=null&&c.vault().head()!=null&&c.vault().next()!=null,"STATE_FIELDS");
            var receipt=linked.receipt();var d=c.vault().deployment();var td=c.token().deployment();var ed=input.envelope().deployment();
            var point=new CAuthorizationModels.ObservationPoint(receipt.blockNumber(),receipt.blockHash(),receipt.transactionHash(),receipt.transactionIndex());need(point.equals(c.point()),"PRESTATE_POINT");
            check(d.chainId().equals(ed.chainId())&&d.token().equals(CAbiModels.address(ed.token()))&&d.vault().equals(CAbiModels.address(ed.vault()))
                &&d.controller().equals(CAbiModels.address(ed.controller()))&&d.rules().equals(CAbiModels.address(ed.rules())),"DEPLOYMENT");
            check(td.chainId().equals(d.chainId())&&td.token().equals(d.token())&&td.genesisHash().equals(d.genesisHash()),"TOKEN_IDENTITY");
            long number;int transactionIndex;
            try{number=receipt.blockNumber().longValueExact();transactionIndex=receipt.transactionIndex().intValueExact();
                need(c.vault().cursor().number()==c.token().cursor().number().longValueExact()&&c.vault().cursor().hash().equals(c.token().cursor().hash())
                    &&c.vault().lastTransactionIndex()==c.token().lastTransactionIndex().intValueExact(),"PROJECTION_POINT");
            }catch(ArithmeticException range){stop(Decision.UNSUPPORTED,"METADATA_RANGE");return null;}
            vaults.validateState(c.vault());check(c.token().balanceOf(d.vault()).equals(c.vault().actualAssets()),"ASSETS_PRE");
            String sender=linked.transaction().sender();CCustodyModels.Call reducedCall=call instanceof CAbiModels.Cancel n?new CCustodyModels.CancelCall(sender,n.nonce()):new CCustodyModels.PauseCall(((CAbiModels.Pause)call).paused());
            boolean reverted=receipt.status()==0;List<CCustodyModels.Log> logs=List.of();
            if(!reverted){
                CAbiEventModels.Event expected;CCustodyModels.Event reduced;
                if(call instanceof CAbiModels.Cancel n){
                    cancel(c,point,sender,n.nonce());expected=new CAbiEventModels.CNonceCancelled(sender,n.nonce());reduced=new CCustodyModels.NonceCancelled(sender,n.nonce());
                }else{var p=(CAbiModels.Pause)call;check(sender.equals(d.pauseAdmin()),"PAUSE_ADMIN");expected=new CAbiEventModels.NewPositionsPauseChanged(p.paused());reduced=new CCustodyModels.PauseChanged(p.paused());}
                check(linked.logs().size()==1,"EVENT_COUNT");var event=linked.logs().getFirst();
                check(event.emitter().equals(d.controller())&&event.event().equals(expected),"CONTROL_EVENT");
                int index;try{index=event.logIndex().intValueExact();}catch(ArithmeticException range){stop(Decision.UNSUPPORTED,"LOG_INDEX_RANGE");return null;}
                logs=List.of(new CCustodyModels.Log(index,event.emitter(),reduced));
            }
            var block=new CCustodyModels.Block(number,receipt.blockHash(),c.parentBlockHash());
            var bundle=new CCustodyModels.Bundle(d,block,receipt.transactionHash(),transactionIndex,receipt.from(),receipt.to(),reverted?CCustodyModels.Outcome.REVERTED:CCustodyModels.Outcome.SUCCESS,reducedCall,logs);
            var v=vaults.apply(c.vault(),bundle);
            var t=tokens.apply(c.token(),new CTokenLedgerModels.Transaction(td,new CTokenLedgerModels.Block(receipt.blockNumber(),receipt.blockHash(),c.parentBlockHash()),receipt.transactionHash(),receipt.transactionIndex(),reverted?CTokenLedgerModels.Outcome.REVERTED:CTokenLedgerModels.Outcome.SUCCESS,List.of()));
            check(t.balanceOf(d.vault()).equals(v.actualAssets()),"ASSETS_POST");
            return new Result(Decision.VERIFIED_IN_SCOPE,reverted?"C_CTRL_REVERTED_NO_CAUSE_INFERENCE":"C_CTRL_CANCEL_OR_PAUSE_CONTEXT_ONLY",new Verified(v,t,reverted));
        }catch(Stop stop){return new Result(stop.decision,"C_CTRL_"+stop.code,null);}
        catch(IllegalArgumentException|ArithmeticException invalid){return new Result(Decision.REJECTED,"C_CTRL_ACCOUNTING_OR_TOKEN",null);}
    }
    private void cancel(Context c,CAuthorizationModels.ObservationPoint point,String owner,BigInteger expected){
        var nonce=c.nonce();var code=c.ownerCode();
        need(nonce!=null&&nonce.point()!=null&&nonce.owner()!=null&&nonce.nonce()!=null,"NONCE_MISSING");
        need(point.equals(nonce.point())&&owner.equals(CAbiModels.address(nonce.owner())),"NONCE_POINT_OR_KEY");CAbiModels.uint(nonce.nonce());
        check(expected.equals(nonce.nonce())&&expected.equals(c.vault().nonces().getOrDefault(owner,BigInteger.ZERO)),"NONCE_VALUE");
        check(expected.compareTo(CAbiModels.UINT_MAX)<0,"NONCE_OVERFLOW");
        need(code!=null&&code.point()!=null&&code.ownerKey()!=null&&code.code()!=null,"CODE_MISSING");
        need(point.equals(code.point())&&owner.equals(CAbiModels.address(code.ownerKey())),"CODE_POINT_OR_KEY");
        check(code.code().equals("0x"),"OWNER_HAS_CODE_OR_MALFORMED");
    }
    private static void need(boolean condition,String code){if(!condition)stop(Decision.INSUFFICIENT,code);}
    private static void check(boolean condition,String code){if(!condition)stop(Decision.REJECTED,code);}
    private static void stop(Decision decision,String code){throw new Stop(decision,code);}
}
