package com.pricetrack.exchange.futures.audit;

import static org.assertj.core.api.Assertions.*;
import static com.pricetrack.exchange.futures.audit.FuturesAuditNonceCancellationVerifier.*;
import com.pricetrack.exchange.futures.chain.FuturesChainIdentity;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.crypto.transaction.type.*;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;

/** Real local signatures, deliberately supplied RPC-shaped fixtures; NOT RPC/canonical-chain proof. */
class FuturesAuditNonceCancellationVerifierTest {
    static final long CHAIN=31337;
    static final String CONTROLLER=address(1),BLOCK=hash(100),TOPIC=Hash.sha3String("UserNonceCancelledV2(address,uint256)");
    static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE,MAX=ONE.shiftLeft(256).subtract(ONE);
    static String address(int n){return "0x"+String.format("%040x",n);}
    static String hash(int n){return "0x"+String.format("%064x",n);}
    static String q(BigInteger n){return Numeric.encodeQuantity(n);}
    static String data(BigInteger nonce){return Hash.sha3String("cancelNonce(uint256)").substring(0,10)+Numeric.toHexStringNoPrefixZeroPadded(nonce,64);}
    static FuturesAuditDeployment target(){
        var chain=new FuturesChainIdentity(BigInteger.valueOf(CHAIN),CONTROLLER,address(2),address(3),address(4),"2");
        return FuturesAuditDeployment.target(FuturesAuditDeployment.Route.B_V2,"local-pure-cancel",chain,hash(1),hash(2),hash(3));
    }
    record Case(FuturesAuditDeployment target,Transaction tx,TransactionReceipt receipt,EthBlock.Block block){Verification verify(){return FuturesAuditNonceCancellationVerifier.verify(target,tx,receipt,block);}}
    static Case fixture(boolean typed,boolean success){return fixture(typed,success,ZERO,data(BigInteger.valueOf(7)),CONTROLLER);}
    static Case fixture(boolean typed,boolean success,BigInteger value,String input,String to){
        Credentials key;
        try{key=Credentials.create(Keys.createEcKeyPair());}catch(Exception failed){throw new AssertionError("LOCAL_KEY_GENERATION_FAILED");}
        RawTransaction unsigned=typed?RawTransaction.createTransaction(CHAIN,BigInteger.valueOf(3),BigInteger.valueOf(90000),to,value,input,ONE,BigInteger.valueOf(20),List.of(new org.web3j.crypto.AccessListObject(address(5),List.of(hash(0)))))
                :RawTransaction.createTransaction(BigInteger.valueOf(3),BigInteger.TEN,BigInteger.valueOf(90000),to,value,input);
        byte[] encoded=typed?TransactionEncoder.signMessage(unsigned,key):TransactionEncoder.signMessage(unsigned,CHAIN,key);
        var signed=(SignedRawTransaction)TransactionDecoder.decode(Numeric.toHexString(encoded));var sig=signed.getSignatureData();
        var tx=new Transaction();tx.setHash(Hash.sha3(Numeric.toHexString(encoded)));tx.setType(typed?"0x2":"0x0");tx.setChainId(q(BigInteger.valueOf(CHAIN)));
        tx.setFrom(key.getAddress());tx.setTo(to);tx.setNonce(q(signed.getNonce()));tx.setValue(q(value));tx.setGas(q(signed.getGasLimit()));tx.setInput(input);
        tx.setR(Numeric.toHexString(sig.getR()));tx.setS(Numeric.toHexString(sig.getS()));tx.setV(typed?Sign.getRecId(sig,CHAIN):new BigInteger(1,sig.getV()).longValueExact());
        if(typed){var inner=(Transaction1559)signed.getTransaction();tx.setMaxFeePerGas(q(inner.getMaxFeePerGas()));tx.setMaxPriorityFeePerGas(q(inner.getMaxPriorityFeePerGas()));tx.setAccessList(inner.getAccessList().stream().map(a->new org.web3j.protocol.core.methods.response.AccessListObject(a.getAddress(),a.getStorageKeys())).toList());tx.setyParity(q(BigInteger.valueOf(tx.getV())));}
        else tx.setGasPrice(q(signed.getGasPrice()));
        tx.setBlockHash(BLOCK);tx.setBlockNumber("0xa");tx.setTransactionIndex("0x0");
        var receipt=new TransactionReceipt();receipt.setTransactionHash(tx.getHash());receipt.setFrom(tx.getFrom());receipt.setTo(to);receipt.setBlockHash(BLOCK);receipt.setBlockNumber("0xa");receipt.setTransactionIndex("0x0");receipt.setStatus(success?"0x1":"0x0");
        var event=new Log();event.setAddress(to);event.setTopics(List.of(TOPIC,"0x"+"0".repeat(24)+tx.getFrom().substring(2)));event.setData("0x"+input.substring(10,Math.min(74,input.length())));event.setTransactionHash(tx.getHash());event.setBlockHash(BLOCK);event.setBlockNumber("0xa");event.setTransactionIndex("0x0");event.setLogIndex("0x5");
        receipt.setLogs(success?List.of(event):List.of());
        var block=new EthBlock.Block();block.setHash(BLOCK);block.setNumber("0xa");block.setTransactions(List.of(new EthBlock.TransactionHash(tx.getHash())));
        return new Case(target(),tx,receipt,block);
    }
    static void rejected(Case c,Verdict verdict,Reason reason){var result=c.verify();assertThat(result.verdict()).isEqualTo(verdict);assertThat(result.reason()).isEqualTo(reason);assertThat(result.cancellation()).isNull();}
    static void mismatch(Case c,Reason reason){rejected(c,Verdict.MISMATCH,reason);}
    static void unknown(Case c,Reason reason){rejected(c,Verdict.INCONCLUSIVE,reason);}
    static void mutations(Consumer<Case> mutation,Reason reason){for(boolean typed:new boolean[]{false,true}){var c=fixture(typed,true);mutation.accept(c);mismatch(c,reason);}}

