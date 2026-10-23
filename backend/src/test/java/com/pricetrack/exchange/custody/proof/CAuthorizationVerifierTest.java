package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CAuthorizationModels.*;
import static org.junit.jupiter.api.Assertions.*;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import com.pricetrack.exchange.custody.proof.CRulesTransitionModels.Position;
import com.pricetrack.exchange.custody.proof.CRulesTransitionModels.Status;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;

class CAuthorizationVerifierTest {
    // Public tiny deterministic fixture integers; no actual user or preserved chain keys.
    static final ECKeyPair USER=ECKeyPair.create(BigInteger.valueOf(17)),ORACLE=ECKeyPair.create(BigInteger.valueOf(23));
    static final String OWNER="0x"+Keys.getAddress(USER.getPublicKey()),SIGNER="0x"+Keys.getAddress(ORACLE.getPublicKey());
    static final HexFormat HEX=HexFormat.of();
    static BigInteger n(long x) {return BigInteger.valueOf(x);}
    static String a(long x) {return "0x"+String.format("%040x",x);}
    static String h(long x) {return "0x"+String.format("%064x",x);}
    static String a(BigInteger x) {return "0x"+String.format("%040x",x);}
    static String h(BigInteger x) {return "0x"+String.format("%064x",x);}
    static BigInteger amount(long x) {return n(x).multiply(BigInteger.TEN.pow(18));}
    final CAuthorizationVerifier verifier=new CAuthorizationVerifier();
    final CEip712Verifier crypto=new CEip712Verifier();
    final ObservationPoint point=new ObservationPoint(n(10),h(100),h(101),n(2));
    Context context(Mode mode) {return new Context(n(31337),a(30),a(31),a(32),SIGNER,h(6),h(8),mode,mode==Mode.DIRECT?OWNER:a(32),n(55),n(1000),point);}
    Context now(Context c,BigInteger now) {return new Context(c.chainId(),c.controller(),c.rules(),c.relayer(),c.oracleSigner(),c.symbolHash(),c.policyHash(),c.mode(),c.actualSender(),c.ethereumTransactionNonce(),now,c.point());}
    Intent intent(BigInteger[] w) {return new Intent(a(w[0]),a(w[1]),h(w[2]),h(w[3]),h(w[4]),h(w[5]),w[6],w[7],w[8],w[9],w[10],w[11],w[12],w[13],w[14],w[15],w[16],w[17],w[18],w[19],w[20],h(w[21]));}
    BigInteger b(String s) {return new BigInteger(s.substring(2),16);}
    BigInteger[] words(Intent i) {return new BigInteger[]{b(i.owner()),b(i.relayer()),b(i.operationId()),b(i.positionId()),b(i.accountRef()),b(i.symbolHash()),i.action(),i.expectedVersion(),i.expectedCallEpoch(),i.side(),i.leverage(),i.entryPriceE8(),i.collateralBefore(),i.collateralAfter(),i.priceMinE8(),i.priceMaxE8(),i.quantityMin(),i.quantityMax(),i.nonce(),i.issuedAt(),i.deadline(),b(i.policyHash())};}
    BigInteger[] words(Report r) {return new BigInteger[]{b(r.operationId()),b(r.positionId()),b(r.accountRef()),b(r.symbolHash()),r.action(),r.expectedVersion(),r.expectedCallEpoch(),r.side(),r.leverage(),r.quantity(),r.entryPriceE8(),r.collateralBefore(),r.collateralAfter(),r.priceE8(),r.observedAt(),r.validUntil(),b(r.policyHash()),b(r.executor())};}
    Report report(BigInteger[] w) {return new Report(h(w[0]),h(w[1]),h(w[2]),h(w[3]),w[4],w[5],w[6],w[7],w[8],w[9],w[10],w[11],w[12],w[13],w[14],w[15],h(w[16]),a(w[17]));}
    String sign(String digest,ECKeyPair key) {var s=Sign.signMessage(HEX.parseHex(digest.substring(2)),key,false);return "0x"+HEX.formatHex(s.getR())+HEX.formatHex(s.getS())+HEX.formatHex(s.getV());}
    Input signed(Input input) {
        Context c=input.context();String user=c.mode()==Mode.RELAYED?sign(crypto.intentDigest(input.intent(),c.chainId(),c.controller()),USER):null;
        return new Input(c,input.intent(),input.report(),user,sign(crypto.reportDigest(input.report(),c.chainId(),c.rules()),ORACLE),input.prestate(),input.ownerCode());
    }
    Input base(Mode mode) {
        String account=verifier.accountRef(OWNER,h(6));
        Report r=new Report(h(1),h(2),account,h(6),n(0),n(0),n(0),n(0),n(10),amount(10),n(100_00000000L),n(0),amount(100),n(100_00000000L),n(1000),n(1030),h(8),a(30));
        Intent i=new Intent(OWNER,a(32),h(1),h(2),account,h(6),n(0),n(0),n(0),n(0),n(10),n(0),n(0),amount(100),n(99_00000000L),n(101_00000000L),amount(9),amount(11),n(7),n(950),n(1070),h(8));
        Prestate pre=new Prestate(point,OWNER,n(7),h(2),a(0),h(2),Position.empty());
        return signed(new Input(context(mode),i,r,null,null,pre,new CodeObservation(point,OWNER,"0x")));
    }
    Input fields(Input x,Intent i,Report r) {return new Input(x.context(),i,r,x.userSignature(),x.priceSignature(),x.prestate(),x.ownerCode());}
    Input context(Input x,Context c) {return new Input(c,x.intent(),x.report(),x.userSignature(),x.priceSignature(),x.prestate(),x.ownerCode());}
    Input pre(Input x,Prestate p) {return new Input(x.context(),x.intent(),x.report(),x.userSignature(),x.priceSignature(),p,x.ownerCode());}
    Input code(Input x,CodeObservation code) {return new Input(x.context(),x.intent(),x.report(),x.userSignature(),x.priceSignature(),x.prestate(),code);}
    Input mutateIntent(Input x,int index,BigInteger value,boolean resign) {BigInteger[] w=words(x.intent());w[index]=value;Input y=fields(x,intent(w),x.report());return resign?signed(y):y;}
    Input mutateReport(Input x,int index,BigInteger value,boolean resign) {BigInteger[] w=words(x.report());w[index]=value;Input y=fields(x,x.intent(),report(w));return resign?signed(y):y;}
    CAuthorizationVerifier.Failure failure(Input x) {return assertThrows(CAuthorizationVerifier.Failure.class,()->verifier.verify(x));}
    Input existing(boolean close) {
        Input x=base(Mode.RELAYED);BigInteger[] iw=words(x.intent()),rw=words(x.report());
        iw[6]=rw[4]=close?n(4):n(1);iw[7]=rw[5]=n(3);iw[8]=rw[6]=n(1);iw[11]=rw[10]=n(100_00000000L);
        iw[12]=rw[11]=amount(100);iw[13]=rw[12]=close?amount(100):amount(150);iw[16]=iw[17]=amount(10);
        Position p=new Position(x.intent().accountRef(),n(0),n(10),amount(10),n(100_00000000L),amount(100),Status.ACTIVE,n(3),n(1),n(0),n(990));
        Prestate pre=new Prestate(point,OWNER,n(7),h(2),OWNER,h(2),p);
        return signed(pre(fields(x,intent(iw),report(rw)),pre));
    }

