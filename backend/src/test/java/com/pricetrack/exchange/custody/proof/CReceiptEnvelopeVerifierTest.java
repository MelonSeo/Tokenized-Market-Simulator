package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CReceiptEnvelopeModels.*;
import static org.junit.jupiter.api.Assertions.*;
import com.pricetrack.exchange.custody.abi.CAbiCodec;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventCodec;
import com.pricetrack.exchange.custody.abi.CAbiEventModels;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.rlp.*;

/** Offline signed fixtures use the PUBLIC unit-test integer 17, not user/Anvil credentials. */
public class CReceiptEnvelopeVerifierTest {
    static final ECKeyPair TEST=ECKeyPair.create(BigInteger.valueOf(17));
    static final String SENDER="0x"+Keys.getAddress(TEST.getPublicKey());
    static final String TOKEN=address(1),VAULT=address(2),CONTROLLER=address(3),RULES=address(4);
    static final String ZERO="0x"+"0".repeat(40),BLOCK_HASH=hash(10);
    static final Deployment DEPLOYMENT=new Deployment(bi(31337),TOKEN,VAULT,CONTROLLER,RULES);
    static final BlockContext BLOCK=new BlockContext(bi(10),BLOCK_HASH,bi(1000));
    static final HexFormat HEX=HexFormat.of();
    final CReceiptEnvelopeVerifier verifier=new CReceiptEnvelopeVerifier();
    final CAbiCodec calls=new CAbiCodec();final CAbiEventCodec events=new CAbiEventCodec();

