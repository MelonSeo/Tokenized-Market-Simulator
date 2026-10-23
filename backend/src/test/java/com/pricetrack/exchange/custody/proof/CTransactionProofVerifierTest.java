package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CTransactionProofModels.*;
import static org.junit.jupiter.api.Assertions.*;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.model.CCustodyModels;
import com.pricetrack.exchange.custody.projection.CCustodyReducer;
import com.pricetrack.exchange.custody.token.*;
import java.math.BigInteger;
import java.lang.reflect.RecordComponent;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.rlp.*;

/** PUBLIC integers 17/23/29 only. Prevalidated snapshots, not live EVM or canonical scanner evidence. */
class CTransactionProofVerifierTest {
    static final ECKeyPair USER=ECKeyPair.create(n(17)),ORACLE=ECKeyPair.create(n(23)),RELAY=ECKeyPair.create(n(29));
    static final String OWNER=addr(USER),RELAYER=addr(RELAY),TOKEN=a(0xab),VAULT=a(0xbc),CONTROLLER=a(0xcd),RULES=a(0xde),ADMIN=a(99),SYMBOL=h(6),POLICY=h(8);
    static final String ZERO="0x"+"0".repeat(40),ZH=h(0),POS=h(12),ACCOUNT=new CAuthorizationVerifier().accountRef(OWNER,SYMBOL);
    static final HexFormat HEX=HexFormat.of();static final BigInteger PRICE=n(10_000_000_000L);
    static final CCustodyModels.Block ANCHOR=new CCustodyModels.Block(0,h(100),h(0)),BLOCK=new CCustodyModels.Block(1,h(101),h(100));
    static final CCustodyModels.Deployment D=new CCustodyModels.Deployment("C",n(31337),h(100),TOKEN,VAULT,CONTROLLER,RULES,RELAYER,ADMIN,addr(ORACLE),POLICY);
    static final CTokenLedgerModels.Block TB=new CTokenLedgerModels.Block(n(1),h(101),h(100));
    static final CTokenLedgerModels.Deployment TD=new CTokenLedgerModels.Deployment("CT",n(31337),h(100),TOKEN,OWNER,h(300),TB,h(500),n(0),n(0));
    final CTransactionProofVerifier verifier=new CTransactionProofVerifier();
    final CEip712Verifier crypto=new CEip712Verifier();final CAbiCodec abi=new CAbiCodec();final CAbiEventCodec events=new CAbiEventCodec();
    final CCustodyReducer vaults=new CCustodyReducer(new CCustodyReducer.Limits(100,128,100,100,100));
    final CTokenLedgerReducer tokens=new CTokenLedgerReducer(new CTokenLedgerReducer.Limits(100,128,100,100));
    record Seed(CCustodyModels.State vault,CTokenLedgerModels.State token,CRulesTransitionModels.Position position) {}
    Seed seed(long house) {
        var v=vaults.initial(D,ANCHOR);var t=tokens.initial(TD,new CTokenLedgerModels.Block(n(0),h(100),h(0)));
        t=tokens.apply(t,new CTokenLedgerModels.Transaction(TD,TB,h(500),n(0),CTokenLedgerModels.Outcome.SUCCESS,List.of(new CTokenLedgerModels.Log(n(0),TOKEN,new CAbiEventModels.Transfer(ZERO,OWNER,CTokenLedgerModels.FIXED_SUPPLY)))));
        var dc=new CCustodyModels.VaultCall(CCustodyModels.VaultAction.DEPOSIT,OWNER,n(3000));
        v=vaults.apply(v,bundle(h(501),1,dc,List.of(new CCustodyModels.Log(1,TOKEN,new CCustodyModels.Transfer(OWNER,VAULT,n(3000))),new CCustodyModels.Log(2,VAULT,new CCustodyModels.Deposited(OWNER,n(3000))))));
        t=tokens.apply(t,new CTokenLedgerModels.Transaction(TD,TB,h(501),n(1),CTokenLedgerModels.Outcome.SUCCESS,List.of(new CTokenLedgerModels.Log(n(1),TOKEN,new CAbiEventModels.Transfer(OWNER,VAULT,n(3000))))));
        v=vaults.apply(v,bundle(h(502),2,new CCustodyModels.VaultCall(CCustodyModels.VaultAction.FUND_HOUSE,OWNER,n(house)),List.of(new CCustodyModels.Log(3,TOKEN,new CCustodyModels.Transfer(OWNER,VAULT,n(house))),new CCustodyModels.Log(4,VAULT,new CCustodyModels.HouseFunded(OWNER,n(house))))));
        t=tokens.apply(t,new CTokenLedgerModels.Transaction(TD,TB,h(502),n(2),CTokenLedgerModels.Outcome.SUCCESS,List.of(new CTokenLedgerModels.Log(n(3),TOKEN,new CAbiEventModels.Transfer(OWNER,VAULT,n(house))))));
        return new Seed(v,t,CRulesTransitionModels.Position.empty());
    }
    CCustodyModels.Bundle bundle(String hash,int index,CCustodyModels.Call call,List<CCustodyModels.Log> logs){return new CCustodyModels.Bundle(D,BLOCK,hash,index,OWNER,VAULT,CCustodyModels.Outcome.SUCCESS,call,logs);}
    Input open(boolean relay){return fixture(seed(2000),0,PRICE,1000,relay);}
    Seed opened(long house) {
        Result r=verifier.verify(fixture(seed(house),0,PRICE,1000,false));assertEquals(Decision.VERIFIED_IN_SCOPE,r.decision(),r.reason());
        return new Seed(r.verified().vault(),r.verified().token(),r.verified().rules().after());
    }
    Input fixture(Seed seed,int action,BigInteger price,long collateral,boolean relay) {
        var p=seed.position();boolean opening=action==0;
        BigInteger before=opening?n(0):p.collateral(),entry=opening?price:p.entryPriceE8(),quantity=opening?n(100):p.quantity();
        BigInteger nonce=seed.vault().nonces().getOrDefault(OWNER,n(0)),version=opening?n(0):p.version(),epoch=opening?n(0):p.callEpoch();
        String op=h(30+seed.vault().lastTransactionIndex());
        var report=new CAbiModels.Report(op,POS,ACCOUNT,SYMBOL,n(action),version,epoch,n(0),n(10),quantity,entry,before,n(collateral),price,n(1000),n(1030),POLICY,CONTROLLER);
        var intent=new CAbiModels.Intent(OWNER,RELAYER,op,POS,ACCOUNT,SYMBOL,n(action),version,epoch,n(0),n(10),opening?n(0):entry,before,n(collateral),n(1),n(20_000_000_000L),opening?n(1):quantity,opening?n(200):quantity,nonce,n(950),n(1070),POLICY);
        String ps=sign(crypto.reportDigest(report,n(31337),RULES),ORACLE),us=sign(crypto.intentDigest(intent,n(31337),CONTROLLER),USER);
        CAbiModels.Call call=relay?new CAbiModels.Relayed(report,ps,intent,us):new CAbiModels.Direct(report,ps,intent);
        var ri=new CRulesTransitionModels.Input(CRulesTransitionModels.Action.values()[action],report,new CRulesTransitionModels.Terms(CONTROLLER,SYMBOL,POLICY),p,n(1000),false,opening?ZH:POS,new CRulesTransitionModels.PrestateKeys(POS,ACCOUNT,op));
        var transition=new CRulesTransitionVerifier().verify(ri);List<CAbiEventModels.Log> logs=new ArrayList<>();
        logs.add(events.encode(RULES,transition.operationApplied()));logs.add(events.encode(RULES,transition.reportConsumed()));
        if(action==0||action==1)logs.add(events.encode(VAULT,new CAbiEventModels.CollateralLocked(op,POS,OWNER,n(collateral).subtract(before),n(collateral))));
        else {
            BigInteger pnl=transition.mark().realized(),paid=n(0),id=n(0);
            if(pnl.signum()>0){paid=seed.vault().totalUnpaid().signum()==0?pnl.min(seed.vault().house()):n(0);BigInteger unpaid=pnl.subtract(paid);if(unpaid.signum()>0){id=seed.vault().next();logs.add(events.encode(VAULT,new CAbiEventModels.ProfitClaimCreated(id,OWNER,POS,op,unpaid)));}}
            logs.add(events.encode(VAULT,new CAbiEventModels.PositionSettled(op,POS,OWNER,n(collateral),pnl,transition.mark().badDebt(),paid,id)));
        }
        logs.add(events.encode(CONTROLLER,new CAbiEventModels.CUserIntentExecuted(op,POS,OWNER,crypto.intentDigest(intent,n(31337),CONTROLLER),crypto.reportDigest(report,n(31337),RULES),n(action),nonce,!relay)));
        var envelope=envelope(call,relay,seed.vault().lastTransactionIndex()+1,logs);
        var receipt=envelope.receipt();var point=new CAuthorizationModels.ObservationPoint(receipt.blockNumber(),receipt.blockHash(),receipt.transactionHash(),receipt.transactionIndex());
        var pre=new CAuthorizationModels.Prestate(point,OWNER,nonce,POS,opening?ZERO:OWNER,POS,p);
        var context=new Context(point,SYMBOL,pre,new CAuthorizationModels.CodeObservation(point,OWNER,"0x"),ri.prestateKeys(),false,ri.activePositionForAccount(),seed.vault(),seed.token(),h(100));
        return new Input(envelope,context);
    }
    CReceiptEnvelopeModels.Input envelope(CAbiModels.Call call,boolean relay,int index,List<CAbiEventModels.Log> logs) {
        String data=abi.encode(call);byte[] raw=raw(data,relay);String hash=hex(Hash.sha3(raw));List<CReceiptEnvelopeModels.RawLog> full=new ArrayList<>();
        for(int i=0;i<logs.size();i++){var l=logs.get(i);full.add(new CReceiptEnvelopeModels.RawLog(hash,n(1),h(101),n(index),n(index*10L+i),false,l.emitter(),l.topics(),l.data()));}
        var receipt=new CReceiptEnvelopeModels.Receipt(hash,n(1),h(101),n(index),relay?RELAYER:OWNER,CONTROLLER,1,full);
        return new CReceiptEnvelopeModels.Input(hex(raw),hash,new CReceiptEnvelopeModels.Deployment(n(31337),TOKEN,VAULT,CONTROLLER,RULES),new CReceiptEnvelopeModels.BlockContext(n(1),h(101),n(1000)),receipt);
    }
    byte[] raw(String data,boolean relay){
        List<RlpType> f=new ArrayList<>(List.of(r(n(7)),r(n(1)),r(n(1_000_000)),RlpString.create(bytes(CONTROLLER)),r(n(0)),RlpString.create(bytes(data))));
        List<RlpType> unsigned=new ArrayList<>(f);unsigned.addAll(List.of(r(n(31337)),r(n(0)),r(n(0))));
        var s=Sign.signMessage(Hash.sha3(RlpEncoder.encode(new RlpList(unsigned))),relay?RELAY:USER,false);int parity=s.getV()[0]-27;
        f.add(r(n(31337*2L+35+parity)));f.add(r(new BigInteger(1,s.getR())));f.add(r(new BigInteger(1,s.getS())));return RlpEncoder.encode(new RlpList(f));
    }
    String sign(String digest,ECKeyPair key){var s=Sign.signMessage(bytes(digest),key,false);return hex(s.getR())+HEX.formatHex(s.getS())+HEX.formatHex(s.getV());}
    Result ok(Input i){Result r=verifier.verify(i);assertEquals(Decision.VERIFIED_IN_SCOPE,r.decision(),r.reason());return r;}
    void deny(Input i){var r=verifier.verify(i);assertNotEquals(Decision.VERIFIED_IN_SCOPE,r.decision());assertNull(r.verified());}
    Input context(Input i,String key,Object value){return change(i,"context",change(i.context(),key,value));}
    Input log(Input i,int at,String field,Object value){var logs=new ArrayList<>(i.envelope().receipt().logs());logs.set(at,change(logs.get(at),field,value));return change(i,"envelope",change(i.envelope(),"receipt",change(i.envelope().receipt(),"logs",logs)));}
    Input event(Input i,int at,CAbiEventModels.Event e){var old=i.envelope().receipt().logs().get(at);var encoded=events.encode(old.emitter(),e);return log(log(i,at,"topics",encoded.topics()),at,"data",encoded.data());}
    Input signedCall(Input i,CAbiModels.Call call,boolean relay){
        List<CAbiEventModels.Log> logs=i.envelope().receipt().logs().stream().map(l->new CAbiEventModels.Log(l.emitter(),l.topics(),l.data())).toList();
        var e=envelope(call,relay,i.envelope().receipt().transactionIndex().intValueExact(),logs);
        var p=new CAuthorizationModels.ObservationPoint(e.receipt().blockNumber(),e.receipt().blockHash(),e.receipt().transactionHash(),e.receipt().transactionIndex());
        var c=change(change(change(i.context(),"point",p),"authorizationPrestate",change(i.context().authorizationPrestate(),"point",p)),"ownerCode",change(i.context().ownerCode(),"point",p));
        return new Input(e,c);
    }
    Input reposition(Input i,String position){
        var dc=(CAbiModels.Direct)abi.decode(CAbiModels.Target.CONTROLLER,new CSignedTransactionVerifier().verify(i.envelope().rawSignedTransaction(),i.envelope().expectedTransactionHash(),n(31337)).calldata());
        var report=change(dc.report(),"positionId",position);var intent=change(dc.intent(),"positionId",position);
        i=signedCall(i,new CAbiModels.Direct(report,sign(crypto.reportDigest(report,n(31337),RULES),ORACLE),intent),false);
        for(int at=0;at<i.envelope().receipt().logs().size();at++){
            var l=i.envelope().receipt().logs().get(at);var kind=l.emitter().equals(RULES)?CAbiEventModels.EmitterKind.RULES:l.emitter().equals(VAULT)?CAbiEventModels.EmitterKind.VAULT:CAbiEventModels.EmitterKind.CONTROLLER;
            CAbiEventModels.Event event=events.decode(kind,new CAbiEventModels.Log(l.emitter(),l.topics(),l.data()));
            if(event instanceof Record record&&Arrays.stream(record.getClass().getRecordComponents()).anyMatch(f->f.getName().equals("positionId")))event=(CAbiEventModels.Event)change(record,"positionId",position);
            if(event instanceof CAbiEventModels.CUserIntentExecuted u)event=change(u,"intentDigest",crypto.intentDigest(intent,n(31337),CONTROLLER));
            if(event instanceof CAbiEventModels.CUserIntentExecuted u)event=change(u,"reportDigest",crypto.reportDigest(report,n(31337),RULES));
            i=event(i,at,event);
        }
        i=context(i,"authorizationPrestate",change(change(i.context().authorizationPrestate(),"positionOwnerKey",position),"rulesPositionKey",position));return context(i,"rulesKeys",change(i.context().rulesKeys(),"positionId",position));
    }
    Seed stage(Seed seed,CCustodyModels.Call call,String from,String to,List<String> emitters,List<CCustodyModels.Event> values,List<CAbiEventModels.Transfer> transfers){
        int index=Math.max(seed.vault().lastTransactionIndex(),seed.token().lastTransactionIndex().intValueExact())+1;String hash=h(800+index);
        List<CCustodyModels.Log> logs=new ArrayList<>();for(int j=0;j<values.size();j++)logs.add(new CCustodyModels.Log(index*10+j,emitters.get(j),values.get(j)));
        var v=vaults.apply(seed.vault(),new CCustodyModels.Bundle(D,BLOCK,hash,index,from,to,CCustodyModels.Outcome.SUCCESS,call,logs));
        List<CTokenLedgerModels.Log> tokenLogs=new ArrayList<>();for(int j=0;j<transfers.size();j++)tokenLogs.add(new CTokenLedgerModels.Log(n(index*10L+j),TOKEN,transfers.get(j)));
        var t=tokens.apply(seed.token(),new CTokenLedgerModels.Transaction(TD,TB,hash,n(index),CTokenLedgerModels.Outcome.SUCCESS,tokenLogs));return new Seed(v,t,seed.position());
    }
    Seed marginCalled(){
        Seed s=opened(2000);String op=h(77);
        s=stage(s,new CCustodyModels.RiskCall(CCustodyModels.RiskAction.START_CALL,OWNER,op,POS,n(1),n(1000),h(78)),RELAYER,CONTROLLER,List.of(RULES,RULES,CONTROLLER),List.of(
            new CCustodyModels.RuleApplied(op,POS,"START_CALL",n(2),n(1000),n(0),n(0),n(1),n(1060)),new CCustodyModels.ReportConsumed(op,n(1000),n(1030)),new CCustodyModels.RiskExecuted(op,POS,OWNER,h(78),"START_CALL")),List.of());
        var p=new CRulesTransitionModels.Position(ACCOUNT,n(0),n(10),n(100),PRICE,n(1000),CRulesTransitionModels.Status.MARGIN_CALL,n(2),n(1),n(1060),n(1000));return new Seed(s.vault(),s.token(),p);
    }
    Seed waitingClaimWithHouse(){
        Seed s=opened(50);String other=addr(ORACLE),pos=h(90),open=h(91),close=h(92);
        int index=s.token().lastTransactionIndex().intValueExact()+1;
        var t=tokens.apply(s.token(),new CTokenLedgerModels.Transaction(TD,TB,h(700+index),n(index),CTokenLedgerModels.Outcome.SUCCESS,List.of(new CTokenLedgerModels.Log(n(index*10L),TOKEN,new CAbiEventModels.Transfer(OWNER,other,n(1000))))));s=new Seed(s.vault(),t,s.position());
        s=stage(s,new CCustodyModels.VaultCall(CCustodyModels.VaultAction.DEPOSIT,other,n(1000)),other,VAULT,List.of(TOKEN,VAULT),List.of(new CCustodyModels.Transfer(other,VAULT,n(1000)),new CCustodyModels.Deposited(other,n(1000))),List.of(new CAbiEventModels.Transfer(other,VAULT,n(1000))));
        s=stage(s,new CCustodyModels.UserCall(CCustodyModels.UserAction.OPEN,other,open,pos,n(0),n(0),n(0),n(1000),h(93),h(94),true),other,CONTROLLER,List.of(RULES,RULES,VAULT,CONTROLLER),List.of(
            new CCustodyModels.RuleApplied(open,pos,"OPEN",n(1),n(1000),n(0),n(0),n(0),n(0)),new CCustodyModels.ReportConsumed(open,n(1000),n(1030)),new CCustodyModels.Locked(open,pos,other,n(1000),n(1000)),new CCustodyModels.UserExecuted(open,pos,other,h(93),h(94),"OPEN",n(0),true)),List.of());
        s=stage(s,new CCustodyModels.UserCall(CCustodyModels.UserAction.CLOSE,other,close,pos,n(1),n(1),n(1000),n(1000),h(95),h(96),true),other,CONTROLLER,List.of(RULES,RULES,VAULT,VAULT,CONTROLLER),List.of(
            new CCustodyModels.RuleApplied(close,pos,"CLOSE",n(2),n(1000),n(1000),n(0),n(0),n(0)),new CCustodyModels.ReportConsumed(close,n(1000),n(1030)),new CCustodyModels.ClaimCreated(n(1),other,pos,close,n(950)),new CCustodyModels.PositionSettled(close,pos,other,n(1000),n(1000),n(0),n(50),n(1)),new CCustodyModels.UserExecuted(close,pos,other,h(95),h(96),"CLOSE",n(1),true)),List.of());
        return stage(s,new CCustodyModels.VaultCall(CCustodyModels.VaultAction.FUND_HOUSE,OWNER,n(100)),OWNER,VAULT,List.of(TOKEN,VAULT),List.of(new CCustodyModels.Transfer(OWNER,VAULT,n(100)),new CCustodyModels.HouseFunded(OWNER,n(100))),List.of(new CAbiEventModels.Transfer(OWNER,VAULT,n(100))));
    }
    @Test void directOpenVerifiesOriginalRawOracleAndBothReducers(){var r=ok(open(false));assertEquals(n(1000),r.verified().vault().totalLocked());assertEquals(n(100),r.verified().rules().after().quantity());assertTrue(r.verified().authorization().mode()==CAuthorizationModels.Mode.DIRECT);}
    @Test void relayedOpenReverifiesActualUserAndRawRelayer(){var r=ok(open(true));assertEquals(CAuthorizationModels.Mode.RELAYED,r.verified().authorization().mode());assertEquals(n(7),r.verified().authorization().ethereumTransactionNonce());assertEquals(n(0),r.verified().authorization().consumedUserNonce());}
    @Test void directTopUpIncreasesOnlyExistingLock(){var r=ok(fixture(opened(2000),1,PRICE,1500,false));assertEquals(n(1500),r.verified().vault().totalLocked());assertEquals(n(1500),r.verified().vault().totalFree());}
    @Test void relayedTopUpRetainsOwnerAndExactEntryQuantity(){var r=ok(fixture(opened(2000),1,n(11_000_000_000L),1500,true));assertEquals(PRICE,r.verified().rules().after().entryPriceE8());assertEquals(n(100),r.verified().rules().after().quantity());}
    @Test void directClosePaysProfitFromHouseNotTokens(){var r=ok(fixture(opened(2000),4,n(11_000_000_000L),1000,false));assertEquals(n(4000),r.verified().vault().totalFree());assertEquals(n(1000),r.verified().vault().house());assertEquals(n(5000),r.verified().token().balanceOf(VAULT));}
    @Test void relayCloseAtLossReplenishesHouse(){var r=ok(fixture(opened(2000),4,n(9_500_000_000L),1000,true));assertEquals(n(2500),r.verified().vault().totalFree());assertEquals(n(2500),r.verified().vault().house());}
    @Test void profitCloseCreatesExactUnpaidClaim(){var r=ok(fixture(opened(50),4,n(11_000_000_000L),1000,false));assertEquals(n(950),r.verified().vault().totalUnpaid());assertEquals(n(3050),r.verified().vault().totalFree());assertEquals(n(0),r.verified().vault().house());}
    @Test void terminalRulesCollateralIsHistoricalButVaultLockZero(){var r=ok(fixture(opened(2000),4,PRICE,1000,false));assertEquals(n(1000),r.verified().rules().after().collateral());assertEquals(n(0),r.verified().vault().positions().get(POS).collateral());}
    @Test void rawHashTamperFailsBeforeFinancialProjection(){Input i=open(false);deny(change(i,"envelope",change(i.envelope(),"expectedTransactionHash",h(999))));}
    @Test void validRawDoesNotMakeInvalidUserOrOracleSignatureValid(){Input i=open(true);var c=(CAbiModels.Relayed)abi.decode(CAbiModels.Target.CONTROLLER,new CSignedTransactionVerifier().verify(i.envelope().rawSignedTransaction(),i.envelope().expectedTransactionHash(),n(31337)).calldata());deny(signedCall(i,new CAbiModels.Relayed(c.report(),c.priceSignature(),c.intent(),"0x"+"00".repeat(65)),true));deny(signedCall(i,new CAbiModels.Relayed(c.report(),"0x"+"00".repeat(65),c.intent(),c.userSignature()),true));}
    @Test void userAndOracleKeysCannotApproveEachOthersRole(){Input i=open(true);var c=(CAbiModels.Relayed)abi.decode(CAbiModels.Target.CONTROLLER,new CSignedTransactionVerifier().verify(i.envelope().rawSignedTransaction(),i.envelope().expectedTransactionHash(),n(31337)).calldata());assertNotEquals(OWNER,D.oracleSigner());assertNotEquals(RELAYER,D.oracleSigner());deny(signedCall(i,new CAbiModels.Relayed(c.report(),c.priceSignature(),c.intent(),sign(crypto.intentDigest(c.intent(),n(31337),CONTROLLER),ORACLE)),true));deny(signedCall(i,new CAbiModels.Relayed(c.report(),sign(crypto.reportDigest(c.report(),n(31337),RULES),USER),c.intent(),c.userSignature()),true));}
    @Test void topUpRecoversMarginCallWithSameEpochAndZeroDeadline(){var r=ok(fixture(marginCalled(),1,n(9_000_000_000L),1500,false));var p=r.verified().rules().after();assertEquals(CRulesTransitionModels.Status.ACTIVE,p.status());assertEquals(n(1),p.callEpoch());assertEquals(n(3),p.version());assertEquals(n(0),p.deadline());assertEquals(n(0),r.verified().vault().positions().get(POS).deadline());}
    @Test void existingFifoClaimCannotBeOvertakenEvenWhenHouseHasCash(){Seed s=waitingClaimWithHouse();assertEquals(n(950),s.vault().totalUnpaid());assertEquals(n(100),s.vault().house());var r=ok(fixture(s,4,n(11_000_000_000L),1000,true));assertEquals(n(100),r.verified().vault().house());assertEquals(n(1950),r.verified().vault().totalUnpaid());assertEquals(n(0),r.verified().vault().claims().get(n(2)).paid());assertEquals(n(1000),r.verified().vault().claims().get(n(2)).remaining());assertEquals(s.vault().claims().get(n(1)),r.verified().vault().claims().get(n(1)));}
    @Test void adminPauseAndExistingUnpaidEachBlockOpen(){Input i=open(false);deny(context(i,"vault",change(i.context().vault(),"paused",true)));Seed s=waitingClaimWithHouse();s=stage(s,new CCustodyModels.UserCall(CCustodyModels.UserAction.CLOSE,OWNER,h(140),POS,n(1),n(1),n(1000),n(1000),h(141),h(142),true),OWNER,CONTROLLER,List.of(RULES,RULES,VAULT,CONTROLLER),List.of(new CCustodyModels.RuleApplied(h(140),POS,"CLOSE",n(2),n(1000),n(0),n(0),n(0),n(0)),new CCustodyModels.ReportConsumed(h(140),n(1000),n(1030)),new CCustodyModels.PositionSettled(h(140),POS,OWNER,n(1000),n(0),n(0),n(0),n(0)),new CCustodyModels.UserExecuted(h(140),POS,OWNER,h(141),h(142),"CLOSE",n(1),true)),List.of());
        // New id has pristine rules/owner evidence; previous terminal id remains historical.
        Input fresh=fixture(new Seed(s.vault(),s.token(),CRulesTransitionModels.Position.empty()),0,PRICE,1000,false);
        fresh=reposition(fresh,h(150));assertEquals(n(950),fresh.context().vault().totalUnpaid());assertEquals("C_TX_PROJECTION_OR_RULES",verifier.verify(fresh).reason());deny(fresh);
    }
    @Test void checksumCaseDoesNotTurnValidDeploymentOrPositionOwnerIntoConflict(){Input i=open(false);var d=i.envelope().deployment();d=change(change(change(change(d,"token",upper(d.token())),"vault",upper(d.vault())),"controller",upper(d.controller())),"rules",upper(d.rules()));ok(change(i,"envelope",change(i.envelope(),"deployment",d)));for(int action:List.of(1,4)){Input existing=fixture(opened(2000),action,PRICE,action==1?1500:1000,false);ok(context(existing,"authorizationPrestate",change(existing.context().authorizationPrestate(),"positionOwner",upper(OWNER))));}}
    @Test void reportAndUserTimingAreNotBypassedByReceiptSuccess(){Input i=open(false);deny(change(i,"envelope",change(i.envelope(),"block",change(i.envelope().block(),"timestamp",n(1100)))));deny(change(i,"envelope",change(i.envelope(),"block",change(i.envelope().block(),"timestamp",n(1031)))));}
    @Test void receiptPointMismatchIsInsufficient(){Input i=open(false);var r=verifier.verify(context(i,"point",change(i.context().point(),"transactionIndex",n(99))));assertEquals(Decision.INSUFFICIENT,r.decision());}
    @Test void missingContextAndEachCriticalPrestateAreInsufficient(){Input i=open(false);deny(change(i,"context",null));for(String f:List.of("authorizationPrestate","ownerCode","rulesKeys","operationUsed","activePosition","vault","token","parentBlockHash"))assertEquals(Decision.INSUFFICIENT,verifier.verify(context(i,f,null)).decision(),f);}
    @Test void ownerCodeMustBeObservedAtExactPoint(){Input i=open(false);deny(context(i,"ownerCode",change(i.context().ownerCode(),"point",change(i.context().point(),"blockHash",h(99)))));deny(context(i,"ownerCode",change(i.context().ownerCode(),"code","0x01")));}
    @Test void fullRulesMappingKeysCannotBeRebound(){Input i=open(false);deny(context(i,"rulesKeys",change(i.context().rulesKeys(),"operationId",h(999))));}
    @Test void suppliedNonceMustMatchVaultProjection(){Input i=open(false);deny(context(i,"authorizationPrestate",change(i.context().authorizationPrestate(),"userNonce",n(1))));}
    @Test void usedOperationMustMatchVaultProjectionAndRules(){deny(context(open(false),"operationUsed",true));}
    @Test void activePointerCannotHideAnExistingPosition(){deny(context(open(false),"activePosition",POS));}
    @Test void tokenDeploymentIdentityCannotBeMixed(){Input i=open(false);var td=change(i.context().token().deployment(),"token",a(90));deny(context(i,"token",change(i.context().token(),"deployment",td)));}
    @Test void envelopeDeploymentCannotDifferFromVaultDeployment(){Input i=open(false);deny(change(i,"envelope",change(i.envelope(),"deployment",change(i.envelope().deployment(),"controller",a(90)))));}
    @Test void projectionCursorsMustDescribeSamePrestate(){Input i=open(false);assertEquals(Decision.INSUFFICIENT,verifier.verify(context(i,"token",change(i.context().token(),"lastTransactionIndex",n(1)))).decision());}
    @Test void vaultActualAssetsMustMatchTokenBalance(){Input i=open(false);deny(context(i,"vault",change(i.context().vault(),"actualAssets",n(5001))));}
    @Test void forgedTokenSeedIsRejectedAtomically(){Input i=open(false);var b=new HashMap<>(i.context().token().balances());b.put(OWNER,b.get(OWNER).subtract(n(1)));b.put(a(90),n(1));var token=change(i.context().token(),"balances",b);var r=verifier.verify(context(i,"token",token));assertEquals(Decision.REJECTED,r.decision());assertNull(r.verified());assertEquals(n(0),i.context().vault().totalLocked());}
    @Test void everyFullRulesOperationFieldMutationIsRejected(){Input i=open(false);var e=(CAbiEventModels.OperationApplied)new CAbiEventCodec().decode(CAbiEventModels.EmitterKind.RULES,new CAbiEventModels.Log(RULES,i.envelope().receipt().logs().getFirst().topics(),i.envelope().receipt().logs().getFirst().data()));for(var f:e.getClass().getRecordComponents()){Object value=field(e,f.getName());deny(event(i,0,change(e,f.getName(),value instanceof BigInteger b?b.add(n(1)):h(999))));}}
    @Test void fullReportConsumedTimesAreCompared(){Input i=open(false);deny(event(i,1,new CAbiEventModels.FuturesReportConsumed(h(32),n(1001),n(1031))));}
    @Test void everyControllerEventFieldMutationIsRejected(){Input i=open(false);int at=i.envelope().receipt().logs().size()-1;var l=i.envelope().receipt().logs().get(at);var e=(CAbiEventModels.CUserIntentExecuted)events.decode(CAbiEventModels.EmitterKind.CONTROLLER,new CAbiEventModels.Log(CONTROLLER,l.topics(),l.data()));for(var f:e.getClass().getRecordComponents()){Object v=field(e,f.getName());Object wrong=v instanceof BigInteger b?b.add(n(1)):v instanceof Boolean b?!b:f.getName().equals("owner")?a(99):h(999);deny(event(i,at,change(e,f.getName(),wrong)));}}
    @Test void lockEventMismatchCannotMoveCollateral(){Input i=open(false);deny(event(i,2,new CAbiEventModels.CollateralLocked(h(32),POS,OWNER,n(999),n(1000))));assertEquals(n(3000),i.context().vault().totalFree());}
    @Test void claimAndSettlementAmountsMustMatchFifoAccounting(){Input i=fixture(opened(50),4,n(11_000_000_000L),1000,false);deny(event(i,2,new CAbiEventModels.ProfitClaimCreated(n(1),OWNER,POS,h(33),n(951))));deny(event(i,3,new CAbiEventModels.PositionSettled(h(33),POS,OWNER,n(1000),n(1000),n(0),n(51),n(1))));}
    @Test void eventOrderExtraTokenEventAndMissingLogsAreRejected(){Input i=open(false);var logs=new ArrayList<>(i.envelope().receipt().logs());Collections.swap(logs,0,1);deny(change(i,"envelope",change(i.envelope(),"receipt",change(i.envelope().receipt(),"logs",logs))));deny(log(i,2,"emitter",RULES));deny(event(i,2,new CAbiEventModels.Transfer(OWNER,VAULT,n(0))));logs=new ArrayList<>(i.envelope().receipt().logs());logs.remove(2);deny(change(i,"envelope",change(i.envelope(),"receipt",change(i.envelope().receipt(),"logs",logs))));}
    @Test void revertedSupportedCallDoesNotVerifyApprovalOrSettle(){Input i=open(false);var receipt=change(change(i.envelope().receipt(),"status",0),"logs",List.of());i=change(i,"envelope",change(i.envelope(),"receipt",receipt));i=context(i,"ownerCode",null);var r=ok(i);assertTrue(r.verified().reverted());assertNull(r.verified().authorization());assertNull(r.verified().rules());assertEquals(n(3000),r.verified().vault().totalFree());assertEquals(n(0),r.verified().vault().totalLocked());}
    @Test void unsupportedCallScopeNeverFabricatesAnAccountingCall(){Input i=open(false);var env=envelope(new CAbiModels.Cancel(n(0)),false,3,List.of());var r=verifier.verify(change(i,"envelope",env));assertEquals(Decision.UNSUPPORTED,r.decision());assertNull(r.verified());}
    @Test void repeatedOriginalPrestateIsDeterministicAndInputsRemainImmutable(){Input i=open(false);var first=ok(i);var second=ok(i);assertEquals(first,second);assertEquals(n(0),i.context().vault().totalLocked());assertEquals(2,i.context().vault().observations().size());assertFalse(first.completeCoverage());assertTrue(first.verificationScope().contains("PREVALIDATED_CONTEXT_ONLY"));assertFalse(i.toString().contains(i.envelope().rawSignedTransaction()));}
    static BigInteger n(long value){return BigInteger.valueOf(value);}static String a(long value){return "0x"+String.format("%040x",value);}static String h(long value){return "0x"+String.format("%064x",value);}static String addr(ECKeyPair key){return "0x"+Keys.getAddress(key.getPublicKey());}static byte[] bytes(String value){return HEX.parseHex(value.substring(2));}static String hex(byte[] value){return "0x"+HEX.formatHex(value);}static RlpString r(BigInteger value){return RlpString.create(value);}
    static String upper(String address){return "0x"+address.substring(2).toUpperCase(Locale.ROOT);}
    static Object field(Record record,String name){try{return record.getClass().getMethod(name).invoke(record);}catch(Exception e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked") static <T extends Record>T change(T record,String name,Object value){try{var fields=record.getClass().getRecordComponents();Class<?>[] types=new Class<?>[fields.length];Object[] values=new Object[fields.length];for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();values[i]=fields[i].getName().equals(name)?value:fields[i].getAccessor().invoke(record);}return (T)record.getClass().getConstructor(types).newInstance(values);}catch(Exception e){throw new AssertionError(e);}}
}
