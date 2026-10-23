package com.pricetrack.exchange.futures.audit;

import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

/** Local signatures/raw only. No RPC, canonical receipt, PostgreSQL or user-wallet acceptance claim. */
class FuturesAuthorizedAuditVerifierTest {
    static final Credentials USER=Credentials.create("6f"),PRICE=Credentials.create("14d"),RELAYER=Credentials.create("de");
    static final BigInteger ZERO=BigInteger.ZERO,MARGIN=BigInteger.TEN.pow(23),P0=new BigInteger("7500000000000"),P1=new BigInteger("7500100000000");
    static final String PID="position",OID="operation";
    static final FuturesChainIdentity V2=new FuturesChainIdentity(BigInteger.valueOf(31337),"0x"+"12".repeat(20),RELAYER.getAddress(),PRICE.getAddress(),"0x"+"13".repeat(20),"2");
    record Case(FuturesChainIdentity chain,Position position,Operation operation,FuturesTransactionStore.Stored stored,FuturesAuthorizedAuditEvidence evidence){
        FuturesAuthorizedAuditVerifier.Result verify(){return FuturesAuthorizedAuditVerifier.verify(chain,position,operation,stored,evidence);}
        Case with(FuturesAuthorizedAuditEvidence e){return new Case(chain,position,operation,stored,e);}
    }
    static String sign(byte[] digest){var s=Sign.signMessage(digest,USER.getEcKeyPair(),false);byte[] b=new byte[65];System.arraycopy(s.getR(),0,b,0,32);System.arraycopy(s.getS(),0,b,32,32);b[64]=s.getV()[0];return Numeric.toHexString(b);}
    static Position position(BigInteger price){return new Position(PID,1,UserIntentCodec.account(USER.getAddress()),FuturesMath.Side.LONG,10,FuturesMath.quantity(MARGIN,10,price),price,MARGIN,"OPEN","HEALTHY",1,0,0,1010,null,null,null,null,Instant.EPOCH);}
    static Operation operation(BigInteger price){return new Operation(OID,PID,1,Action.OPEN,0,0,price,1010,ZERO,MARGIN,MARGIN,ZERO,ZERO,ZERO,"CONFIRMED");}
    static String priceSignature(FuturesChainIdentity c,FuturesReport r){return FuturesReportCodec.sign(r,c.chainId(),c.priceContract(),PRICE.getEcKeyPair());}
    static FuturesTransactionStore.Stored stored(FuturesChainIdentity c,Operation o,FuturesReport r,Authorization a){
        String ps=priceSignature(c,r),data=a==null?FuturesReportCodec.encode(r,ps):UserIntentCodec.encode(r,ps,a);
        byte[] raw=TransactionEncoder.signMessage(RawTransaction.createTransaction(ZERO,BigInteger.ONE,BigInteger.valueOf(500000),c.engine(),ZERO,data),31337,RELAYER);
        return new FuturesTransactionStore.Stored(o.id(),Numeric.toHexString(raw),Numeric.toHexString(Hash.sha3(raw)),0,o.state(),c.chainId(),c.engine(),c.executor(),r,ps,a);
    }
    static FuturesAuthorizedAuditEvidence.Revision revision(int n,FuturesChainIdentity c,FuturesReport r){return new FuturesAuthorizedAuditEvidence.Revision(n,r,priceSignature(c,r),r.priceE8(),r.observedAt().longValueExact(),"SYNTHETIC",1L);}
    static FuturesAuthorizedAuditEvidence copy(FuturesAuthorizedAuditEvidence e,Authorization pa,Authorization a,String wallet,Integer head,List<FuturesAuthorizedAuditEvidence.Revision> revisions){
        return new FuturesAuthorizedAuditEvidence(e.operationId(),e.positionId(),e.userId(),pa,a,wallet,e.v2PositionController(),e.v2OperationController(),e.originalReport(),e.originalPriceSignature(),head,revisions);
    }
    static Case v2(int head){
        var p=position(head==0?P0:P1);var o=operation(head==0?P0:P1);var r=FuturesReport.from(o,p,V2.reportExecutor());
        var r0=FuturesReport.from(operation(P0),position(P0),V2.reportExecutor());
        var i=new UserIntentV2Codec.Intent(USER.getAddress(),V2.executor(),r.operationId(),r.positionId(),r.accountRef(),r.symbolHash(),0,ZERO,ZERO,0,10,ZERO,ZERO,MARGIN,
            P0.subtract(BigInteger.TEN.pow(10)),P1.add(BigInteger.TEN.pow(10)),BigInteger.ONE,FuturesMath.quantity(MARGIN,10,P0).add(BigInteger.ONE),BigInteger.valueOf(3),BigInteger.valueOf(1000),BigInteger.valueOf(1120),r.policyHash());
        var a=new Authorization(V2,USER.getAddress(),null,sign(UserIntentV2Codec.digest(V2,i)),i);var pa=new Authorization(V2,USER.getAddress(),null,null);
        var revisions=head==0?List.of(revision(0,V2,r0)):List.of(revision(0,V2,r0),revision(1,V2,r));
        var e=new FuturesAuthorizedAuditEvidence(OID,PID,1,pa,a,USER.getAddress(),V2.engine(),V2.engine(),r0,priceSignature(V2,r0),head,revisions);
        return new Case(V2,p,o,stored(V2,o,r,a),e);
    }
    @Test void revisionZeroAndOneRetainOriginalConsentAndMatchFinalRaw(){
        for(int n=0;n<2;n++){var f=v2(n);var result=f.verify();assertThat(result.mode()).isEqualTo("B_V2");assertThat(result.activeRevision()).isEqualTo(n);assertThat(result.intentHash()).startsWith("0x");assertThat(result.reportDigest()).startsWith("0x");}
    }
    @Test void revisionOrderIsCanonicalizedButDuplicateMissingExtraAndWrongHeadReject(){
        var f=v2(1);var e=f.evidence();var reverse=new ArrayList<>(e.revisions());Collections.reverse(reverse);
        f.with(copy(e,e.positionAuthorization(),e.operationAuthorization(),e.linkedWallet(),1,reverse)).verify();
        for(var rows:List.of(List.of(e.revisions().get(1)),List.of(e.revisions().get(0),e.revisions().get(0)),List.of(e.revisions().get(0))))
            assertThatThrownBy(()->f.with(copy(e,e.positionAuthorization(),e.operationAuthorization(),e.linkedWallet(),1,rows)).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
        assertThatThrownBy(()->f.with(copy(e,e.positionAuthorization(),e.operationAuthorization(),e.linkedWallet(),0,e.revisions())).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
        assertThatThrownBy(()->f.with(copy(e,e.positionAuthorization(),e.operationAuthorization(),e.linkedWallet(),2,e.revisions())).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void priorRevisionCannotBeTheStoredSignedReport(){
        var f=v2(1);var old=f.evidence().revisions().get(0).report();var stale=stored(V2,f.operation(),old,f.evidence().operationAuthorization());
        assertThatThrownBy(()->FuturesAuthorizedAuditVerifier.verify(V2,f.position(),f.operation(),stale,f.evidence())).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void missingAuthorizationIsInconclusiveNotProofOfCorruption(){var f=v2(0);assertThatThrownBy(()->f.with(null).verify()).isInstanceOf(FuturesAuditSnapshot.Unavailable.class);}
    @Test void ownerWalletAndPositionApprovalPollutionReject(){
        var f=v2(0);var e=f.evidence();
        assertThatThrownBy(()->f.with(copy(e,e.positionAuthorization(),e.operationAuthorization(),PRICE.getAddress(),0,e.revisions())).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
        assertThatThrownBy(()->f.with(copy(e,e.operationAuthorization(),e.operationAuthorization(),e.linkedWallet(),0,e.revisions())).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
        var bad=new Authorization(V2,PRICE.getAddress(),null,null);
        assertThatThrownBy(()->f.with(copy(e,bad,e.operationAuthorization(),e.linkedWallet(),0,e.revisions())).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void routingVersionAndContractIdentityCannotBeMixed(){
        var f=v2(0);var e=f.evidence();var wrong=new FuturesChainIdentity(V2.chainId(),V2.engine(),V2.executor(),V2.priceSigner(),V2.rules(),"1");
        assertThatThrownBy(()->FuturesAuthorizedAuditVerifier.verify(wrong,f.position(),f.operation(),f.stored(),e)).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
        var polluted=new FuturesAuthorizedAuditEvidence(OID,PID,1,e.positionAuthorization(),e.operationAuthorization(),e.linkedWallet(),V2.rules(),V2.engine(),e.originalReport(),e.originalPriceSignature(),0,e.revisions());
        assertThatThrownBy(()->f.with(polluted).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void unsignedAndPreparedEvidenceDoesNotRequireRawOrCanonicalReceipt(){
        var f=v2(0);assertThat(FuturesAuthorizedAuditVerifier.verify(V2,f.position(),f.operation(),null,f.evidence()).mode()).isEqualTo("B_V2");
        // This only verifies linkage. The enclosing audit must separately reject unsettled operations as INCONCLUSIVE.
    }
    @Test void providerGenerationAndRevisionObservedPriceCannotBeSubstituted(){
        var f=v2(1);var e=f.evidence();var r=e.revisions().get(1);
        for(int n=0;n<3;n++){
            var bad=new FuturesAuthorizedAuditEvidence.Revision(1,r.report(),r.priceSignature(),n==0?r.price().add(BigInteger.ONE):r.price(),n==1?r.observedAt()+1:r.observedAt(),r.provider(),n==2?2L:r.generation());
            var changed=copy(e,e.positionAuthorization(),e.operationAuthorization(),e.linkedWallet(),1,List.of(e.revisions().get(0),bad));
            assertThatThrownBy(()->f.with(changed).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
        }
    }
    @Test void modifiedUserNonceOrSignatureAndOriginalReportReject(){
        var f=v2(0);var e=f.evidence();var a=e.operationAuthorization();
        var bad=new Authorization(V2,a.owner(),null,"0x"+"00".repeat(65),a.intentV2());
        assertThatThrownBy(()->f.with(copy(e,e.positionAuthorization(),bad,e.linkedWallet(),0,e.revisions())).verify()).isInstanceOf(RuntimeException.class);
        var originalChanged=new FuturesAuthorizedAuditEvidence(OID,PID,1,e.positionAuthorization(),a,e.linkedWallet(),V2.engine(),V2.engine(),v2(1).stored().report(),e.originalPriceSignature(),0,e.revisions());
        assertThatThrownBy(()->f.with(originalChanged).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
        var i=a.intentV2();var wrongNonce=new UserIntentV2Codec.Intent(i.owner(),i.relayer(),i.operationId(),i.positionId(),i.accountRef(),i.symbolHash(),i.action(),i.expectedVersion(),i.expectedCallEpoch(),i.side(),i.leverage(),i.entryPriceE8(),i.collateralBefore(),i.collateralAfter(),i.priceMinE8(),i.priceMaxE8(),i.quantityMin(),i.quantityMax(),i.nonce().add(BigInteger.ONE),i.issuedAt(),i.deadline(),i.policyHash());
        var replay=new Authorization(V2,a.owner(),null,a.userSignature(),wrongNonce);
        assertThatThrownBy(()->f.with(copy(e,e.positionAuthorization(),replay,e.linkedWallet(),0,e.revisions())).verify()).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void validServerSignatureDoesNotAuthorizeOutsideUserEconomicBounds(){
        var f=v2(0);var e=f.evidence();var r=e.originalReport();var price=P0.multiply(BigInteger.TWO);
        var outside=new FuturesReport(r.operationId(),r.positionId(),r.accountRef(),r.symbolHash(),r.action(),r.expectedVersion(),r.expectedCallEpoch(),r.side(),r.leverage(),FuturesMath.quantity(MARGIN,10,price),price,r.collateralBefore(),r.collateralAfter(),price,r.observedAt(),r.validUntil(),r.policyHash(),r.executor());
        var rows=List.of(revision(0,V2,outside));
        var changed=new FuturesAuthorizedAuditEvidence(OID,PID,1,e.positionAuthorization(),e.operationAuthorization(),e.linkedWallet(),V2.engine(),V2.engine(),outside,priceSignature(V2,outside),0,rows);
        assertThatThrownBy(()->f.with(changed).verify()).hasMessage("EXECUTION_OUTSIDE_USER_BOUNDS");
    }
    @Test void v1SignatureAndLegacyEvidenceRemainDistinct(){
        var c=new FuturesChainIdentity(V2.chainId(),V2.engine(),V2.executor(),V2.priceSigner(),V2.rules(),"1");var p=position(P0);var o=operation(P0);var r=FuturesReport.from(o,p,c.reportExecutor());
        var approval=new Approval(USER.getAddress(),ZERO,r.validUntil(),c.executor());var a=new Authorization(c,USER.getAddress(),approval,sign(UserIntentCodec.digest(c,r,approval)));
        var e=new FuturesAuthorizedAuditEvidence(OID,PID,1,new Authorization(c,USER.getAddress(),null,null),a,USER.getAddress(),null,null,r,priceSignature(c,r),null,List.of());
        assertThat(FuturesAuthorizedAuditVerifier.verify(c,p,o,stored(c,o,r,a),e).mode()).isEqualTo("B_V1");
        var legacy=new FuturesChainIdentity(c.chainId(),c.engine(),c.executor(),c.priceSigner());var lr=FuturesReport.from(o,p,legacy.executor());
        assertThat(FuturesAuthorizedAuditVerifier.verify(legacy,p,o,stored(legacy,o,lr,null),null).mode()).isEqualTo("LEGACY");
        assertThatThrownBy(()->FuturesAuthorizedAuditVerifier.verify(legacy,p,o,null,e)).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void automaticActionRequiresNoUserIntentOrRevisionButKeepsAuthorizedOwner(){
        var p=position(P0);var o=new Operation(OID,PID,1,Action.START_CALL,1,0,P0,1010,MARGIN,MARGIN,ZERO,ZERO,ZERO,ZERO,"CONFIRMED");
        var a=new Authorization(V2,USER.getAddress(),null,null);var r=FuturesReport.from(o,p,V2.reportExecutor());
        var e=new FuturesAuthorizedAuditEvidence(OID,PID,1,a,a,USER.getAddress(),V2.engine(),V2.engine(),null,null,null,List.of());
        assertThat(FuturesAuthorizedAuditVerifier.verify(V2,p,o,stored(V2,o,r,a),e).intentHash()).isNull();
        var polluted=copy(e,a,a,e.linkedWallet(),0,List.of(revision(0,V2,r)));
        assertThatThrownBy(()->FuturesAuthorizedAuditVerifier.verify(V2,p,o,null,polluted)).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void topUpAndCloseRetainExactPositionEntryAndQuantityConsent(){
        for(var action:List.of(Action.TOP_UP,Action.CLOSE)){
            var p=position(P0);var after=action==Action.TOP_UP?MARGIN.add(BigInteger.TEN.pow(20)):MARGIN;
            var o=new Operation(OID,PID,1,action,1,0,P0,1010,MARGIN,after,after.subtract(MARGIN),ZERO,ZERO,ZERO,"CONFIRMED");var r=FuturesReport.from(o,p,V2.reportExecutor());
            var i=new UserIntentV2Codec.Intent(USER.getAddress(),V2.executor(),r.operationId(),r.positionId(),r.accountRef(),r.symbolHash(),action.ordinal(),BigInteger.ONE,ZERO,0,10,P0,MARGIN,after,P0,P1,p.quantity(),p.quantity(),BigInteger.valueOf(5),BigInteger.valueOf(1000),BigInteger.valueOf(1120),r.policyHash());
            var a=new Authorization(V2,i.owner(),null,sign(UserIntentV2Codec.digest(V2,i)),i);var pa=new Authorization(V2,i.owner(),null,null);
            var e=new FuturesAuthorizedAuditEvidence(OID,PID,1,pa,a,i.owner(),V2.engine(),V2.engine(),r,priceSignature(V2,r),0,List.of(revision(0,V2,r)));
            assertThat(FuturesAuthorizedAuditVerifier.verify(V2,p,o,stored(V2,o,r,a),e).mode()).isEqualTo("B_V2");
        }
    }
    @Test void internalToStringNeverExposesSignaturesOrReports(){var e=v2(1).evidence();assertThat(e.toString()).isEqualTo("FuturesAuthorizedAuditEvidence[internal evidence redacted]");assertThat(e.revisions().get(0).toString()).isEqualTo("FuturesAuditRevision[internal evidence redacted]");}
}