    @Test void legacySignedDepositAndTwoExactEmittersLinkWithoutAccountingClaim() {
        Input input=deposit(false);Result result=verifier.verify(input);
        assertEquals(Decision.LINKED_IN_SCOPE,result.decision());assertEquals(SENDER,result.linked().transaction().sender());
        assertEquals(2,result.linked().logs().size());assertEquals(VAULT,result.linked().transaction().to());
        assertEquals(new CAbiEventModels.Deposited(SENDER,bi(100)),result.linked().logs().get(1).event());
        assertFalse(result.completeCoverage());assertTrue(result.verificationScope().contains("PREVALIDATED_BLOCK_DEPLOYMENT_CONTEXT_ONLY"));
    }
    @Test void typeTwoUsesTheSameRealInternalRawVerifier() {
        Result result=verifier.verify(deposit(true));assertEquals(Decision.LINKED_IN_SCOPE,result.decision());
        assertEquals(CSignedTransactionVerifier.Type.EIP1559,result.linked().transaction().type());
        assertEquals(SENDER,result.linked().receipt().from());assertEquals(bi(7),result.linked().transaction().nonce());
    }
    @Test void controllerCallAndFullEventPayloadRemainDecoded() {
        Input input=fixture(CONTROLLER,calls.encode(new CAbiModels.Cancel(bi(9))),false,BigInteger.ZERO,
            List.of(event(CONTROLLER,new CAbiEventModels.CNonceCancelled(SENDER,bi(9)))));
        Result result=verifier.verify(input);assertEquals(Decision.LINKED_IN_SCOPE,result.decision());
        assertEquals(new CAbiModels.Cancel(bi(9)),result.linked().call());
        assertEquals(new CAbiEventModels.CNonceCancelled(SENDER,bi(9)),result.linked().logs().getFirst().event());
    }
    @Test void rulesAndControllerCategoriesRetainAllFullEventFields() {
        var rule=new CAbiEventModels.OperationApplied(hash(1),hash(2),bi(5),bi(3),bi(8_000_000_000L),bi(1000),bi(-2000),bi(-1000),bi(1000),bi(1),bi(1060));
        var consumed=new CAbiEventModels.FuturesReportConsumed(hash(1),bi(1060),bi(1090));
        var risk=new CAbiEventModels.CRiskExecuted(hash(1),hash(2),SENDER,hash(3),bi(5));
        // Event economic correspondence to this cancel calldata is intentionally NOT verified by this unit.
        Input input=fixture(CONTROLLER,calls.encode(new CAbiModels.Cancel(bi(0))),true,BigInteger.ZERO,
            List.of(event(RULES,rule),event(RULES,consumed),event(CONTROLLER,risk)));
        Result result=verifier.verify(input);assertEquals(Decision.LINKED_IN_SCOPE,result.decision());
        assertEquals(List.of(rule,consumed,risk),result.linked().logs().stream().map(DecodedLog::event).toList());
    }
    @Test void zeroTokenTransferAndApprovalSyntaxArePreservedNotConvertedToFakeAccountingCalls() {
        var transfer=new CAbiEventModels.Transfer(ZERO,VAULT,BigInteger.ZERO);
        var approval=new CAbiEventModels.Approval(SENDER,VAULT,BigInteger.ZERO);
        Input input=fixture(TOKEN,calls.encode(new CAbiModels.Approve(VAULT,BigInteger.ZERO)),false,BigInteger.ZERO,
            List.of(event(TOKEN,transfer),event(TOKEN,approval)));
        Result result=verifier.verify(input);assertEquals(Decision.LINKED_IN_SCOPE,result.decision());
        assertInstanceOf(CAbiModels.Approve.class,result.linked().call());
        assertEquals(transfer,result.linked().logs().getFirst().event());assertEquals(approval,result.linked().logs().get(1).event());
    }
    @Test void originalRawHashIsReverifiedAndCannotReuseExpectedIdentityAfterTampering() {
        Input input=deposit(false);String raw=input.rawSignedTransaction();
        String changed=raw.substring(0,raw.length()-2)+(raw.endsWith("00")?"01":"00");
        assertDecision(Decision.REJECTED,change(input,"rawSignedTransaction",changed));
        assertDecision(Decision.REJECTED,change(input,"expectedTransactionHash",hash(999)));
        assertFalse(Arrays.stream(Input.class.getRecordComponents()).anyMatch(f->f.getType()==CSignedTransactionVerifier.Proof.class));
    }
    @Test void receiptHashMustMatchTheAuthenticatedRawHash() {
        Input input=deposit(false);
        Result result=verifier.verify(change(input,"receipt",change(input.receipt(),"transactionHash",hash(999))));
        assertEquals(Decision.REJECTED,result.decision());assertEquals("C_ENV_TX_HASH_CONFLICT",result.reason());
    }
    @Test void receiptFromAndToAreComparedToRecoveredSenderAndSignedTarget() {
        Input input=deposit(false);
        for(String field:List.of("from","to")) {
            Receipt changed=change(input.receipt(),field,address(999));
            assertDecision(Decision.REJECTED,change(input,"receipt",changed));
        }
    }
    @Test void signedChainCannotBeReinterpretedByAnotherDeploymentContext() {
        Input input=deposit(true);Deployment other=change(DEPLOYMENT,"chainId",bi(31338));
        assertDecision(Decision.REJECTED,change(input,"deployment",other));
    }
    @Test void receiptBlockHashAndNumberMustMatchSuppliedBlockContext() {
        Input input=deposit(false);
        for(String field:List.of("blockHash","blockNumber")) {
            Object wrong=field.equals("blockHash")?hash(999):bi(999);
            assertDecision(Decision.REJECTED,change(input,"receipt",change(input.receipt(),field,wrong)));
        }
    }
    @Test void everyLogMustMatchReceiptTransactionAndBlockMetadata() {
        Input input=deposit(false);RawLog log=input.receipt().logs().getFirst();
        for(String field:List.of("transactionHash","blockNumber","blockHash","transactionIndex")) {
            Object wrong=field.endsWith("Hash")?hash(999):bi(999);
            assertDecision(Decision.REJECTED,withLogs(input,List.of(change(log,field,wrong),input.receipt().logs().get(1))));
        }
    }
    @Test void removedDuplicateOrReversedLogIndicesAreRejected() {
        Input input=deposit(false);RawLog first=input.receipt().logs().getFirst(),last=input.receipt().logs().get(1);
        assertDecision(Decision.REJECTED,withLogs(input,List.of(change(first,"removed",true),last)));
        assertDecision(Decision.REJECTED,withLogs(input,List.of(first,change(last,"logIndex",first.logIndex()))));
        assertDecision(Decision.REJECTED,withLogs(input,List.of(last,first)));
    }
    @Test void canonicalShapedStatusZeroHasNoLogEffectsAndNoInventedRevertCause() {
        Input input=deposit(false);input=withLogs(input,List.of());input=change(input,"receipt",change(input.receipt(),"status",0));
        Result result=verifier.verify(input);assertEquals(Decision.LINKED_IN_SCOPE,result.decision());
        assertTrue(result.linked().logs().isEmpty());assertEquals(0,result.linked().receipt().status());
        assertEquals("C_ENV_REVERTED_NO_LOGS_NO_CAUSE_INFERENCE",result.reason());assertFalse(result.completeCoverage());
    }
    @Test void statusZeroWithLogsAndInvalidStatusAreConcreteConflicts() {
        Input input=deposit(false);
        assertDecision(Decision.REJECTED,change(input,"receipt",change(input.receipt(),"status",0)));
        assertDecision(Decision.REJECTED,change(input,"receipt",change(input.receipt(),"status",2)));
    }
    @Test void allSupportedNonpayableTargetsRejectSignedValue() {
        for(String target:List.of(TOKEN,VAULT,CONTROLLER)) {
            CAbiModels.Call call=target.equals(TOKEN)?new CAbiModels.Approve(VAULT,bi(1)):
                target.equals(VAULT)?new CAbiModels.VaultAmount(CAbiModels.VaultAction.DEPOSIT,bi(1)):new CAbiModels.Cancel(bi(1));
            Input input=fixture(target,calls.encode(call),false,BigInteger.ONE,List.of());
            assertEquals("C_ENV_NONPAYABLE_VALUE",verifier.verify(input).reason());
        }
    }
    @Test void unknownPayableTargetsAndUnsupportedSelectorsAreNotNonpayableContradictions() {
        for(boolean type2:List.of(false,true)) {
            Input unknown=fixture(address(999),calls.encode(new CAbiModels.VaultAmount(CAbiModels.VaultAction.DEPOSIT,bi(100))),
                type2,BigInteger.ONE,List.of());
            Result result=verifier.verify(unknown);
            assertEquals(Decision.UNSUPPORTED,result.decision());
            assertEquals("C_ENV_CALL_TARGET",result.reason());assertNull(result.linked());
        }
        for(String target:List.of(TOKEN,VAULT,CONTROLLER)) {
            Result result=verifier.verify(fixture(target,"0xdeadbeef",false,BigInteger.ONE,List.of()));
            assertEquals(Decision.UNSUPPORTED,result.decision());
            assertEquals("C_ENV_CALL_SELECTOR",result.reason());assertNull(result.linked());
        }
    }
    @Test void revertedNonpayableValueCallsAreObservedWithoutInventingARevertCause() {
        for(String target:List.of(TOKEN,VAULT,CONTROLLER)) {
            CAbiModels.Call call=target.equals(TOKEN)?new CAbiModels.Approve(VAULT,bi(1)):
                target.equals(VAULT)?new CAbiModels.VaultAmount(CAbiModels.VaultAction.DEPOSIT,bi(1)):new CAbiModels.Cancel(bi(1));
            Input input=fixture(target,calls.encode(call),false,BigInteger.ONE,List.of());
            input=change(input,"receipt",change(input.receipt(),"status",0));
            Result result=verifier.verify(input);
            assertEquals(Decision.LINKED_IN_SCOPE,result.decision());
            assertEquals("C_ENV_REVERTED_NO_LOGS_NO_CAUSE_INFERENCE",result.reason());
            assertEquals(BigInteger.ONE,result.linked().transaction().value());
            assertEquals(0,result.linked().receipt().status());assertTrue(result.linked().logs().isEmpty());
            // The same failed Receipt cannot claim event effects even with matching metadata.
            CAbiEventModels.Log emitted=events.encode(TOKEN,new CAbiEventModels.Transfer(SENDER,VAULT,bi(1)));
            RawLog log=new RawLog(input.expectedTransactionHash(),BLOCK.number(),BLOCK.hash(),bi(2),bi(5),false,emitted.emitter(),emitted.topics(),emitted.data());
            assertEquals("C_ENV_REVERTED_LOGS",verifier.verify(withLogs(input,List.of(log))).reason());
        }
    }
    @Test void unsupportedRawTypeAndContractCreationRemainUnsupportedNotFinancialFailures() {
        Input base=deposit(false);
        Input typed=change(change(base,"rawSignedTransaction","0x01c0"),"expectedTransactionHash",digest(bytes("0x01c0")));
        assertDecision(Decision.UNSUPPORTED,typed);
        Input creation=fixture("0x","0x",false,BigInteger.ZERO,List.of());
        assertEquals("C_ENV_CONTRACT_CREATION",verifier.verify(creation).reason());
    }
    @Test void unknownSelectorTargetEmitterOrTopicIsNeverSilentlyDropped() {
        assertDecision(Decision.UNSUPPORTED,fixture(VAULT,"0xdeadbeef",false,BigInteger.ZERO,List.of()));
        assertDecision(Decision.UNSUPPORTED,fixture(address(999),calls.encode(new CAbiModels.Cancel(bi(1))),false,BigInteger.ZERO,List.of()));
        Input input=deposit(false);RawLog first=input.receipt().logs().getFirst();
        assertDecision(Decision.UNSUPPORTED,withLogs(input,List.of(change(first,"emitter",address(999)))));
        List<String> topics=new ArrayList<>(first.topics());topics.set(0,hash(999));
        assertDecision(Decision.UNSUPPORTED,withLogs(input,List.of(change(first,"topics",topics))));
    }
    @Test void missingRequiredTransportFieldsAreInsufficientNotAssumedDefaults() {
        Input input=deposit(false);
        for(String field:List.of("rawSignedTransaction","expectedTransactionHash","deployment","block","receipt"))assertDecision(Decision.INSUFFICIENT,change(input,field,null));
        for(String field:List.of("transactionHash","blockNumber","blockHash","transactionIndex","from","to","status","logs"))
            assertDecision(Decision.INSUFFICIENT,change(input,"receipt",change(input.receipt(),field,null)));
        RawLog log=input.receipt().logs().getFirst();
        for(String field:List.of("transactionHash","blockNumber","blockHash","transactionIndex","logIndex","removed","emitter","topics","data"))
            assertDecision(Decision.INSUFFICIENT,withLogs(input,List.of(change(log,field,null))));
        assertDecision(Decision.INSUFFICIENT,change(input,"deployment",change(DEPLOYMENT,"token",VAULT)));
    }
    @Test void countAndAggregateDataCapsAreCheckedBeforeEvenMalformedRawCrypto() {
        Input base=change(deposit(false),"rawSignedTransaction","0xBAD_RAW_SENTINEL");
        RawLog log=base.receipt().logs().getFirst();
        assertEquals("C_ENV_LOG_COUNT_LIMIT",verifier.verify(withLogs(base,Collections.nCopies(129,log))).reason());
        assertEquals("C_ENV_LOG_DATA_LIMIT",verifier.verify(withLogs(base,List.of(change(log,"data","0x"+"00".repeat(65_537))))).reason());
        assertEquals("C_ENV_LOG_TOPIC_LIMIT",verifier.verify(withLogs(base,List.of(change(log,"topics",Collections.nCopies(5,hash(1)))))).reason());
        assertEquals("C_ENV_RAW_LIMIT",verifier.verify(change(base,"rawSignedTransaction","0x"+"00".repeat(65_537))).reason());
    }
    @Test void malformedAbiAndKnownEventAtWrongCEmitterAreRejected() {
        Input input=deposit(false);RawLog transfer=input.receipt().logs().getFirst();
        assertDecision(Decision.REJECTED,withLogs(input,List.of(change(transfer,"emitter",VAULT))));
        assertDecision(Decision.REJECTED,withLogs(input,List.of(change(transfer,"data","0xgg"))));
        List<String> topics=new ArrayList<>(transfer.topics());topics.set(1,"0x01"+topics.get(1).substring(4));
        assertDecision(Decision.REJECTED,withLogs(input,List.of(change(transfer,"topics",topics))));
        assertDecision(Decision.UNSUPPORTED,fixture(VAULT,"0xb6b55f25",false,BigInteger.ZERO,List.of()));
    }
    @Test void strictCallAbiSubsetFailureDoesNotClaimAnEvmContradiction() {
        String canonical=calls.encode(new CAbiModels.VaultAmount(CAbiModels.VaultAction.DEPOSIT,bi(1)));
        Result trailing=verifier.verify(fixture(VAULT,canonical+"00".repeat(32),false,BigInteger.ZERO,List.of()));
        assertEquals(Decision.UNSUPPORTED,trailing.decision());assertEquals("C_ENV_CALL_ABI_SUBSET",trailing.reason());
        // Nonzero high address padding is outside this strict codec's supported subset too.
        String approve=calls.encode(new CAbiModels.Approve(VAULT,bi(1)));
        String padded=approve.substring(0,10)+"01"+approve.substring(12);
        Result padding=verifier.verify(fixture(TOKEN,padded,false,BigInteger.ZERO,List.of()));
        assertEquals(Decision.UNSUPPORTED,padding.decision());assertEquals("C_ENV_CALL_ABI_SUBSET",padding.reason());
    }
    @Test void linkedDataIsImmutableAndSafeStringsDoNotExposeRawOrCallSignatures() {
        String sentinel="0xdeadbeefcafef00d1122334455667788";
        CAbiModels.Report report=new CAbiModels.Report(hash(1),hash(2),hash(3),hash(4),bi(0),bi(0),bi(0),bi(0),bi(10),bi(100),bi(10_000_000_000L),bi(0),bi(1000),bi(10_000_000_000L),bi(1000),bi(1030),hash(5),CONTROLLER);
        CAbiModels.Intent intent=new CAbiModels.Intent(SENDER,address(9),hash(1),hash(2),hash(3),hash(4),bi(0),bi(0),bi(0),bi(0),bi(10),bi(0),bi(0),bi(1000),bi(10_000_000_000L),bi(10_000_000_000L),bi(100),bi(100),bi(0),bi(1000),bi(1120),hash(5));
        Input input=fixture(CONTROLLER,calls.encode(new CAbiModels.Direct(report,sentinel,intent)),false,BigInteger.ZERO,List.of());
        Result result=verifier.verify(input);assertEquals(Decision.LINKED_IN_SCOPE,result.decision());
        assertFalse(input.toString().contains(input.rawSignedTransaction()));assertFalse(result.toString().contains(sentinel));
        assertFalse(result.linked().toString().contains(sentinel));assertEquals(sentinel,((CAbiModels.Direct)result.linked().call()).priceSignature());
        assertThrows(UnsupportedOperationException.class,()->result.linked().logs().add(null));
        // This deliberately non-65-byte Oracle signature and missing expected action logs prove scope is NOT authorization/economics.
        assertTrue(result.verificationScope().contains("not owner/Oracle authorization"));assertFalse(result.completeCoverage());
    }
    @Test void fullWidthMetadataIsNeverNarrowedAndRepeatedVerificationHasNoStateEffect() {
        BigInteger max=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);Input base=deposit(false);
        BlockContext block=new BlockContext(max,BLOCK_HASH,max);
        Receipt receipt=change(change(base.receipt(),"blockNumber",max),"transactionIndex",max);
        List<RawLog> logs=new ArrayList<>();
        for(int n=0;n<2;n++)logs.add(change(change(change(receipt.logs().get(n),"blockNumber",max),"transactionIndex",max),"logIndex",max.subtract(bi(1-n))));
        Input input=change(change(base,"block",block),"receipt",change(receipt,"logs",logs));
        Result result=verifier.verify(input);assertEquals(Decision.LINKED_IN_SCOPE,result.decision());
        assertEquals(max,result.linked().block().timestamp());assertEquals(max,result.linked().receipt().transactionIndex());
        assertEquals(result,verifier.verify(input));logs.clear();assertEquals(2,result.linked().logs().size());
    }

    private Input deposit(boolean type2) {
        return fixture(VAULT,calls.encode(new CAbiModels.VaultAmount(CAbiModels.VaultAction.DEPOSIT,bi(100))),type2,BigInteger.ZERO,
            List.of(event(TOKEN,new CAbiEventModels.Transfer(SENDER,VAULT,bi(100))),event(VAULT,new CAbiEventModels.Deposited(SENDER,bi(100)))));
    }
    private CAbiEventModels.Log event(String emitter,CAbiEventModels.Event event){return events.encode(emitter,event);}
    private static Input fixture(String to,String calldata,boolean type2,BigInteger value,List<CAbiEventModels.Log> events) {
        byte[] raw=signed(to,calldata,type2,value);String hash=digest(raw);List<RawLog> logs=new ArrayList<>();
        for(int n=0;n<events.size();n++) {
            var e=events.get(n);logs.add(new RawLog(hash,BLOCK.number(),BLOCK.hash(),bi(2),bi(5+n),false,e.emitter(),e.topics(),e.data()));
        }
        Receipt receipt=new Receipt(hash,BLOCK.number(),BLOCK.hash(),bi(2),SENDER,to.equals("0x")?null:to,1,logs);
        return new Input(hex(raw),hash,DEPLOYMENT,BLOCK,receipt);
    }
    private static byte[] signed(String to,String calldata,boolean type2,BigInteger value) {
        List<RlpType> fields=new ArrayList<>();
        if(type2)fields.addAll(List.of(number(bi(31337)),number(bi(7)),number(bi(1)),number(bi(2)),number(bi(100_000)),RlpString.create(bytes(to)),number(value),RlpString.create(bytes(calldata)),new RlpList(List.of())));
        else fields.addAll(List.of(number(bi(7)),number(bi(1)),number(bi(100_000)),RlpString.create(bytes(to)),number(value),RlpString.create(bytes(calldata))));
        List<RlpType> unsigned=new ArrayList<>(fields);
        if(!type2){unsigned.add(number(bi(31337)));unsigned.add(number(BigInteger.ZERO));unsigned.add(number(BigInteger.ZERO));}
        byte[] signing=RlpEncoder.encode(new RlpList(unsigned));if(type2)signing=join(new byte[]{2},signing);
        Sign.SignatureData signature=Sign.signMessage(Hash.sha3(signing),TEST,false);int parity=signature.getV()[0]-27;
        fields.add(number(type2?bi(parity):bi(31337).multiply(bi(2)).add(bi(35+parity))));
        fields.add(number(new BigInteger(1,signature.getR())));fields.add(number(new BigInteger(1,signature.getS())));
        byte[] encoded=RlpEncoder.encode(new RlpList(fields));return type2?join(new byte[]{2},encoded):encoded;
    }
    private void assertDecision(Decision expected,Input input){Result result=verifier.verify(input);assertEquals(expected,result.decision(),result.reason());if(expected!=Decision.LINKED_IN_SCOPE)assertNull(result.linked());}
    private static Input withLogs(Input input,List<RawLog> logs){return change(input,"receipt",change(input.receipt(),"logs",logs));}
    private static RlpString number(BigInteger n){return RlpString.create(n);}
    private static BigInteger bi(long n){return BigInteger.valueOf(n);}
    private static String address(int n){return "0x"+String.format("%040x",n);}
    private static String hash(int n){return "0x"+String.format("%064x",n);}
    private static byte[] bytes(String hex){return HEX.parseHex(hex.substring(2));}
    private static String hex(byte[] bytes){return "0x"+HEX.formatHex(bytes);}
    private static String digest(byte[] bytes){return hex(Hash.sha3(bytes));}
    private static byte[] join(byte[] first,byte[] second){byte[] result=Arrays.copyOf(first,first.length+second.length);System.arraycopy(second,0,result,first.length,second.length);return result;}
    @SuppressWarnings("unchecked") private static <T extends Record>T change(T record,String name,Object value) {
        try {
            RecordComponent[] fields=record.getClass().getRecordComponents();Class<?>[] types=new Class<?>[fields.length];Object[] values=new Object[fields.length];boolean found=false;
            for(int n=0;n<fields.length;n++){types[n]=fields[n].getType();values[n]=fields[n].getName().equals(name)?value:fields[n].getAccessor().invoke(record);found|=fields[n].getName().equals(name);}
            if(!found)throw new AssertionError("Unknown fixture field");return (T)record.getClass().getConstructor(types).newInstance(values);
        }catch(Exception e){throw new AssertionError("Fixture construction",e);}
    }
}
