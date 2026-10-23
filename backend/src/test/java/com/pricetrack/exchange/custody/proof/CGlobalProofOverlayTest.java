package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CGlobalProofOverlay.*;
import static com.pricetrack.exchange.custody.proof.CTransactionProofVerifierTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.model.CCustodyModels;
import java.io.Serializable;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.rlp.*;

/** Same-block offline only. All new raw envelopes use explicit monotonic Ethereum nonces. */
class CGlobalProofOverlayTest {
    final CGlobalProofOverlay overlay=new CGlobalProofOverlay();
    final CTransactionProofVerifierTest user=new CTransactionProofVerifierTest();
    final CTransactionRiskProofVerifierTest risk=new CTransactionRiskProofVerifierTest();
    final CVaultTransactionProofVerifierTest vault=new CVaultTransactionProofVerifierTest();
    final CControllerControlProofVerifierTest control=new CControllerControlProofVerifierTest();
    final CTokenTransactionProofVerifierTest token=new CTokenTransactionProofVerifierTest();
    final CAbiCodec abi=new CAbiCodec();final CEip712Verifier crypto=new CEip712Verifier();
    Root root(CTransactionProofVerifierTest.Seed seed){return new Root(h(900).substring(2),SYMBOL,n(1000),seed.vault(),seed.token());}
    Root root(){return root(user.seed(2000));}
    Step token(CTokenTransactionProofModels.Input i){return new Step(i.envelope(),new TokenWitness(i.context().allowance()));}
    Step trade(CTransactionProofModels.Input i){var c=i.context();return new Step(i.envelope(),new TradingWitness(c.authorizationPrestate(),c.ownerCode(),c.rulesKeys(),c.operationUsed(),c.activePosition()));}
    Step cash(CVaultTransactionProofModels.Input i){return new Step(i.envelope(),new VaultWitness(i.context().allowance()));}
    Step control(CControllerControlProofModels.Input i){return new Step(i.envelope(),new ControlWitness(i.context().nonce(),i.context().ownerCode()));}
    Step indexed(Step old,int index){
        var p=new CSignedTransactionVerifier().verify(old.envelope().rawSignedTransaction(),old.envelope().expectedTransactionHash(),n(31337));
        ECKeyPair key=p.sender().equals(OWNER)?USER:p.sender().equals(addr(ORACLE))?ORACLE:RELAY;
        byte[] raw=raw(p.to(),p.calldata(),key,n(100L+index));String hash=hex(Hash.sha3(raw));
        var receipt=old.envelope().receipt();List<CReceiptEnvelopeModels.RawLog> logs=new ArrayList<>();
        for(int at=0;at<receipt.logs().size();at++){var l=receipt.logs().get(at);logs.add(change(change(change(l,"transactionHash",hash),"transactionIndex",n(index)),"logIndex",n(index*100L+at)));}
        receipt=change(change(change(receipt,"transactionHash",hash),"transactionIndex",n(index)),"logs",logs);
        var e=change(change(change(old.envelope(),"rawSignedTransaction",hex(raw)),"expectedTransactionHash",hash),"receipt",receipt);
        var point=new CAuthorizationModels.ObservationPoint(receipt.blockNumber(),receipt.blockHash(),hash,n(index));Witness witness=old.witness();
        if(witness instanceof TokenWitness t)witness=new TokenWitness(t.allowance()==null?null:change(t.allowance(),"point",point));
        else if(witness instanceof TradingWitness t)witness=new TradingWitness(t.prestate()==null?null:change(t.prestate(),"point",point),t.ownerCode()==null?null:change(t.ownerCode(),"point",point),t.rulesKeys(),t.operationUsed(),t.activePosition());
        else if(witness instanceof VaultWitness v)witness=new VaultWitness(v.allowance()==null?null:change(v.allowance(),"point",point));
        else if(witness instanceof ControlWitness c)witness=new ControlWitness(c.nonce()==null?null:change(c.nonce(),"point",point),c.ownerCode()==null?null:change(c.ownerCode(),"point",point));
        return new Step(e,witness);
    }
    byte[] raw(String to,String data,ECKeyPair key,BigInteger nonce){var fields=new ArrayList<RlpType>(List.of(r(nonce),r(n(1)),r(n(1_000_000)),RlpString.create(bytes(to)),r(n(0)),RlpString.create(bytes(data))));var unsigned=new ArrayList<>(fields);unsigned.addAll(List.of(r(n(31337)),r(n(0)),r(n(0))));var sig=Sign.signMessage(Hash.sha3(RlpEncoder.encode(new RlpList(unsigned))),key,false);fields.add(r(n(31337*2L+35+sig.getV()[0]-27)));fields.add(r(new BigInteger(1,sig.getR())));fields.add(r(new BigInteger(1,sig.getS())));return RlpEncoder.encode(new RlpList(fields));}
    Result ok(Root root,List<Step> steps){Result result=overlay.verify(root,steps);assertEquals(Decision.VERIFIED_IN_SCOPE,result.decision(),result.reason());return result;}
    void deny(Result result){assertNotEquals(Decision.VERIFIED_IN_SCOPE,result.decision());assertNull(result.summary());assertNull(result.checkpoint());}
    Step approve(String spender,BigInteger value,int index){return indexed(token(token.fixture(user.seed(2000).token(),new CAbiModels.Approve(spender,value),USER,null)),index);}
    Step zero(int index){return indexed(token(token.transfer(0)),index);}
    Step cashAllowance(long amount,long observed,int index){var input=vault.cash(CAbiModels.VaultAction.DEPOSIT,amount);input=vault.context(input,"allowance",change(input.context().allowance(),"value",n(observed)));return indexed(cash(input),index);}
    Step open(int index){return indexed(trade(user.open(false)),index);}
    Step logStart(Step step,long first){var logs=new ArrayList<CReceiptEnvelopeModels.RawLog>();for(int i=0;i<step.envelope().receipt().logs().size();i++)logs.add(change(step.envelope().receipt().logs().get(i),"logIndex",n(first+i)));return change(step,"envelope",change(step.envelope(),"receipt",change(step.envelope().receipt(),"logs",logs)));}
    void globalLogDenied(List<Step> steps){var result=overlay.verify(root(),steps);assertEquals(Decision.REJECTED,result.decision(),result.reason());assertEquals("C_OVERLAY_GLOBAL_LOG_ORDER",result.reason());deny(result);}
    @Test void tokenThenFinancialCannotReverseGlobalBlockLogOrder(){globalLogDenied(List.of(logStart(zero(3),1000),open(4)));}
    @Test void financialThenTokenCannotReverseGlobalBlockLogOrder(){globalLogDenied(List.of(open(3),logStart(zero(4),302)));}
    @Test void financialThenTokenCannotDuplicateGlobalBlockLogIndex(){globalLogDenied(List.of(open(3),logStart(zero(4),303)));}
    @Test void emptyDomainObservationCannotResetGlobalLogHighWatermark(){Step noop=indexed(cash(vault.process(user.seed(2000),USER)),4);assertTrue(noop.envelope().receipt().logs().isEmpty());globalLogDenied(List.of(logStart(zero(3),1000),noop,open(5)));}
    @Test void revertedObservationCannotResetGlobalLogHighWatermark(){Step failed=open(4);failed=change(failed,"envelope",change(failed.envelope(),"receipt",change(change(failed.envelope().receipt(),"logs",List.of()),"status",0)));globalLogDenied(List.of(logStart(zero(3),1000),failed,logStart(zero(5),1000)));}
    @Test void rootFullVaultLogsAlsoSetInitialGlobalHighWatermark(){globalLogDenied(List.of(logStart(zero(3),4)));}
    @Test void increasingGlobalLogOrderAcrossBothDomainsAndEmptyObservationRemainsValid(){Step noop=indexed(cash(vault.process(user.opened(2000),USER)),5);ok(root(),List.of(zero(3),open(4),noop,zero(6)));}
    @Test void emptyTranscriptIsBoundedRootAssertionNotGlobalAudit(){var r=ok(root(),List.of());assertEquals(0,r.summary().steps());assertEquals(n(2),r.summary().globalPoint().transactionIndex());assertFalse(r.summary().completeCoverage());assertFalse(r.summary().globalCoverage());assertFalse(r.summary().canonicalVerified());assertFalse(r.summary().persistentRestartVerified());}
    @Test void approvalGapThenOpenUsesOriginalVerifiersAndDistinctCursors(){var first=ok(root(),List.of(approve(VAULT,n(100),3)));assertEquals(n(3),first.summary().globalPoint().transactionIndex());assertEquals(n(2),first.summary().financialPoint().transactionIndex());var r=overlay.append(root(),first.checkpoint(),open(4));assertEquals(Decision.VERIFIED_IN_SCOPE,r.decision(),r.reason());assertEquals(n(4),r.summary().globalPoint().transactionIndex());assertEquals(n(4),r.summary().financialPoint().transactionIndex());assertEquals(0,r.summary().pendingTokenGap());}
    @Test void zeroAndSelfGapsThenTopUpKeepExistingRulesAndLock(){var s=user.opened(2000);Root root=root(s);Step self=indexed(token(token.fixture(s.token(),new CAbiModels.Transfer(OWNER,n(1)),USER,null)),5);Step top=indexed(trade(user.fixture(s,1,PRICE,1500,false)),6);var r=ok(root,List.of(indexed(token(token.fixture(s.token(),new CAbiModels.Transfer(addr(RELAY),n(0)),USER,null)),4),self,top));assertEquals(2,r.summary().tokenSteps());assertEquals(1,r.summary().financialSteps());assertEquals(n(6),r.summary().financialPoint().transactionIndex());}
    @Test void gapThenActualRiskVerifierPreservesKeeperBoundary(){var s=user.opened(2000);var start=risk.fixture(s,CAbiModels.RiskAction.START_CALL,risk.adverse(false),1000);var r=ok(root(s),List.of(indexed(token(token.fixture(s.token(),new CAbiModels.Transfer(addr(RELAY),n(0)),USER,null)),4),indexed(trade(start),5)));assertEquals(1,r.summary().financialSteps());}
    @Test void gapThenCancelUsesActualControllerCodeNonceVerifier(){var s=control.rebind(user.seed(2000));var r=ok(root(s),List.of(zero(3),indexed(control(control.fixture(s,new CAbiModels.Cancel(n(0)),USER)),4)));assertEquals(n(4),r.summary().financialPoint().transactionIndex());}
    @Test void gapThenCashChecksActualAllowanceAndTokenMovement(){var r=ok(root(),List.of(approve(VAULT,n(100),3),cashAllowance(60,100,4)));assertEquals(1,r.summary().financialSteps());assertEquals(0,r.summary().pendingTokenGap());}
    @Test void manyAppendsReplayEntirePrivateHistory(){var a=ok(root(),List.of(zero(3)));var b=overlay.append(root(),a.checkpoint(),open(4));assertEquals(Decision.VERIFIED_IN_SCOPE,b.decision(),b.reason());var c=overlay.append(root(),b.checkpoint(),zero(5));assertEquals(Decision.VERIFIED_IN_SCOPE,c.decision(),c.reason());assertEquals(3,c.summary().steps());assertEquals(2,c.summary().tokenSteps());assertEquals(1,c.summary().financialSteps());assertEquals(n(4),c.summary().financialPoint().transactionIndex());assertEquals(n(5),c.summary().globalPoint().transactionIndex());assertEquals(1,c.summary().pendingTokenGap());}
    @Test void changedConfiguredSourceOrRootCannotReuseCheckpoint(){var cp=ok(root(),List.of(zero(3))).checkpoint();deny(overlay.append(change(root(),"sourceIdentity",h(901).substring(2)),cp,open(4)));deny(overlay.append(change(root(),"blockTimestamp",n(1001)),cp,open(4)));var changed=change(root().vault(),"paused",true);deny(overlay.append(change(root(),"vault",changed),cp,open(4)));}
    @Test void rootDeploymentGenesisAndStartingPointMustAgree(){Root r=root();deny(overlay.verify(change(r,"token",change(r.token(),"deployment",change(r.token().deployment(),"genesisHash",h(99)))),List.of()));deny(overlay.verify(change(r,"token",change(r.token(),"lastTransactionIndex",n(1))),List.of()));deny(overlay.verify(change(r,"vault",change(r.vault(),"actualAssets",n(5001))),List.of()));}
    @Test void stepMustBindEveryDeclaredContractAndChainToRoot(){Step step=zero(3);for(String field:List.of("token","vault","controller","rules")){var e=change(step.envelope(),"deployment",change(step.envelope().deployment(),field,a(99)));deny(overlay.verify(root(),List.of(change(step,"envelope",e))));}}
    @Test void missingOriginalStepCannotBeHiddenByAdvancingIndex(){var r=overlay.verify(root(),List.of(zero(4)));assertEquals(Decision.INSUFFICIENT,r.decision());assertEquals("C_OVERLAY_TRANSACTION_GAP",r.reason());deny(r);}
    @Test void reversedOrderAndDuplicateFinancialStepAreRejected(){deny(overlay.verify(root(),List.of(zero(4),zero(3))));Step first=open(3);deny(overlay.verify(root(),List.of(first,indexed(first,4))));}
    @Test void identicalRawHashAtAnotherPointIsRejectedWithoutReexecution(){Step first=zero(3);var e=first.envelope();var receipt=change(e.receipt(),"transactionIndex",n(4));var logs=new ArrayList<CReceiptEnvelopeModels.RawLog>();for(var l:receipt.logs())logs.add(change(change(l,"transactionIndex",n(4)),"logIndex",n(400)));receipt=change(receipt,"logs",logs);Step duplicate=new Step(change(e,"receipt",receipt),first.witness());var r=overlay.verify(root(),List.of(first,duplicate));assertEquals("C_OVERLAY_DUPLICATE_RAW_HASH",r.reason());deny(r);}
    @Test void sameBlockTimestampIsFixedAssertionAndCrossBlockUnsupported(){Step step=zero(3);deny(overlay.verify(root(),List.of(change(step,"envelope",change(step.envelope(),"block",change(step.envelope().block(),"timestamp",n(1001)))))));var e=step.envelope();var receipt=change(e.receipt(),"blockNumber",n(2));var logs=receipt.logs().stream().map(l->change(l,"blockNumber",n(2))).toList();receipt=change(receipt,"logs",logs);e=change(change(e,"receipt",receipt),"block",change(e.block(),"number",n(2)));var r=overlay.verify(root(),List.of(new Step(e,step.witness())));assertEquals(Decision.UNSUPPORTED,r.decision());deny(r);}
    @Test void wrongWitnessTypeCannotMakeCashATokenGap(){Step step=zero(3);deny(overlay.verify(root(),List.of(new Step(step.envelope(),new VaultWitness(null)))));deny(overlay.verify(root(),List.of(new Step(step.envelope(),null))));}
    @Test void mutableReceiptAndTopicInputsAreDeepSnapshotted(){Step original=zero(3);var logs=new ArrayList<>(original.envelope().receipt().logs());var topics=new ArrayList<>(logs.getFirst().topics());logs.set(0,change(logs.getFirst(),"topics",topics));var receipt=change(original.envelope().receipt(),"logs",logs);Step mutable=new Step(change(original.envelope(),"receipt",receipt),original.witness());var input=new ArrayList<>(List.of(mutable));var a=ok(root(),input);topics.clear();logs.clear();input.clear();var b=overlay.append(root(),a.checkpoint(),open(4));assertEquals(Decision.VERIFIED_IN_SCOPE,b.decision(),b.reason());assertEquals(2,b.summary().steps());}
    @Test void failedAppendReturnsNoPartialCheckpointAndOldOneRemainsUsable(){var a=ok(root(),List.of(approve(VAULT,n(100),3)));deny(overlay.append(root(),a.checkpoint(),cashAllowance(500,9999,4)));var good=overlay.append(root(),a.checkpoint(),cashAllowance(60,100,4));assertEquals(Decision.VERIFIED_IN_SCOPE,good.decision(),good.reason());assertEquals(1,a.summary().steps());}
    @Test void checkpointHasNoPublicConstructorStateGetterOrSerialization(){assertEquals(0,Checkpoint.class.getConstructors().length);assertFalse(Serializable.class.isAssignableFrom(Checkpoint.class));for(Method method:Checkpoint.class.getDeclaredMethods())if(Modifier.isPublic(method.getModifiers()))assertEquals(String.class,method.getReturnType());for(RecordComponent field:Step.class.getRecordComponents())assertFalse(field.getType().getSimpleName().equals("State"));for(Class<?> type:Witness.class.getPermittedSubclasses())for(RecordComponent field:type.getRecordComponents())assertFalse(field.getType().getSimpleName().equals("State"));}
    @Test void summariesAndCheckpointStringsDoNotExposeOriginalRaw(){Step step=zero(3);var r=ok(root(),List.of(step));for(String text:List.of(step.toString(),r.toString(),r.summary().toString(),r.checkpoint().toString()))assertFalse(text.contains(step.envelope().rawSignedTransaction()));}
    @Test void canonicalRootHashDoesNotDependOnMapIterationOrder(){Root root=root();var map=new LinkedHashMap<String,CCustodyModels.Balance>();root.vault().balances().entrySet().stream().sorted(Map.Entry.<String,CCustodyModels.Balance>comparingByKey().reversed()).forEach(e->map.put(e.getKey(),e.getValue()));Root reordered=change(root,"vault",change(root.vault(),"balances",map));assertEquals(ok(root,List.of()).summary().rootHash(),ok(reordered,List.of()).summary().rootHash());}
    @Test void pendingTokenGapLimitAndTotalTranscriptLimitAreNotResetByAppend(){List<Step> steps=new ArrayList<>();for(int index=3;index<68;index++)steps.add(zero(index));var result=overlay.verify(root(),steps);assertEquals(Decision.INSUFFICIENT,result.decision());assertEquals("C_OVERLAY_PENDING_GAP_LIMIT",result.reason());deny(result);deny(overlay.verify(root(),Collections.nCopies(129,zero(3))));}
    @Test void oversizedWitnessAndMutableLogCountAreRejectedBeforeRetention(){Step step=open(3);var witness=(TradingWitness)step.witness();var huge=change(witness.ownerCode(),"code","X".repeat(MAX_RETAINED_BYTES/2));deny(overlay.verify(root(),List.of(new Step(step.envelope(),change(witness,"ownerCode",huge)))));var e=step.envelope();var receipt=change(e.receipt(),"logs",Collections.nCopies(129,e.receipt().logs().getFirst()));assertEquals(Decision.INSUFFICIENT,overlay.verify(root(),List.of(change(step,"envelope",change(e,"receipt",receipt)))).decision());}
    @Test void positiveVaultAssetDeltaNeverBecomesAnInvisibleGap(){Step step=indexed(token(token.fixture(user.seed(2000).token(),new CAbiModels.Transfer(VAULT,n(1)),USER,null)),3);var r=overlay.verify(root(),List.of(step));assertEquals(Decision.UNSUPPORTED,r.decision());deny(r);}
    @Test void rawEthereumNoncesAreDistinctFromControllerSignatureNonce(){Step a=zero(3),b=open(4);var raw=new CSignedTransactionVerifier();assertEquals(n(103),raw.verify(a.envelope().rawSignedTransaction(),a.envelope().expectedTransactionHash(),n(31337)).nonce());assertEquals(n(104),raw.verify(b.envelope().rawSignedTransaction(),b.envelope().expectedTransactionHash(),n(31337)).nonce());assertEquals(n(0),((CAbiModels.Direct)abi.decode(CAbiModels.Target.CONTROLLER,raw.verify(b.envelope().rawSignedTransaction(),b.envelope().expectedTransactionHash(),n(31337)).calldata())).intent().nonce());ok(root(),List.of(a,b));}
    @Test void verifiedOpenFullRulesCannotBeReplacedByLaterAssertion(){Step top=indexed(trade(user.fixture(user.opened(2000),1,PRICE,1500,false)),4);var witness=(TradingWitness)top.witness();var position=witness.prestate().rulesPosition();for(RecordComponent field:position.getClass().getRecordComponents()){Object value=field(position,field.getName());Object changed=value instanceof BigInteger b?b.add(n(1)):value instanceof Enum<?>?CRulesTransitionModels.Status.MARGIN_CALL:h(99);var next=change(witness,"prestate",change(witness.prestate(),"rulesPosition",change(position,field.getName(),changed)));var r=overlay.verify(root(),List.of(open(3),new Step(top.envelope(),next)));assertEquals("C_OVERLAY_RULES_TRANSCRIPT_CONFLICT",r.reason(),field.getName());deny(r);}}
    @Test void otherwiseLocallyValidOldObservedTimestampIsRejectedAfterDerivedOpen(){var input=user.fixture(user.opened(2000),1,PRICE,1500,false);input=user.context(input,"authorizationPrestate",change(input.context().authorizationPrestate(),"rulesPosition",change(input.context().authorizationPrestate().rulesPosition(),"lastObservedAt",n(999))));user.ok(input);Step top=indexed(trade(input),4);var r=overlay.verify(root(),List.of(open(3),top));assertEquals("C_OVERLAY_RULES_TRANSCRIPT_CONFLICT",r.reason());deny(r);}
    @Test void approvedAllowanceCannotBeInflatedForCashDeposit(){var r=overlay.verify(root(),List.of(approve(VAULT,n(100),3),cashAllowance(500,9999,4)));assertEquals("C_OVERLAY_ALLOWANCE_TRANSCRIPT_CONFLICT",r.reason());deny(r);}
    @Test void finiteCashSpendIsCarriedIntoNextAllowanceAssertion(){deny(overlay.verify(root(),List.of(approve(VAULT,n(100),3),cashAllowance(60,100,4),cashAllowance(20,100,5))));ok(root(),List.of(approve(VAULT,n(100),3),cashAllowance(60,100,4),cashAllowance(20,40,5)));}
    @Test void transferFromAllowanceIsCarriedEvenWhenOwnerEqualsCaller(){Step approval=approve(OWNER,n(100),3);Step spend=indexed(token(token.from(OWNER,addr(RELAY),60,USER,n(100))),4);Step stale=indexed(token(token.from(OWNER,addr(RELAY),20,USER,n(100))),5);var r=overlay.verify(root(),List.of(approval,spend,stale));assertEquals("C_OVERLAY_ALLOWANCE_TRANSCRIPT_CONFLICT",r.reason());deny(r);Step correct=indexed(token(token.from(OWNER,addr(RELAY),20,USER,n(40))),5);ok(root(),List.of(approval,spend,correct));}
    @Test void infiniteAllowanceIsNotDecreasedBySpends(){Step approval=approve(addr(ORACLE),CAbiModels.UINT_MAX,3);Step a=indexed(token(token.from(OWNER,addr(RELAY),1,ORACLE,CAbiModels.UINT_MAX)),4);Step b=indexed(token(token.from(OWNER,addr(RELAY),2,ORACLE,CAbiModels.UINT_MAX)),5);ok(root(),List.of(approval,a,b));}
    @Test void firstUnknownAllowanceCanBeAssertedButNotContradictedAfterward(){Step first=indexed(token(token.from(OWNER,addr(RELAY),10,ORACLE,n(20))),3);Step second=indexed(token(token.from(OWNER,addr(RELAY),1,ORACLE,n(20))),4);var r=overlay.verify(root(),List.of(first,second));assertEquals("C_OVERLAY_ALLOWANCE_TRANSCRIPT_CONFLICT",r.reason());deny(r);}
    @Test void equivalentFullTranscriptReplayProducesSameSummaryWithoutExposingState(){var steps=List.of(approve(VAULT,n(100),3),cashAllowance(60,100,4),zero(5));var a=ok(root(),steps);var b=ok(root(),steps);assertEquals(a.summary(),b.summary());assertNotSame(a.checkpoint(),b.checkpoint());assertTrue(a.summary().verificationScope().contains("MEMORY_ONLY"));}
    @Test void noopAndRevertedDomainCountsAreNotSuccessfulFinancialExecutionCounts(){Step noop=indexed(cash(vault.process(user.seed(2000),USER)),3);Step failed=open(4);var receipt=change(change(failed.envelope().receipt(),"logs",List.of()),"status",0);failed=change(failed,"envelope",change(failed.envelope(),"receipt",receipt));var r=ok(root(),List.of(noop,failed));assertEquals(2,r.summary().financialSteps());assertEquals(n(4),r.summary().financialPoint().transactionIndex());assertEquals(2,r.summary().steps());}
    @Test void malformedLongRootKeysAreRejectedBeforeCanonicalSorting(){Root r=root();var bad=new HashMap<String,CCustodyModels.Balance>();for(int i=0;i<128;i++)bad.put("X".repeat(8192)+i,new CCustodyModels.Balance(n(0),n(0)));bad.put(OWNER,new CCustodyModels.Balance(n(3000),n(0)));var invalid=change(r,"vault",change(r.vault(),"balances",bad));var result=overlay.verify(invalid,List.of());assertEquals(Decision.REJECTED,result.decision());assertEquals("C_OVERLAY_CONTEXT_OR_REPLAY",result.reason());deny(result);}
    @Test void malformedUnicodeCannotAliasDifferentRetainedWitnesses(){
        var seed=user.opened(2000);
        Step start=indexed(trade(risk.fixture(seed,CAbiModels.RiskAction.START_CALL,risk.adverse(false),1000)),4);
        var witness=(TradingWitness)start.witness();
        for(String invalid:List.of(String.valueOf((char)0xd800),String.valueOf((char)0xdc00))){
            var code=new CAuthorizationModels.CodeObservation(witness.prestate().point(),OWNER,invalid);
            Result result=overlay.verify(root(seed),List.of(new Step(start.envelope(),change(witness,"ownerCode",code))));
            assertEquals(Decision.REJECTED,result.decision());
            deny(result);
        }
    }
    @Test void validUnicodeRemainsAcceptedAndDistinctRatherThanBeingNormalizedToReplacement(){
        // Demonstrates the JDK default-conversion boundary, not an executed whole-transcript hash collision.
        assertArrayEquals(String.valueOf((char)0xd800).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            String.valueOf((char)0xdc00).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var seed=user.opened(2000);
        Step start=indexed(trade(risk.fixture(seed,CAbiModels.RiskAction.START_CALL,risk.adverse(false),1000)),4);
        var witness=(TradingWitness)start.witness();Set<String> hashes=new HashSet<>();
        for(String valid:List.of("청산 근거","청산 근거2",new String(Character.toChars(0x1f600)))){
            var code=new CAuthorizationModels.CodeObservation(witness.prestate().point(),OWNER,valid);
            var result=ok(root(seed),List.of(new Step(start.envelope(),change(witness,"ownerCode",code))));
            hashes.add(result.summary().transcriptHash());
        }
        assertEquals(3,hashes.size());
    }
}
