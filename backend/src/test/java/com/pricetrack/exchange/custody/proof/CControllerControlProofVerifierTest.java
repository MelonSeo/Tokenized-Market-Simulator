package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CControllerControlProofModels.*;
import static com.pricetrack.exchange.custody.proof.CTransactionProofVerifierTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.model.CCustodyModels;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.rlp.*;

/** Offline public tiny keys only. Prevalidated snapshots, not real nonce/code getters or canonical cancellation. */
class CControllerControlProofVerifierTest {
    final CTransactionProofVerifierTest f=new CTransactionProofVerifierTest();final CControllerControlProofVerifier verifier=new CControllerControlProofVerifier();
    final CAbiCodec abi=new CAbiCodec();final CAbiEventCodec events=new CAbiEventCodec();
    CTransactionProofVerifierTest.Seed rebind(CTransactionProofVerifierTest.Seed seed){
        var d=change(seed.vault().deployment(),"pauseAdmin",OWNER);var observations=new HashMap<CCustodyModels.TxKey,CCustodyModels.Bundle>();
        seed.vault().observations().forEach((k,b)->observations.put(k,change(b,"deployment",d)));
        var state=change(change(seed.vault(),"deployment",d),"observations",observations);return new CTransactionProofVerifierTest.Seed(state,seed.token(),seed.position());
    }
    Input cancel(){return fixture(rebind(f.seed(2000)),new CAbiModels.Cancel(n(0)),USER);}
    Input pause(boolean value){return fixture(rebind(f.seed(2000)),new CAbiModels.Pause(value),USER);}
    Input fixture(CTransactionProofVerifierTest.Seed s,CAbiModels.Call call,ECKeyPair key){
        CAbiEventModels.Event event=call instanceof CAbiModels.Cancel c?new CAbiEventModels.CNonceCancelled(addr(key),c.nonce()):new CAbiEventModels.NewPositionsPauseChanged(((CAbiModels.Pause)call).paused());
        int index=s.vault().lastTransactionIndex()+1;
        byte[] raw=raw(abi.encode(call),key,n(index+4L));String hash=hex(Hash.sha3(raw));var encoded=events.encode(CONTROLLER,event);
        var log=new CReceiptEnvelopeModels.RawLog(hash,n(1),h(101),n(index),n(index*10L),false,CONTROLLER,encoded.topics(),encoded.data());
        var receipt=new CReceiptEnvelopeModels.Receipt(hash,n(1),h(101),n(index),addr(key),CONTROLLER,1,List.of(log));
        var e=new CReceiptEnvelopeModels.Input(hex(raw),hash,new CReceiptEnvelopeModels.Deployment(n(31337),TOKEN,VAULT,CONTROLLER,RULES),new CReceiptEnvelopeModels.BlockContext(n(1),h(101),n(1000)),receipt);
        var point=new CAuthorizationModels.ObservationPoint(n(1),h(101),hash,n(index));
        return new Input(e,new Context(point,s.vault(),s.token(),h(100),call instanceof CAbiModels.Cancel c?new NonceObservation(point,addr(key),c.nonce()):null,
            call instanceof CAbiModels.Cancel?new CAuthorizationModels.CodeObservation(point,addr(key),"0x"):null));
    }
    byte[] raw(String data,ECKeyPair key,BigInteger ethereumNonce){List<RlpType> v=new ArrayList<>(List.of(r(ethereumNonce),r(n(1)),r(n(1_000_000)),RlpString.create(bytes(CONTROLLER)),r(n(0)),RlpString.create(bytes(data))));var unsigned=new ArrayList<>(v);unsigned.addAll(List.of(r(n(31337)),r(n(0)),r(n(0))));var s=Sign.signMessage(Hash.sha3(RlpEncoder.encode(new RlpList(unsigned))),key,false);v.add(r(n(31337*2L+35+s.getV()[0]-27)));v.add(r(new BigInteger(1,s.getR())));v.add(r(new BigInteger(1,s.getS())));return RlpEncoder.encode(new RlpList(v));}
    Result ok(Input i){Result r=verifier.verify(i);assertEquals(Decision.VERIFIED_IN_SCOPE,r.decision(),r.reason());return r;}
    void deny(Input i){Result r=verifier.verify(i);assertNotEquals(Decision.VERIFIED_IN_SCOPE,r.decision());assertNull(r.verified());}
    Input context(Input i,String field,Object value){return change(i,"context",change(i.context(),field,value));}
    Input logs(Input i,List<CReceiptEnvelopeModels.RawLog> logs){return change(i,"envelope",change(i.envelope(),"receipt",change(i.envelope().receipt(),"logs",logs)));}
    Input event(Input i,CAbiEventModels.Event event){var logs=new ArrayList<>(i.envelope().receipt().logs());var encoded=events.encode(CONTROLLER,event);logs.set(0,change(change(logs.getFirst(),"topics",encoded.topics()),"data",encoded.data()));return logs(i,logs);}
    void unchangedMoney(CCustodyModels.State before,CCustodyModels.State after){assertEquals(before.balances(),after.balances());assertEquals(before.positions(),after.positions());assertEquals(before.claims(),after.claims());assertEquals(before.usedOperations(),after.usedOperations());assertEquals(before.totalFree(),after.totalFree());assertEquals(before.totalLocked(),after.totalLocked());assertEquals(before.house(),after.house());assertEquals(before.actualAssets(),after.actualAssets());assertEquals(before.totalUnpaid(),after.totalUnpaid());assertEquals(before.head(),after.head());assertEquals(before.next(),after.next());}
    @Test void firstCancelUsesExplicitZeroGetterAndAdvancesOnlyOwnerNonce(){Input i=cancel();assertFalse(i.context().vault().nonces().containsKey(OWNER));var r=ok(i);assertEquals(n(1),r.verified().vault().nonces().get(OWNER));unchangedMoney(i.context().vault(),r.verified().vault());assertEquals(i.context().token().balances(),r.verified().token().balances());}
    @Test void cancelPreservesOpenPositionLocksAndClaimsOfAllOwners(){var s=rebind(f.waitingClaimWithHouse());Input i=fixture(s,new CAbiModels.Cancel(n(1)),USER);var r=ok(i);assertEquals(n(2),r.verified().vault().nonces().get(OWNER));assertEquals(s.vault().nonces().get(addr(ORACLE)),r.verified().vault().nonces().get(addr(ORACLE)));unchangedMoney(s.vault(),r.verified().vault());}
    @Test void nextCancelRequiresNewCanonicalShapedExpectedNonce(){var first=ok(cancel());var s=new CTransactionProofVerifierTest.Seed(first.verified().vault(),first.verified().token(),CRulesTransitionModels.Position.empty());ok(fixture(s,new CAbiModels.Cancel(n(1)),USER));deny(fixture(s,new CAbiModels.Cancel(n(0)),USER));}
    @Test void cancelWrongGetterValueCannotMatchProjectionByCoincidence(){Input i=cancel();assertEquals("C_CTRL_NONCE_VALUE",verifier.verify(context(i,"nonce",change(i.context().nonce(),"nonce",n(1)))).reason());}
    @Test void cancelProjectionNonceCannotBeSubstitutedWithAnotherOwner(){Input i=cancel();deny(context(i,"nonce",change(i.context().nonce(),"owner",addr(ORACLE))));deny(context(i,"ownerCode",change(i.context().ownerCode(),"ownerKey",addr(ORACLE))));}
    @Test void cancelMissingNonceFieldsAreInsufficientNotDefaultAssumptions(){Input i=cancel();assertEquals(Decision.INSUFFICIENT,verifier.verify(context(i,"nonce",null)).decision());for(String field:List.of("point","owner","nonce"))assertEquals(Decision.INSUFFICIENT,verifier.verify(context(i,"nonce",change(i.context().nonce(),field,null))).decision());}
    @Test void cancelNonceAndCodeMustBeObservedAtExactTransactionPrestate(){Input i=cancel();var other=change(i.context().point(),"transactionHash",h(999));assertEquals(Decision.INSUFFICIENT,verifier.verify(context(i,"nonce",change(i.context().nonce(),"point",other))).decision());assertEquals(Decision.INSUFFICIENT,verifier.verify(context(i,"ownerCode",change(i.context().ownerCode(),"point",other))).decision());}
    @Test void cancelCodeMissingIsInsufficientAndOnlyExactEmptyCodeAllowed(){Input i=cancel();assertEquals(Decision.INSUFFICIENT,verifier.verify(context(i,"ownerCode",null)).decision());for(String code:List.of("0x00","0x6000","0X","", "BAD_CODE_SENTINEL"))deny(context(i,"ownerCode",change(i.context().ownerCode(),"code",code)));}
    @Test void cancelMissingCodeFieldsAreInsufficient(){Input i=cancel();for(String field:List.of("point","ownerKey","code"))assertEquals(Decision.INSUFFICIENT,verifier.verify(context(i,"ownerCode",change(i.context().ownerCode(),field,null))).decision());}
    @Test void nonceMaxOverflowIsRejectedWithoutAdvancingProjection(){var s=rebind(f.seed(2000));s=new CTransactionProofVerifierTest.Seed(change(s.vault(),"nonces",Map.of(OWNER,CAbiModels.UINT_MAX)),s.token(),s.position());Input i=fixture(s,new CAbiModels.Cancel(CAbiModels.UINT_MAX),USER);assertEquals("C_CTRL_NONCE_OVERFLOW",verifier.verify(i).reason());assertEquals(CAbiModels.UINT_MAX,i.context().vault().nonces().get(OWNER));}
    @Test void nonceNearMaxCanAdvanceExactlyToMax(){var s=rebind(f.seed(2000));var before=CAbiModels.UINT_MAX.subtract(n(1));s=new CTransactionProofVerifierTest.Seed(change(s.vault(),"nonces",Map.of(OWNER,before)),s.token(),s.position());var r=ok(fixture(s,new CAbiModels.Cancel(before),USER));assertEquals(CAbiModels.UINT_MAX,r.verified().vault().nonces().get(OWNER));}
    @Test void invalidNegativeOrOverflowNonceObservationIsRejected(){Input i=cancel();deny(context(i,"nonce",change(i.context().nonce(),"nonce",n(-1))));deny(context(i,"nonce",change(i.context().nonce(),"nonce",CAbiModels.UINT_MAX.add(n(1)))));}
    @Test void cancelEventOwnerAndNonceMustMatchRawSenderAndCall(){Input i=cancel();deny(event(i,new CAbiEventModels.CNonceCancelled(addr(ORACLE),n(0))));deny(event(i,new CAbiEventModels.CNonceCancelled(OWNER,n(1))));}
    @Test void pauseByFixedAdminChangesOnlyOpenFlag(){Input i=pause(true);var r=ok(i);assertTrue(r.verified().vault().paused());assertEquals(i.context().vault().nonces(),r.verified().vault().nonces());unchangedMoney(i.context().vault(),r.verified().vault());assertEquals(i.context().token().balances(),r.verified().token().balances());}
    @Test void unpauseWorksAndSameBooleanStillRequiresActualEvent(){
        Input initial=pause(true);var first=ok(initial);
        var s=new CTransactionProofVerifierTest.Seed(first.verified().vault(),first.verified().token(),CRulesTransitionModels.Position.empty());
        Input repeated=fixture(s,new CAbiModels.Pause(true),USER);
        assertNotEquals(initial.envelope().expectedTransactionHash(),repeated.envelope().expectedTransactionHash());
        var rawVerifier=new CSignedTransactionVerifier();
        assertEquals(n(7),rawVerifier.verify(initial.envelope().rawSignedTransaction(),initial.envelope().expectedTransactionHash(),n(31337)).nonce());
        assertEquals(n(8),rawVerifier.verify(repeated.envelope().rawSignedTransaction(),repeated.envelope().expectedTransactionHash(),n(31337)).nonce());
        assertTrue(ok(repeated).verified().vault().paused());
        assertFalse(ok(fixture(s,new CAbiModels.Pause(false),USER)).verified().vault().paused());
        Input same=pause(false);ok(same);deny(logs(same,List.of()));
    }
    @Test void neitherRelayerNorOracleCanActAsPauseAdmin(){var s=rebind(f.seed(2000));for(var key:List.of(RELAY,ORACLE))assertEquals("C_CTRL_PAUSE_ADMIN",verifier.verify(fixture(s,new CAbiModels.Pause(true),key)).reason());}
    @Test void pauseHasNoUserCodeOrNonceCondition(){Input i=pause(true);i=context(i,"nonce",new NonceObservation(null,null,n(-1)));i=context(i,"ownerCode",new CAuthorizationModels.CodeObservation(null,null,"0x6000"));ok(i);}
    @Test void pauseEventBooleanMustMatchCalldata(){deny(event(pause(true),new CAbiEventModels.NewPositionsPauseChanged(false)));}
    @Test void exactControllerEmitterEventCountAndNoTokenEffectsAreRequired(){Input i=cancel();deny(logs(i,List.of()));var list=new ArrayList<>(i.envelope().receipt().logs());list.add(change(list.getFirst(),"logIndex",list.getFirst().logIndex().add(n(1))));deny(logs(i,list));deny(logs(i,List.of(change(i.envelope().receipt().logs().getFirst(),"emitter",VAULT))));var transfer=events.encode(TOKEN,new CAbiEventModels.Transfer(OWNER,VAULT,n(0)));var old=i.envelope().receipt().logs().getFirst();deny(logs(i,List.of(change(change(change(old,"emitter",TOKEN),"topics",transfer.topics()),"data",transfer.data()))));}
    @Test void revertedCancelDoesNotVerifyOrConsumeNonceAndCode(){Input i=cancel();i=logs(i,List.of());i=change(i,"envelope",change(i.envelope(),"receipt",change(i.envelope().receipt(),"status",0)));i=context(context(i,"nonce",null),"ownerCode",null);var r=ok(i);assertTrue(r.verified().reverted());assertEquals(i.context().vault().nonces(),r.verified().vault().nonces());unchangedMoney(i.context().vault(),r.verified().vault());}
    @Test void revertedPauseWrongAdminIsNoEffectsObservationNotAuthorizedPause(){Input i=fixture(rebind(f.seed(2000)),new CAbiModels.Pause(true),RELAY);i=logs(i,List.of());i=change(i,"envelope",change(i.envelope(),"receipt",change(i.envelope().receipt(),"status",0)));var r=ok(i);assertFalse(r.verified().vault().paused());assertTrue(r.verified().reverted());}
    @Test void revertedControlCannotClaimLogs(){Input i=cancel();deny(change(i,"envelope",change(i.envelope(),"receipt",change(i.envelope().receipt(),"status",0))));}
    @Test void rawHashSenderAndDeploymentMismatchAreRejected(){Input i=cancel();deny(change(i,"envelope",change(i.envelope(),"expectedTransactionHash",h(999))));deny(change(i,"envelope",change(i.envelope(),"receipt",change(i.envelope().receipt(),"from",addr(ORACLE)))));deny(change(i,"envelope",change(i.envelope(),"deployment",change(i.envelope().deployment(),"controller",a(99)))));}
    @Test void missingContextAndDifferentSnapshotPointsFailClosed(){Input i=cancel();for(String field:List.of("point","vault","token","parentBlockHash"))assertEquals(Decision.INSUFFICIENT,verifier.verify(context(i,field,null)).decision());deny(context(i,"point",change(i.context().point(),"transactionIndex",n(99))));deny(context(i,"token",change(i.context().token(),"lastTransactionIndex",n(1))));}
    @Test void tokenIdentityAndVaultAssetBalanceMustAgree(){Input i=cancel();deny(context(i,"token",change(i.context().token(),"deployment",change(i.context().token().deployment(),"genesisHash",h(99)))));deny(context(i,"vault",change(i.context().vault(),"actualAssets",n(5001))));}
    @Test void secondReducerFailureDoesNotReturnPartiallyAdvancedNonce(){Input i=cancel();var b=new HashMap<>(i.context().token().balances());b.put(OWNER,b.get(OWNER).subtract(n(1)));b.put(a(88),n(1));deny(context(i,"token",change(i.context().token(),"balances",b)));assertFalse(i.context().vault().nonces().containsKey(OWNER));}
    @Test void checksumAddressKeysAreNormalizedAndOriginalInputReplayIsDeterministic(){Input i=cancel();i=context(i,"nonce",change(i.context().nonce(),"owner",upper(OWNER)));i=context(i,"ownerCode",change(i.context().ownerCode(),"ownerKey",upper(OWNER)));var d=i.envelope().deployment();d=change(change(change(change(d,"token",upper(TOKEN)),"vault",upper(VAULT)),"controller",upper(CONTROLLER)),"rules",upper(RULES));i=change(i,"envelope",change(i.envelope(),"deployment",d));var r=ok(i);assertEquals(r,ok(i));assertFalse(r.completeCoverage());assertTrue(r.verificationScope().contains("PREVALIDATED_CONTEXT_ONLY"));assertFalse(i.toString().contains(i.envelope().rawSignedTransaction()));}
    @Test void envelopeLimitsArePreservedBeforeControllerProcessing(){Input i=cancel();var excessive=Collections.nCopies(129,i.envelope().receipt().logs().getFirst());assertEquals(Decision.INSUFFICIENT,verifier.verify(logs(i,excessive)).decision());}
    @Test void unsupportedCallIsNotConvertedToCancelOrPause(){Input i=cancel();var envelope=f.envelope(new CAbiModels.ProcessClaim(),false,3,List.of());var result=verifier.verify(change(i,"envelope",envelope));assertEquals(Decision.UNSUPPORTED,result.decision());assertNull(result.verified());}
}