    @Test void independentCastCAccountRefGoldenMatchesSolidityAbiWords() {
        assertEquals("0x6453a33a384c95ac2e22861641952671527656a5ba5e1079f8454aad0cf620ac",verifier.accountRef("0x"+"11".repeat(20),h(6)));
        assertEquals("0x3b700c6618dfd86828ae9c5ffd523221face1cc6508e6d1dcb304bf84926c289",verifier.accountRef("0x"+"11".repeat(20),"0x"+"33".repeat(32)));
    }
    @Test void directOpenRequiresOwnerButNoRedundantUserSignature() {
        Input x=base(Mode.DIRECT);assertNull(x.userSignature());var result=verifier.verify(x);
        assertEquals(OWNER,result.owner());assertEquals(n(7),result.consumedUserNonce());assertEquals(n(8),result.nextUserNonce());assertFalse(result.completeCoverage());
    }
    @Test void relayOpenReverifiesActualUserAndOracleSignatures() {assertEquals(Mode.RELAYED,verifier.verify(base(Mode.RELAYED)).mode());}
    @Test void topUpAndCloseUseExactExistingEntryQuantityAndOwner() {verifier.verify(existing(false));verifier.verify(existing(true));}
    @Test void everyIntentFieldMutationFailsWithOriginalSignaturesAndContext() {
        Input x=base(Mode.RELAYED);BigInteger[] original=words(x.intent());
        for(int k=0;k<22;k++) {BigInteger[] changed=original.clone();changed[k]=changed[k].add(BigInteger.ONE);failure(fields(x,intent(changed),x.report()));}
        verifier.verify(x);
    }
    @Test void everyReportFieldMutationFailsWithOriginalOracleSignature() {
        Input x=base(Mode.RELAYED);BigInteger[] original=words(x.report());
        for(int k=0;k<18;k++) {BigInteger[] changed=original.clone();changed[k]=changed[k].add(BigInteger.ONE);failure(fields(x,x.intent(),report(changed)));}
        verifier.verify(x);
    }
    @Test void relayCannotUseMissingInvalidOrOtherUserSignature() {
        Input x=base(Mode.RELAYED);
        for(String signature:Arrays.asList(null,"0x",sign(crypto.intentDigest(x.intent(),x.context().chainId(),x.context().controller()),ORACLE)))
            assertEquals("C_AUTH_SIGNATURE",failure(new Input(x.context(),x.intent(),x.report(),signature,x.priceSignature(),x.prestate(),x.ownerCode())).getMessage());
    }
    @Test void directCannotBeSubmittedByRelayerOrAnotherOwner() {
        Input x=base(Mode.DIRECT);Context c=x.context();
        Context wrong=new Context(c.chainId(),c.controller(),c.rules(),c.relayer(),c.oracleSigner(),c.symbolHash(),c.policyHash(),c.mode(),a(32),c.ethereumTransactionNonce(),c.blockTimestamp(),c.point());
        assertEquals("C_AUTH_DIRECT_SENDER",failure(context(x,wrong)).getMessage());
    }
    @Test void relayRequiresFixedCallerAndFixedIntentRelayerInBothModes() {
        Input x=base(Mode.RELAYED);Context c=x.context();
        Context wrong=new Context(c.chainId(),c.controller(),c.rules(),c.relayer(),c.oracleSigner(),c.symbolHash(),c.policyHash(),c.mode(),OWNER,c.ethereumTransactionNonce(),c.blockTimestamp(),c.point());
        assertEquals("C_AUTH_RELAY_SENDER",failure(context(x,wrong)).getMessage());
        for(Mode mode:Mode.values())assertEquals("C_AUTH_RELAYER_FIELD",failure(mutateIntent(base(mode),1,n(999),true)).getMessage());
    }
    @Test void userNonceIsNotEthereumTransactionNonceAndMustMatchKeyedPrestate() {
        Input x=base(Mode.RELAYED);assertEquals(n(55),verifier.verify(x).ethereumTransactionNonce());
        assertEquals("C_AUTH_USER_NONCE",failure(mutateIntent(x,18,n(8),true)).getMessage());
    }
    @Test void maximumUserNonceCannotOverflowOnConsumption() {
        Input x=mutateIntent(base(Mode.RELAYED),18,CAbiModels.UINT_MAX,true);Prestate p=x.prestate();
        x=pre(x,new Prestate(p.point(),p.nonceOwnerKey(),CAbiModels.UINT_MAX,p.positionOwnerKey(),p.positionOwner(),p.rulesPositionKey(),p.rulesPosition()));
        assertEquals("C_AUTH_NONCE_OVERFLOW",failure(x).getMessage());
    }
    @Test void exactOneHundredTwentySecondWindowAndDeadlineBoundaryAreAllowed() {
        Input x=mutateIntent(base(Mode.RELAYED),19,n(1000),false);x=mutateIntent(x,20,n(1120),true);
        verifier.verify(x);verifier.verify(context(x,now(x.context(),n(1120))));
    }
    @Test void oneHundredTwentyOneSecondsFutureIssueAndExpiredIntentAreRejected() {
        Input x=base(Mode.RELAYED);
        assertEquals("C_AUTH_USER_WINDOW",failure(mutateIntent(x,20,n(1071),true)).getMessage());
        assertEquals("C_AUTH_USER_WINDOW",failure(mutateIntent(x,19,n(1001),true)).getMessage());
        assertEquals("C_AUTH_USER_WINDOW",failure(context(x,now(x.context(),n(1071)))).getMessage());
        assertEquals("C_AUTH_USER_WINDOW",failure(mutateIntent(x,20,n(950),true)).getMessage());
    }
    @Test void missingOwnerCodeIsInsufficientRatherThanAssumingEmptyEoa() {
        Input x=base(Mode.RELAYED);
        assertEquals(FailureKind.INSUFFICIENT_CONTEXT,failure(code(x,null)).kind());
        assertEquals("C_AUTH_OWNER_CODE_MISSING",failure(code(x,new CodeObservation(point,OWNER,null))).getMessage());
    }
    @Test void ownerCodeAtDifferentPointOrKeyIsInsufficient() {
        Input x=base(Mode.RELAYED);ObservationPoint other=new ObservationPoint(n(10),h(100),h(999),n(2));
        assertEquals(FailureKind.INSUFFICIENT_CONTEXT,failure(code(x,new CodeObservation(other,OWNER,"0x"))).kind());
        assertEquals(FailureKind.INSUFFICIENT_CONTEXT,failure(code(x,new CodeObservation(point,a(999),"0x"))).kind());
    }
    @Test void observedNonemptyOrDelegatedOwnerCodeIsRejected() {
        Input x=base(Mode.RELAYED);
        for(String code:List.of("0x00","0xef0100"+"11".repeat(20)))assertEquals("C_AUTH_OWNER_HAS_CODE",failure(code(x,new CodeObservation(point,OWNER,code))).getMessage());
    }
    @Test void missingPrestateAndWrongObservationPointAreInsufficient() {
        Input x=base(Mode.RELAYED);assertEquals(FailureKind.INSUFFICIENT_CONTEXT,failure(pre(x,null)).kind());
        Prestate p=x.prestate();ObservationPoint other=new ObservationPoint(n(11),h(101),h(102),n(0));
        assertEquals("C_AUTH_PRESTATE_POINT",failure(pre(x,new Prestate(other,p.nonceOwnerKey(),p.userNonce(),p.positionOwnerKey(),p.positionOwner(),p.rulesPositionKey(),p.rulesPosition()))).getMessage());
    }
    @Test void noncePositionOwnerAndRulesMappingKeysMustBeExplicitlyCorrect() {
        Input x=base(Mode.RELAYED);Prestate p=x.prestate();
        List<Prestate> wrong=List.of(new Prestate(point,a(999),p.userNonce(),h(2),a(0),h(2),p.rulesPosition()),
            new Prestate(point,OWNER,p.userNonce(),h(999),a(0),h(2),p.rulesPosition()),
            new Prestate(point,OWNER,p.userNonce(),h(2),a(0),h(999),p.rulesPosition()));
        for(Prestate q:wrong)assertEquals("C_AUTH_PRESTATE_KEYS",failure(pre(x,q)).getMessage());
    }
    @Test void fullRulesPositionAndControllerPositionOwnerCannotBeSubstituted() {
        Input x=existing(false);Prestate p=x.prestate();
        assertEquals("C_AUTH_POSITION_OWNER",failure(pre(x,new Prestate(point,OWNER,n(7),h(2),a(999),h(2),p.rulesPosition()))).getMessage());
        Position old=p.rulesPosition(),wrong=new Position(old.accountRef(),old.side(),old.leverage(),old.quantity(),old.entryPriceE8(),old.collateral(),old.status(),n(4),old.callEpoch(),old.deadline(),old.lastObservedAt());
        assertEquals("C_AUTH_POSITION_TERMS",failure(pre(x,new Prestate(point,OWNER,n(7),h(2),OWNER,h(2),wrong))).getMessage());
    }
    @Test void openCannotReuseNonemptyOwnerOrRulesPosition() {
        Input x=base(Mode.RELAYED);Prestate p=x.prestate();
        assertEquals("C_AUTH_OPEN_PRESTATE",failure(pre(x,new Prestate(point,OWNER,n(7),h(2),OWNER,h(2),p.rulesPosition()))).getMessage());
        Position malformed=new Position(h(0),n(0),n(0),n(0),n(0),n(0),Status.NONE,n(1),n(0),n(0),n(0));
        assertEquals("C_AUTH_OPEN_PRESTATE",failure(pre(x,new Prestate(point,OWNER,n(7),h(2),a(0),h(2),malformed))).getMessage());
    }
    @Test void zeroReversedOrUnboundedPriceAndQuantityRangesAreRejected() {
        Input x=base(Mode.RELAYED);
        for(int index:new int[]{14,16})assertEquals("C_AUTH_BOUNDS",failure(mutateIntent(x,index,n(0),true)).getMessage());
        assertEquals("C_AUTH_BOUNDS",failure(mutateIntent(x,15,BigInteger.TEN.pow(20),true)).getMessage());
        assertEquals("C_AUTH_BOUNDS",failure(mutateIntent(x,17,BigInteger.TEN.pow(30),true)).getMessage());
        assertEquals("C_AUTH_BOUNDS",failure(mutateIntent(x,14,n(102_00000000L),true)).getMessage());
    }
    @Test void executedPriceAndQuantityMustStayWithinApprovedBounds() {
        Input x=base(Mode.RELAYED);
        assertEquals("C_AUTH_OUTSIDE_BOUNDS",failure(mutateReport(x,13,n(98_00000000L),true)).getMessage());
        assertEquals("C_AUTH_OUTSIDE_BOUNDS",failure(mutateReport(x,9,amount(12),true)).getMessage());
    }
    @Test void openQuantityMustEqualExistingFloorFormulaNotOnlyRange() {
        Input x=base(Mode.RELAYED);
        assertEquals("C_AUTH_OPEN_QUANTITY",failure(mutateReport(x,9,amount(10).add(BigInteger.ONE),true)).getMessage());
    }
    @Test void openIntentEntryMustBeZeroAndReportEntryMustEqualExecutedPrice() {
        Input x=base(Mode.RELAYED);
        assertEquals("C_AUTH_OPEN_TERMS",failure(mutateIntent(x,11,n(1),true)).getMessage());
        assertEquals("C_AUTH_OPEN_TERMS",failure(mutateReport(x,10,n(100_00000001L),true)).getMessage());
    }
    @Test void existingPositionQuantityBoundsAndOriginalEntryAreExact() {
        Input x=existing(false);
        assertEquals("C_AUTH_EXACT_ENTRY_QUANTITY",failure(mutateIntent(x,16,amount(9),true)).getMessage());
        assertEquals("C_AUTH_EXACT_ENTRY_QUANTITY",failure(mutateIntent(x,11,n(100_00000001L),true)).getMessage());
    }
    @Test void topUpIncreasesCollateralAndCloseCannotChangeCollateral() {
        Input top=existing(false);BigInteger[] iw=words(top.intent()),rw=words(top.report());iw[13]=rw[12]=amount(100);
        assertEquals("C_AUTH_TOP_UP_COLLATERAL",failure(signed(fields(top,intent(iw),report(rw)))).getMessage());
        Input close=existing(true);iw=words(close.intent());rw=words(close.report());iw[13]=rw[12]=amount(99);
        assertEquals("C_AUTH_CLOSE_COLLATERAL",failure(signed(fields(close,intent(iw),report(rw)))).getMessage());
    }
    @Test void controllerSymbolPolicyAccountAndReportExecutorIdentityMustMatch() {
        Input x=base(Mode.RELAYED);
        for(int index:new int[]{4,5,21})assertEquals("C_AUTH_INTENT_REPORT_LINK",failure(mutateIntent(x,index,n(999),true)).getMessage());
        assertEquals("C_AUTH_INTENT_REPORT_LINK",failure(mutateReport(x,17,n(999),true)).getMessage());
    }
    @Test void wrongChainRulesOracleOrUserDomainCannotReuseValidSignatures() {
        Input x=base(Mode.RELAYED);Context c=x.context();
        List<Context> wrong=List.of(
            new Context(n(31338),c.controller(),c.rules(),c.relayer(),c.oracleSigner(),c.symbolHash(),c.policyHash(),c.mode(),c.actualSender(),c.ethereumTransactionNonce(),c.blockTimestamp(),c.point()),
            new Context(c.chainId(),c.controller(),a(999),c.relayer(),c.oracleSigner(),c.symbolHash(),c.policyHash(),c.mode(),c.actualSender(),c.ethereumTransactionNonce(),c.blockTimestamp(),c.point()),
            new Context(c.chainId(),c.controller(),c.rules(),c.relayer(),a(999),c.symbolHash(),c.policyHash(),c.mode(),c.actualSender(),c.ethereumTransactionNonce(),c.blockTimestamp(),c.point()));
        for(Context other:wrong)assertEquals("C_AUTH_SIGNATURE",failure(context(x,other)).getMessage());
    }
    @Test void reportSignatureIsAlwaysRequiredEvenOnDirectOwnerPath() {
        Input x=base(Mode.DIRECT);
        assertEquals("C_AUTH_SIGNATURE",failure(new Input(x.context(),x.intent(),x.report(),null,"0x",x.prestate(),x.ownerCode())).getMessage());
    }
    @Test void automaticRiskActionCannotBeDisguisedAsUserCloseApproval() {
        Input x=base(Mode.RELAYED);BigInteger[] iw=words(x.intent()),rw=words(x.report());iw[6]=rw[4]=n(5);
        assertEquals("C_AUTH_USER_ACTION",failure(signed(fields(x,intent(iw),report(rw)))).getMessage());
    }
    @Test void terminalRulesStateCannotAuthorizeNormalTopUpOrClose() {
        Input x=existing(true);Prestate p=x.prestate();Position old=p.rulesPosition();
        Position closed=new Position(old.accountRef(),old.side(),old.leverage(),old.quantity(),old.entryPriceE8(),old.collateral(),Status.CLOSED,old.version(),old.callEpoch(),old.deadline(),old.lastObservedAt());
        assertEquals("C_AUTH_POSITION_STATE",failure(pre(x,new Prestate(point,OWNER,n(7),h(2),OWNER,h(2),closed))).getMessage());
    }
    @Test void verificationScopeDoesNotClaimFreshReportRpcTruthVaultOrCanonicalSuccess() {
        Input x=base(Mode.RELAYED);Input staleReport=context(x,now(x.context(),n(1040)));
        Result result=verifier.verify(staleReport);assertFalse(result.completeCoverage());
        assertTrue(result.verificationScope().contains("PREVALIDATED_CONTEXT_ONLY"));assertTrue(result.verificationScope().contains("Report TTL"));
        assertTrue(result.verificationScope().contains("Vault/pause/unpaid"));
    }
    @Test void inputAndFailureStringsNeverLeakOriginalSignatures() {
        Input x=base(Mode.RELAYED);assertFalse(x.toString().contains(x.userSignature()));assertFalse(x.toString().contains(x.priceSignature()));
        var failure=failure(new Input(x.context(),x.intent(),x.report(),"DO_NOT_ECHO",x.priceSignature(),x.prestate(),x.ownerCode()));
        assertEquals("C_AUTH_SIGNATURE",failure.getMessage());assertNull(failure.getCause());
    }
}
