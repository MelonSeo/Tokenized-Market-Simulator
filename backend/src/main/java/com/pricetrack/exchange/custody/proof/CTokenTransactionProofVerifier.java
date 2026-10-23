package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CTokenTransactionProofModels.*;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventModels;
import com.pricetrack.exchange.custody.token.CTokenLedgerModels;
import com.pricetrack.exchange.custody.token.CTokenLedgerReducer;
import java.math.BigInteger;
import java.util.*;

/** Original signed token call, no Vault balance changes and no fabricated financial observation. */
public final class CTokenTransactionProofVerifier {
    private static final String ZERO="0x"+"0".repeat(40);
    private final CReceiptEnvelopeVerifier envelopes=new CReceiptEnvelopeVerifier();
    private final CTokenLedgerReducer tokens=new CTokenLedgerReducer(new CTokenLedgerReducer.Limits(2048,128,4096,4096));
    private static final class Stop extends RuntimeException {
        final Decision decision;final String code;
        Stop(Decision decision,String code){super(code,null,false,false);this.decision=decision;this.code=code;}
    }
    public Result verify(Input input){
        try{
            need(input!=null&&input.envelope()!=null,"INPUT");var e=envelopes.verify(input.envelope());
            if(e.decision()!=CReceiptEnvelopeModels.Decision.LINKED_IN_SCOPE)return new Result(Decision.valueOf(e.decision().name()),e.reason(),null);
            var linked=e.linked();var call=linked.call();
            if(!(call instanceof CAbiModels.Transfer)&&!(call instanceof CAbiModels.Approve)&&!(call instanceof CAbiModels.TransferFrom))stop(Decision.UNSUPPORTED,"CALL_SCOPE");
            Context c=input.context();need(c!=null&&c.point()!=null&&c.token()!=null&&c.parentBlockHash()!=null,"CONTEXT");
            need(c.token().deployment()!=null&&c.token().cursor()!=null&&c.token().lastTransactionIndex()!=null,"TOKEN_CONTEXT");
            need(c.token().minted(),"INITIAL_MINT_CONTEXT");
            var receipt=linked.receipt();var d=c.token().deployment();var ed=input.envelope().deployment();
            var point=new CAuthorizationModels.ObservationPoint(receipt.blockNumber(),receipt.blockHash(),receipt.transactionHash(),receipt.transactionIndex());need(point.equals(c.point()),"PRESTATE_POINT");
            check(d.chainId().equals(ed.chainId())&&d.token().equals(CAbiModels.address(ed.token())),"TOKEN_IDENTITY");
            check(linked.transaction().to().equals(d.token()),"TOKEN_TARGET");
            String vault=CAbiModels.address(ed.vault()),sender=linked.transaction().sender();
            BigInteger vaultBefore=c.token().balanceOf(vault);List<CTokenLedgerModels.Log> logs=List.of();boolean reverted=receipt.status()==0;
            if(!reverted){
                CAbiEventModels.Event expected;
                if(call instanceof CAbiModels.Approve approval){
                    check(!approval.spender().equals(ZERO),"ZERO_SPENDER");expected=new CAbiEventModels.Approval(sender,approval.spender(),approval.amount());
                }else{
                    String from,to;BigInteger amount;
                    if(call instanceof CAbiModels.Transfer transfer){from=sender;to=transfer.to();amount=transfer.amount();}
                    else{var transfer=(CAbiModels.TransferFrom)call;from=transfer.from();to=transfer.to();amount=transfer.amount();}
                    check(!from.equals(ZERO)&&!to.equals(ZERO),"ZERO_TRANSFER_ENDPOINT");
                    if(amount.signum()>0&&(from.equals(vault)||to.equals(vault)))stop(Decision.UNSUPPORTED,"VAULT_ASSET_DELTA");
                    if(call instanceof CAbiModels.TransferFrom)allowance(c,point,from,sender,amount);
                    expected=new CAbiEventModels.Transfer(from,to,amount);
                }
                check(linked.logs().size()==1,"EVENT_COUNT");var event=linked.logs().getFirst();
                check(event.emitter().equals(d.token())&&event.event().equals(expected),"TOKEN_EVENT");
                logs=List.of(new CTokenLedgerModels.Log(event.logIndex(),event.emitter(),event.event()));
            }
            var block=new CTokenLedgerModels.Block(receipt.blockNumber(),receipt.blockHash(),c.parentBlockHash());
            var next=tokens.apply(c.token(),new CTokenLedgerModels.Transaction(d,block,receipt.transactionHash(),receipt.transactionIndex(),
                reverted?CTokenLedgerModels.Outcome.REVERTED:CTokenLedgerModels.Outcome.SUCCESS,logs));
            check(vaultBefore.equals(next.balanceOf(vault)),"VAULT_ASSET_POST");
            return new Result(Decision.VERIFIED_IN_SCOPE,reverted?"C_TTX_REVERTED_NO_CAUSE_INFERENCE":"C_TTX_TOKEN_ZERO_VAULT_DELTA_ONLY",new Verified(next,vault,VaultAssetEffect.ZERO_DELTA,reverted));
        }catch(Stop stop){return new Result(stop.decision,"C_TTX_"+stop.code,null);}
        catch(IllegalArgumentException|ArithmeticException invalid){return new Result(Decision.REJECTED,"C_TTX_TOKEN_REPLAY",null);}
    }
    private void allowance(Context c,CAuthorizationModels.ObservationPoint point,String owner,String spender,BigInteger amount){
        var a=c.allowance();need(a!=null&&a.point()!=null&&a.owner()!=null&&a.spender()!=null&&a.value()!=null,"ALLOWANCE_MISSING");
        need(point.equals(a.point())&&owner.equals(CAbiModels.address(a.owner()))&&spender.equals(CAbiModels.address(a.spender())),"ALLOWANCE_POINT_OR_KEY");
        CAbiModels.uint(a.value());check(a.value().compareTo(amount)>=0,"ALLOWANCE_AMOUNT");
    }
    private static void need(boolean condition,String code){if(!condition)stop(Decision.INSUFFICIENT,code);}
    private static void check(boolean condition,String code){if(!condition)stop(Decision.REJECTED,code);}
    private static void stop(Decision decision,String code){throw new Stop(decision,code);}
}
