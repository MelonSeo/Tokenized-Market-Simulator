package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CTransactionProofModels.*;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventModels;
import com.pricetrack.exchange.custody.model.CCustodyModels;
import com.pricetrack.exchange.custody.projection.CCustodyReducer;
import com.pricetrack.exchange.custody.token.CTokenLedgerModels;
import com.pricetrack.exchange.custody.token.CTokenLedgerReducer;
import java.math.BigInteger;
import java.util.*;

/** Supported user OPEN/TOP_UP/CLOSE and keeper START/CLEAR/LIQUIDATE.
 * No external Proof input, services, writes or financial algorithms. */
public final class CTransactionProofVerifier {
    private static final String ZERO_ADDRESS="0x"+"0".repeat(40);
    private final CReceiptEnvelopeVerifier envelopes=new CReceiptEnvelopeVerifier();
    private final CAuthorizationVerifier authorizations=new CAuthorizationVerifier();
    private final CRulesTransitionVerifier rules=new CRulesTransitionVerifier();
    private final CEip712Verifier crypto=new CEip712Verifier();
    private final CCustodyReducer vaults=new CCustodyReducer(new CCustodyReducer.Limits(2048,128,4096,2048,2048));
    private final CTokenLedgerReducer tokens=new CTokenLedgerReducer(new CTokenLedgerReducer.Limits(2048,128,4096,4096));
    private static final class Stop extends RuntimeException {
        final Decision decision;final String code;
        Stop(Decision d,String c){super(c,null,false,false);decision=d;code=c;}
    }
    public Result verify(Input input) {
        try {
            need(input!=null&&input.envelope()!=null,"INPUT");
            var e=envelopes.verify(input.envelope());
            if(e.decision()!=CReceiptEnvelopeModels.Decision.LINKED_IN_SCOPE)
                return new Result(Decision.valueOf(e.decision().name()),e.reason(),null);
            var linked=e.linked();var call=linked.call();
            if(!(call instanceof CAbiModels.Direct)&&!(call instanceof CAbiModels.Relayed)&&!(call instanceof CAbiModels.Risk))stop(Decision.UNSUPPORTED,"CALL_SCOPE");
            CAbiModels.Report report;CAbiModels.Intent intent;String userSignature,priceSignature;boolean direct;
            if(call instanceof CAbiModels.Direct d){report=d.report();intent=d.intent();priceSignature=d.priceSignature();userSignature=null;direct=true;}
            else if(call instanceof CAbiModels.Relayed r){report=r.report();intent=r.intent();priceSignature=r.priceSignature();userSignature=r.userSignature();direct=false;}
            else {var r=(CAbiModels.Risk)call;report=r.report();intent=null;priceSignature=r.priceSignature();userSignature=null;direct=false;}
            if(!(call instanceof CAbiModels.Risk)&&!Set.of(BigInteger.ZERO,BigInteger.ONE,BigInteger.valueOf(4)).contains(report.action()))stop(Decision.UNSUPPORTED,"ACTION_SCOPE");
            Context c=input.context();need(c!=null&&c.point()!=null&&c.symbolHash()!=null&&c.vault()!=null&&c.token()!=null
                &&c.parentBlockHash()!=null,"CONTEXT");
            need(c.vault().deployment()!=null&&c.token().deployment()!=null,"DEPLOYMENT_CONTEXT");
            need(c.vault().totalFree()!=null&&c.vault().totalLocked()!=null&&c.vault().house()!=null&&c.vault().actualAssets()!=null
                &&c.vault().totalUnpaid()!=null&&c.vault().head()!=null&&c.vault().next()!=null,"VAULT_STATE_FIELDS");
            var receipt=linked.receipt();var d=c.vault().deployment();var td=c.token().deployment();var ed=input.envelope().deployment();
            var expectedPoint=new CAuthorizationModels.ObservationPoint(receipt.blockNumber(),receipt.blockHash(),receipt.transactionHash(),receipt.transactionIndex());
            need(expectedPoint.equals(c.point()),"PRESTATE_POINT");
            check(d.chainId().equals(ed.chainId())&&d.token().equals(CAbiModels.address(ed.token()))&&d.vault().equals(CAbiModels.address(ed.vault()))
                &&d.controller().equals(CAbiModels.address(ed.controller()))&&d.rules().equals(CAbiModels.address(ed.rules())),"DEPLOYMENT");
            check(td.chainId().equals(d.chainId())&&td.token().equals(d.token())&&td.genesisHash().equals(d.genesisHash()),"TOKEN_IDENTITY");
            check(!d.relayer().equals(d.pauseAdmin())&&!d.oracleSigner().equals(d.pauseAdmin()),"ROLE_IDENTITY");
            long blockNumber;int txIndex;
            try {blockNumber=receipt.blockNumber().longValueExact();txIndex=receipt.transactionIndex().intValueExact();}
            catch(ArithmeticException unsupported){stop(Decision.UNSUPPORTED,"METADATA_RANGE");return null;}
            var block=new CCustodyModels.Block(blockNumber,receipt.blockHash(),c.parentBlockHash());
            var tokenBlock=new CTokenLedgerModels.Block(receipt.blockNumber(),receipt.blockHash(),c.parentBlockHash());
            need(c.vault().cursor()!=null&&c.token().cursor()!=null&&c.token().lastTransactionIndex()!=null,"PROJECTION_CURSOR");
            try {
                need(c.vault().cursor().number()==c.token().cursor().number().longValueExact()
                    &&c.vault().cursor().hash().equals(c.token().cursor().hash())
                    &&c.vault().lastTransactionIndex()==c.token().lastTransactionIndex().intValueExact(),"PROJECTION_POINT");
            } catch(ArithmeticException unsupported){stop(Decision.UNSUPPORTED,"CURSOR_RANGE");}
            vaults.validateState(c.vault());check(c.token().balanceOf(d.vault()).equals(c.vault().actualAssets()),"ASSETS_PRE");
            if(call instanceof CAbiModels.Risk risk)return verifyRisk(c,linked,risk,block,tokenBlock,txIndex);
            var action=CCustodyModels.UserAction.valueOf(CRulesTransitionModels.Action.values()[report.action().intValueExact()].name());
            // A reverted supported call records only a no-effects observation. No approval or failure-cause claim.
            if(receipt.status()==0) {
                var reducedCall=new CCustodyModels.UserCall(action,intent.owner(),report.operationId(),report.positionId(),intent.nonce(),
                    report.expectedVersion(),report.collateralBefore(),report.collateralAfter(),"0x"+"0".repeat(64),"0x"+"0".repeat(64),direct);
                var bundle=new CCustodyModels.Bundle(d,block,receipt.transactionHash(),txIndex,receipt.from(),receipt.to(),CCustodyModels.Outcome.REVERTED,reducedCall,List.of());
                var newVault=vaults.apply(c.vault(),bundle);
                var newToken=tokens.apply(c.token(),new CTokenLedgerModels.Transaction(td,tokenBlock,receipt.transactionHash(),receipt.transactionIndex(),CTokenLedgerModels.Outcome.REVERTED,List.of()));
                check(newToken.balanceOf(d.vault()).equals(newVault.actualAssets()),"ASSETS_POST");
                return success(newVault,newToken,null,null,true);
            }
            need(c.authorizationPrestate()!=null&&c.ownerCode()!=null&&c.rulesKeys()!=null&&c.operationUsed()!=null&&c.activePosition()!=null,"USER_PRESTATE");
            var pre=c.authorizationPrestate();need(pre.rulesPosition()!=null&&pre.userNonce()!=null&&pre.positionOwner()!=null,"USER_PRESTATE_FIELDS");
            var full=pre.rulesPosition();
            need(full.status()!=null&&full.side()!=null&&full.leverage()!=null&&full.quantity()!=null&&full.entryPriceE8()!=null
                &&full.collateral()!=null&&full.version()!=null&&full.callEpoch()!=null&&full.deadline()!=null&&full.lastObservedAt()!=null,"RULES_PRESTATE_FIELDS");
            check(pre.userNonce().equals(c.vault().nonces().getOrDefault(intent.owner(),BigInteger.ZERO)),"VAULT_NONCE");
            check(c.operationUsed()==c.vault().usedOperations().containsKey(report.operationId()),"VAULT_OPERATION");
            linkPosition(c,report,intent.owner());
            var authContext=new CAuthorizationModels.Context(d.chainId(),d.controller(),d.rules(),d.relayer(),d.oracleSigner(),c.symbolHash(),d.policyHash(),
                direct?CAuthorizationModels.Mode.DIRECT:CAuthorizationModels.Mode.RELAYED,linked.transaction().sender(),linked.transaction().nonce(),linked.block().timestamp(),expectedPoint);
            var authorization=authorizations.verify(new CAuthorizationModels.Input(authContext,intent,report,userSignature,priceSignature,pre,c.ownerCode()));
            var rulesInput=new CRulesTransitionModels.Input(CRulesTransitionModels.Action.values()[report.action().intValueExact()],report,
                new CRulesTransitionModels.Terms(d.controller(),c.symbolHash(),d.policyHash()),pre.rulesPosition(),linked.block().timestamp(),c.operationUsed(),c.activePosition(),c.rulesKeys());
            var logs=linked.logs();check(logs.size()>=3,"EVENT_COUNT");
            check(logs.get(0).emitter().equals(d.rules())&&logs.get(1).emitter().equals(d.rules())
                &&logs.get(0).event() instanceof CAbiEventModels.OperationApplied&&logs.get(1).event() instanceof CAbiEventModels.FuturesReportConsumed,"RULES_PREFIX");
            var transition=rules.verifyEvents(rulesInput,(CAbiEventModels.OperationApplied)logs.get(0).event(),(CAbiEventModels.FuturesReportConsumed)logs.get(1).event());
            var expectedUser=new CAbiEventModels.CUserIntentExecuted(report.operationId(),report.positionId(),intent.owner(),authorization.intentDigest(),authorization.reportDigest(),report.action(),intent.nonce(),direct);
            check(logs.getLast().emitter().equals(d.controller())&&logs.getLast().event().equals(expectedUser),"USER_EVENT");
            List<CCustodyModels.Log> reduced=new ArrayList<>();
            for(int k=0;k<logs.size();k++){
                var log=logs.get(k);
                if(k>=2&&k<logs.size()-1)check(log.event() instanceof CAbiEventModels.CollateralLocked
                    ||log.event() instanceof CAbiEventModels.PositionSettled||log.event() instanceof CAbiEventModels.ProfitClaimCreated,"EVENT_MIDDLE_SCOPE");
                int index;try{index=log.logIndex().intValueExact();}catch(ArithmeticException unsupported){stop(Decision.UNSUPPORTED,"LOG_INDEX_RANGE");return null;}
                reduced.add(new CCustodyModels.Log(index,log.emitter(),reduce(log.event())));
            }
            var reducedCall=new CCustodyModels.UserCall(action,intent.owner(),report.operationId(),report.positionId(),intent.nonce(),report.expectedVersion(),
                report.collateralBefore(),report.collateralAfter(),authorization.intentDigest(),authorization.reportDigest(),direct);
            var bundle=new CCustodyModels.Bundle(d,block,receipt.transactionHash(),txIndex,receipt.from(),receipt.to(),CCustodyModels.Outcome.SUCCESS,reducedCall,reduced);
            var newVault=vaults.apply(c.vault(),bundle);
            var newToken=tokens.apply(c.token(),new CTokenLedgerModels.Transaction(td,tokenBlock,receipt.transactionHash(),receipt.transactionIndex(),CTokenLedgerModels.Outcome.SUCCESS,List.of()));
            check(newToken.balanceOf(d.vault()).equals(newVault.actualAssets()),"ASSETS_POST");
            return success(newVault,newToken,transition,authorization,false);
        }catch(Stop stop){return new Result(stop.decision,"C_TX_"+stop.code,null);}
        catch(CAuthorizationVerifier.Failure auth){return new Result(auth.kind()==CAuthorizationModels.FailureKind.INSUFFICIENT_CONTEXT?Decision.INSUFFICIENT:Decision.REJECTED,"C_TX_AUTH",null);}
        catch(IllegalArgumentException|ArithmeticException invalid){return new Result(Decision.REJECTED,"C_TX_PROJECTION_OR_RULES",null);}
    }
    private Result verifyRisk(Context c,CReceiptEnvelopeModels.Linked linked,CAbiModels.Risk call,
        CCustodyModels.Block block,CTokenLedgerModels.Block tokenBlock,int txIndex) {
        var r=call.report();var d=c.vault().deployment();var receipt=linked.receipt();var pre=c.authorizationPrestate();
        need(pre!=null&&pre.positionOwner()!=null,"RISK_OWNER_CONTEXT");
        String owner=CAbiModels.address(pre.positionOwner());
        var action=CCustodyModels.RiskAction.valueOf(call.action().name());
        var reducedCall=new CCustodyModels.RiskCall(action,owner,r.operationId(),r.positionId(),r.expectedVersion(),r.collateralAfter(),"0x"+"0".repeat(64));
        if(receipt.status()==0){
            var v=vaults.apply(c.vault(),new CCustodyModels.Bundle(d,block,receipt.transactionHash(),txIndex,receipt.from(),receipt.to(),CCustodyModels.Outcome.REVERTED,reducedCall,List.of()));
            var t=tokens.apply(c.token(),new CTokenLedgerModels.Transaction(c.token().deployment(),tokenBlock,receipt.transactionHash(),receipt.transactionIndex(),CTokenLedgerModels.Outcome.REVERTED,List.of()));
            check(t.balanceOf(d.vault()).equals(v.actualAssets()),"ASSETS_POST");return success(v,t,null,null,true);
        }
        check(linked.transaction().sender().equals(d.relayer()),"RISK_CALLER");
        check(!owner.equals(ZERO_ADDRESS),"RISK_OWNER");
        need(pre.point()!=null&&pre.positionOwnerKey()!=null&&pre.rulesPositionKey()!=null&&pre.rulesPosition()!=null
            &&c.rulesKeys()!=null&&c.operationUsed()!=null&&c.activePosition()!=null,"RISK_PRESTATE");
        need(pre.point().equals(c.point())&&CAbiModels.hash(pre.positionOwnerKey()).equals(r.positionId())
            &&CAbiModels.hash(pre.rulesPositionKey()).equals(r.positionId()),"RISK_PRESTATE_KEYS_OR_POINT");
        var p=pre.rulesPosition();
        need(p.status()!=null&&p.side()!=null&&p.leverage()!=null&&p.quantity()!=null&&p.entryPriceE8()!=null&&p.collateral()!=null
            &&p.version()!=null&&p.callEpoch()!=null&&p.deadline()!=null&&p.lastObservedAt()!=null,"RISK_POSITION_FIELDS");
        check(p.status()==CRulesTransitionModels.Status.ACTIVE||p.status()==CRulesTransitionModels.Status.MARGIN_CALL,"RISK_POSITION_STATUS");
        check(p.accountRef().equals(authorizations.accountRef(owner,c.symbolHash()))&&p.accountRef().equals(r.accountRef()),"RISK_ACCOUNT");
        check(c.operationUsed()==c.vault().usedOperations().containsKey(r.operationId()),"VAULT_OPERATION");
        linkPosition(c,r,owner);
        try{crypto.verifyReport(r,d.chainId(),d.rules(),d.oracleSigner(),call.priceSignature());}
        catch(IllegalArgumentException invalid){stop(Decision.REJECTED,"RISK_ORACLE_SIGNATURE");}
        String digest=crypto.reportDigest(r,d.chainId(),d.rules());
        var ri=new CRulesTransitionModels.Input(CRulesTransitionModels.Action.valueOf(call.action().name()),r,
            new CRulesTransitionModels.Terms(d.controller(),c.symbolHash(),d.policyHash()),p,linked.block().timestamp(),c.operationUsed(),c.activePosition(),c.rulesKeys());
        var logs=linked.logs();check(logs.size()>=3,"RISK_EVENT_COUNT");
        check(logs.getFirst().emitter().equals(d.rules())&&logs.get(1).emitter().equals(d.rules())
            &&logs.getFirst().event() instanceof CAbiEventModels.OperationApplied&&logs.get(1).event() instanceof CAbiEventModels.FuturesReportConsumed,"RISK_PREFIX");
        var transition=rules.verifyEvents(ri,(CAbiEventModels.OperationApplied)logs.getFirst().event(),(CAbiEventModels.FuturesReportConsumed)logs.get(1).event());
        check(logs.getLast().emitter().equals(d.controller())&&logs.getLast().event().equals(new CAbiEventModels.CRiskExecuted(r.operationId(),r.positionId(),owner,digest,r.action())),"RISK_EVENT");
        List<CCustodyModels.Log> reduced=new ArrayList<>();
        for(int k=0;k<logs.size();k++){
            var l=logs.get(k);
            if(k>=2&&k<logs.size()-1)check(call.action()==CAbiModels.RiskAction.LIQUIDATE&&(l.event() instanceof CAbiEventModels.PositionSettled||l.event() instanceof CAbiEventModels.ProfitClaimCreated),"RISK_MIDDLE_SCOPE");
            int index;try{index=l.logIndex().intValueExact();}catch(ArithmeticException unsupported){stop(Decision.UNSUPPORTED,"LOG_INDEX_RANGE");return null;}
            reduced.add(new CCustodyModels.Log(index,l.emitter(),reduce(l.event())));
        }
        reducedCall=new CCustodyModels.RiskCall(action,owner,r.operationId(),r.positionId(),r.expectedVersion(),r.collateralAfter(),digest);
        var v=vaults.apply(c.vault(),new CCustodyModels.Bundle(d,block,receipt.transactionHash(),txIndex,receipt.from(),receipt.to(),CCustodyModels.Outcome.SUCCESS,reducedCall,reduced));
        var t=tokens.apply(c.token(),new CTokenLedgerModels.Transaction(c.token().deployment(),tokenBlock,receipt.transactionHash(),receipt.transactionIndex(),CTokenLedgerModels.Outcome.SUCCESS,List.of()));
        check(t.balanceOf(d.vault()).equals(v.actualAssets()),"ASSETS_POST");return success(v,t,transition,null,false);
    }
    private void linkPosition(Context c,CAbiModels.Report r,String owner) {
        var p=c.authorizationPrestate().rulesPosition();var projected=c.vault().positions().get(r.positionId());
        if(p.status()==CRulesTransitionModels.Status.NONE) {
            check(projected==null&&CAbiModels.address(c.authorizationPrestate().positionOwner()).equals(ZERO_ADDRESS),"OPEN_POSITION");
        } else {
            check(projected!=null,"POSITION_MISSING");
            check(projected.owner().equals(owner)&&projected.owner().equals(CAbiModels.address(c.authorizationPrestate().positionOwner()))
                &&projected.version().equals(p.version())&&projected.callEpoch().equals(p.callEpoch())&&projected.deadline().equals(p.deadline())
                &&projected.status().name().equals(p.status().name()),"POSITION_LINK");
            boolean terminal=p.status()==CRulesTransitionModels.Status.CLOSED||p.status()==CRulesTransitionModels.Status.LIQUIDATED;
            check(projected.collateral().equals(terminal?BigInteger.ZERO:p.collateral()),"POSITION_COLLATERAL");
        }
    }
    private CCustodyModels.Event reduce(CAbiEventModels.Event event) {
        if(event instanceof CAbiEventModels.OperationApplied e)return new CCustodyModels.RuleApplied(e.operationId(),e.positionId(),CRulesTransitionModels.Action.values()[e.action().intValueExact()].name(),e.version(),e.collateral(),e.realized(),e.badDebt(),e.callEpoch(),e.deadline());
        if(event instanceof CAbiEventModels.FuturesReportConsumed e)return new CCustodyModels.ReportConsumed(e.operationId(),e.observedAt(),e.validUntil());
        if(event instanceof CAbiEventModels.CollateralLocked e)return new CCustodyModels.Locked(e.operationId(),e.positionId(),e.owner(),e.delta(),e.lockedAfter());
        if(event instanceof CAbiEventModels.ProfitClaimCreated e)return new CCustodyModels.ClaimCreated(e.claimId(),e.owner(),e.positionId(),e.operationId(),e.amount());
        if(event instanceof CAbiEventModels.PositionSettled e)return new CCustodyModels.PositionSettled(e.operationId(),e.positionId(),e.owner(),e.collateral(),e.realizedPnl(),e.badDebt(),e.paidProfit(),e.claimId());
        if(event instanceof CAbiEventModels.CUserIntentExecuted e)return new CCustodyModels.UserExecuted(e.operationId(),e.positionId(),e.owner(),e.intentDigest(),e.reportDigest(),CRulesTransitionModels.Action.values()[e.action().intValueExact()].name(),e.nonce(),e.direct());
        if(event instanceof CAbiEventModels.CRiskExecuted e)return new CCustodyModels.RiskExecuted(e.operationId(),e.positionId(),e.owner(),e.reportDigest(),CRulesTransitionModels.Action.values()[e.action().intValueExact()].name());
        stop(Decision.REJECTED,"UNEXPECTED_USER_EVENT");return null;
    }
    private static Result success(CCustodyModels.State v,CTokenLedgerModels.State t,CRulesTransitionModels.Result r,CAuthorizationModels.Result a,boolean reverted){return new Result(Decision.VERIFIED_IN_SCOPE,"C_TX_SUPPORTED_USER_OR_KEEPER_CONTEXT_ONLY",new Verified(v,t,r,a,reverted));}
    private static void need(boolean value,String code){if(!value)stop(Decision.INSUFFICIENT,code);}
    private static void check(boolean value,String code){if(!value)stop(Decision.REJECTED,code);}
    private static void stop(Decision decision,String code){throw new Stop(decision,code);}
}