    @Test void type0Eip155AndType2NonemptyAccessListSuccessVerify(){
        for(boolean typed:new boolean[]{false,true}){var c=fixture(typed,true);var result=c.verify();assertThat(result.verdict()).isEqualTo(Verdict.VERIFIED);assertThat(result.reason()).isEqualTo(Reason.CANCEL_SUCCESS_EVIDENCE);assertThat(result.cancellation().owner()).isEqualTo(c.tx().getFrom());assertThat(result.cancellation().expectedNonce()).isEqualTo(BigInteger.valueOf(7));assertThat(result.cancellation().logIndex()).isEqualTo(BigInteger.valueOf(5));}
    }
    @Test void failedCancellationHasNoConsumedNonceAndDoesNotClaimCause(){
        for(boolean typed:new boolean[]{false,true}){var result=fixture(typed,false).verify();assertThat(result.verdict()).isEqualTo(Verdict.VERIFIED);assertThat(result.reason()).isEqualTo(Reason.CANCEL_REVERT_CAUSE_UNDETERMINED);assertThat(result.cancellation().successful()).isFalse();assertThat(result.cancellation().logIndex()).isNull();}
    }
    @Test void nonzeroValueFailureIsSupportedButSuccessIsContradictory(){
        for(boolean typed:new boolean[]{false,true}){assertThat(fixture(typed,false,ONE,data(ONE),CONTROLLER).verify().verdict()).isEqualTo(Verdict.VERIFIED);mismatch(fixture(typed,true,ONE,data(ONE),CONTROLLER),Reason.VALUE_CONFLICT);}
    }
    @Test void cryptographicallyValidTrailingAbiIsUnsupportedNotContractRejection(){unknown(fixture(true,true,ZERO,data(ONE)+"00",CONTROLLER),Reason.ABI_UNSUPPORTED);}
    @Test void unsupportedSelectorAndTransactionTypesAreInconclusive(){
        var c=fixture(false,true);c.tx().setInput("0x00000000"+"0".repeat(64));unknown(c,Reason.ABI_UNSUPPORTED);
        for(var type:List.of("0x1","0x3","0x4","0x7")){c=fixture(true,true);c.tx().setType(type);unknown(c,Reason.TYPE_UNSUPPORTED);}
    }
    @Test void legacyUnprotectedVIsOutsideSupportedDomain(){var c=fixture(false,true);c.tx().setV(27);unknown(c,Reason.TYPE_UNSUPPORTED);}
    @Test void wrongChainIsDetectedBeforeEnvelopeReconstruction(){mutations(c->c.tx().setChainId("0x1"),Reason.CHAIN_CONFLICT);var c=fixture(false,true);c.tx().setChainId(null);c.tx().setV(37);mismatch(c,Reason.CHAIN_CONFLICT);}
    @Test void changedSignedInputAndReportedHashAreNotAccepted(){mutations(c->c.tx().setInput(data(BigInteger.valueOf(8))),Reason.HASH_CONFLICT);mutations(c->c.tx().setHash(hash(999)),Reason.HASH_CONFLICT);}
    @Test void changedSignatureAndSignedNonceAreNotAccepted(){mutations(c->c.tx().setR("0x1"),Reason.HASH_CONFLICT);mutations(c->c.tx().setNonce("0x4"),Reason.HASH_CONFLICT);}
    @Test void recoveredSenderNotRpcClaimIsTheDirectCaller(){mutations(c->c.tx().setFrom(address(99)),Reason.SENDER_CONFLICT);}
    @Test void otherTopLevelDestinationIsNotPretendedToBeSupportedInternalCancellation(){unknown(fixture(true,true,ZERO,data(ONE),address(99)),Reason.TARGET_UNSUPPORTED);}
    @Test void accessListAndFeeMutationsChangeSignedEnvelopeHash(){var c=fixture(true,true);c.tx().setAccessList(List.of(new org.web3j.protocol.core.methods.response.AccessListObject(address(8),List.of(hash(0)))));mismatch(c,Reason.HASH_CONFLICT);c=fixture(true,true);c.tx().setMaxFeePerGas("0x15");mismatch(c,Reason.HASH_CONFLICT);}
    @Test void type2ParityAndMissingFieldsFailClosed(){var c=fixture(true,true);c.tx().setyParity(q(BigInteger.valueOf(1-c.tx().getV())));mismatch(c,Reason.SIGNATURE_CONFLICT);c=fixture(true,true);c.tx().setV(2);mismatch(c,Reason.SIGNATURE_CONFLICT);c=fixture(true,true);c.tx().setAccessList(null);unknown(c,Reason.EVIDENCE_REQUIRED);c=fixture(true,true);c.tx().setChainId(null);unknown(c,Reason.EVIDENCE_REQUIRED);}
    @Test void highSAndZeroRFailWithoutLeakingSignature(){var c=fixture(true,true);c.tx().setS("0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");mismatch(c,Reason.SIGNATURE_CONFLICT);c=fixture(false,true);c.tx().setR("0x0");mismatch(c,Reason.SIGNATURE_CONFLICT);}
    @Test void receiptFromToHashHeightAndIndexMustLinkExactly(){
        mutations(c->c.receipt().setFrom(address(9)),Reason.RECEIPT_LINK_CONFLICT);mutations(c->c.receipt().setTo(address(9)),Reason.RECEIPT_LINK_CONFLICT);
        mutations(c->c.receipt().setTransactionHash(hash(99)),Reason.RECEIPT_LINK_CONFLICT);mutations(c->c.receipt().setBlockNumber("0xb"),Reason.RECEIPT_LINK_CONFLICT);mutations(c->c.receipt().setTransactionIndex("0x1"),Reason.RECEIPT_LINK_CONFLICT);
        mutations(c->c.receipt().setBlockHash(hash(99)),Reason.RECEIPT_LINK_CONFLICT);mutations(c->c.block().setHash(hash(99)),Reason.RECEIPT_LINK_CONFLICT);
    }
    @Test void headerMembershipIsRequiredNotJustMatchingBlockHash(){var c=fixture(true,true);c.block().setTransactions(List.of(new EthBlock.TransactionHash(hash(99))));mismatch(c,Reason.HEADER_MEMBERSHIP_CONFLICT);c=fixture(true,true);c.block().setTransactions(List.of());mismatch(c,Reason.HEADER_MEMBERSHIP_CONFLICT);c=fixture(true,true);c.block().setTransactions(null);unknown(c,Reason.EVIDENCE_REQUIRED);}
    @Test void pendingAndMalformedQuantityEvidenceIsInconclusive(){var c=fixture(true,true);c.tx().setBlockHash(null);unknown(c,Reason.EVIDENCE_MALFORMED);c=fixture(true,true);c.tx().setBlockNumber(null);unknown(c,Reason.EVIDENCE_REQUIRED);c=fixture(true,true);c.tx().setNonce("0x-1");unknown(c,Reason.EVIDENCE_MALFORMED);c=fixture(true,true);c.tx().setGas("0x0");unknown(c,Reason.EVIDENCE_MALFORMED);}
    @Test void eventEmitterOwnerTopicAndNonceMustMatchSignedCall(){
        mutations(c->c.receipt().getLogs().getFirst().setAddress(address(9)),Reason.EVENT_CONFLICT);
        mutations(c->c.receipt().getLogs().getFirst().setTopics(List.of(hash(99),c.receipt().getLogs().getFirst().getTopics().get(1))),Reason.EVENT_CONFLICT);
        mutations(c->c.receipt().getLogs().getFirst().setTopics(List.of(TOPIC,"0x"+"0".repeat(24)+address(9).substring(2))),Reason.EVENT_CONFLICT);
        mutations(c->c.receipt().getLogs().getFirst().setData("0x"+Numeric.toHexStringNoPrefixZeroPadded(ONE,64)),Reason.EVENT_CONFLICT);
    }
    @Test void eventCoordinatesMustMatchReceipt(){
        mutations(c->c.receipt().getLogs().getFirst().setBlockHash(hash(99)),Reason.EVENT_CONFLICT);
        mutations(c->c.receipt().getLogs().getFirst().setTransactionHash(hash(99)),Reason.EVENT_CONFLICT);mutations(c->c.receipt().getLogs().getFirst().setBlockNumber("0xb"),Reason.EVENT_CONFLICT);mutations(c->c.receipt().getLogs().getFirst().setTransactionIndex("0x1"),Reason.EVENT_CONFLICT);
    }
    @Test void removedEventIsWithdrawnEvidenceNotConfirmedNonceConflict(){
        for(boolean typed:new boolean[]{false,true}){var c=fixture(typed,true);c.receipt().getLogs().getFirst().setRemoved(true);unknown(c,Reason.REMOVED_EVENT);}
    }
    @Test void missingExtraAndMalformedLogsFailClosed(){var c=fixture(true,true);c.receipt().setLogs(List.of());mismatch(c,Reason.EVENT_CONFLICT);c=fixture(true,true);c.receipt().setLogs(List.of(c.receipt().getLogs().getFirst(),c.receipt().getLogs().getFirst()));mismatch(c,Reason.EVENT_CONFLICT);c=fixture(true,true);c.receipt().getLogs().getFirst().setData("0x01");unknown(c,Reason.EVIDENCE_MALFORMED);c=fixture(true,true);c.receipt().getLogs().getFirst().setLogIndex(null);unknown(c,Reason.EVIDENCE_REQUIRED);}
    @Test void failedReceiptCannotHaveAnyEventsAndUnknownStatusIsNotFailure(){var c=fixture(true,true);c.receipt().setStatus("0x0");mismatch(c,Reason.EVENT_CONFLICT);c=fixture(true,false);c.receipt().setStatus("0x2");unknown(c,Reason.STATUS_UNSUPPORTED);c=fixture(true,false);c.receipt().setLogs(null);unknown(c,Reason.EVIDENCE_REQUIRED);}
    @Test void maximumNonceSuccessWouldOverflowButFailureIsNotGivenInventedCause(){mismatch(fixture(true,true,ZERO,data(MAX),CONTROLLER),Reason.NONCE_OVERFLOW);assertThat(fixture(true,false,ZERO,data(MAX),CONTROLLER).verify().reason()).isEqualTo(Reason.CANCEL_REVERT_CAUSE_UNDETERMINED);}
    @Test void inputAndNumericBoundsApplyBeforeParsing(){var c=fixture(true,true);c.tx().setInput("0x"+"aa".repeat(MAX_INPUT_BYTES+1));unknown(c,Reason.BOUNDED_INPUT_LIMIT);c=fixture(true,true);c.tx().setGas("0x"+"f".repeat(65));unknown(c,Reason.BOUNDED_INPUT_LIMIT);c=fixture(true,true);c.tx().setR("0x"+"f".repeat(65));unknown(c,Reason.BOUNDED_INPUT_LIMIT);}
    @Test void accessListEntryAndKeyLimitsAreInconclusive(){
        var c=fixture(true,true);var item=new org.web3j.protocol.core.methods.response.AccessListObject(address(5),List.of(hash(0)));c.tx().setAccessList(Collections.nCopies(17,item));unknown(c,Reason.BOUNDED_INPUT_LIMIT);
        c=fixture(true,true);c.tx().setAccessList(List.of(new org.web3j.protocol.core.methods.response.AccessListObject(address(5),Collections.nCopies(33,hash(0)))));unknown(c,Reason.BOUNDED_INPUT_LIMIT);
        c=fixture(true,true);c.tx().setAccessList(Collections.nCopies(9,new org.web3j.protocol.core.methods.response.AccessListObject(address(5),Collections.nCopies(32,hash(0)))));unknown(c,Reason.BOUNDED_INPUT_LIMIT);
    }
    @Test void headerAndReceiptSizeLimitsAreInconclusive(){var c=fixture(true,true);c.block().setTransactions(Collections.nCopies(1025,new EthBlock.TransactionHash(c.tx().getHash())));unknown(c,Reason.BOUNDED_INPUT_LIMIT);c=fixture(true,true);c.receipt().setLogs(Collections.nCopies(51,c.receipt().getLogs().getFirst()));unknown(c,Reason.BOUNDED_INPUT_LIMIT);}
    @Test void invalidAccessKeysAndGasFeeShapeAreInconclusive(){var c=fixture(true,true);c.tx().setAccessList(List.of(new org.web3j.protocol.core.methods.response.AccessListObject(address(5),List.of("secret-invalid"))));unknown(c,Reason.EVIDENCE_MALFORMED);c=fixture(true,true);c.tx().setMaxPriorityFeePerGas("0xff");unknown(c,Reason.EVIDENCE_MALFORMED);}
    @Test void unsupportedTargetAndNullInputsCannotVerify(){assertThat(FuturesAuditNonceCancellationVerifier.verify(null,null,null,null).reason()).isEqualTo(Reason.INPUT_REQUIRED);var c=fixture(true,true);var chain=c.target().chain();var v1=new FuturesChainIdentity(chain.chainId(),chain.engine(),chain.executor(),chain.priceSigner(),chain.rules(),"1");var target=FuturesAuditDeployment.target(FuturesAuditDeployment.Route.B_V1,"v1",v1,hash(1),hash(2),hash(3));unknown(new Case(target,c.tx(),c.receipt(),c.block()),Reason.TARGET_UNSUPPORTED);}
    @Test void outputAndErrorsContainNoRawSignatureOrSecretPayload(){
        var c=fixture(true,true);var result=c.verify();assertThat(Arrays.stream(Cancellation.class.getRecordComponents()).map(x->x.getName())).doesNotContain("raw","signature","input","r","s");
        assertThat(result.toString()).doesNotContain(c.tx().getR(),c.tx().getS(),c.tx().getInput());c.tx().setInput("secret-invalid");assertThat(c.verify().toString()).doesNotContain("secret-invalid");
    }
}
