package com.pricetrack.exchange.futures.audit;

import com.pricetrack.exchange.futures.authorization.UserIntentCodec;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;
import com.pricetrack.exchange.futures.authorization.UserIntentV2Codec;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import java.util.*;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/** Pure DB/signature/raw linkage only. Does NOT prove a canonical receipt, chain owner or journal conservation. */
public final class FuturesAuthorizedAuditVerifier {
    private FuturesAuthorizedAuditVerifier(){}
    /** Safe summary. Callers must independently complete chain/reverse-coverage/accounting checks. */
    public record Result(String mode,Integer activeRevision,String owner,String intentHash,String reportDigest){}
    public static Result verify(FuturesChainIdentity chain,Position position,Operation operation,
            FuturesTransactionStore.Stored stored,FuturesAuthorizedAuditEvidence evidence) {
        try { return inspect(chain,position,operation,stored,evidence); }
        catch(Failure|FuturesAuditSnapshot.Unavailable known){throw known;}
        catch(RuntimeException malformed){throw new Failure("AUDIT_AUTHORIZATION_CONFLICT");}
    }
    private static Result inspect(FuturesChainIdentity chain,Position p,Operation o,
            FuturesTransactionStore.Stored s,FuturesAuthorizedAuditEvidence e) {
        require(p!=null&&o!=null&&p.id().equals(o.positionId())&&p.userId()==o.userId());
        if(!chain.authorized()){
            require(e==null&&(s==null||s.authorization()==null));
            if(s!=null)validateStored(chain,p,o,s,null);
            return new Result("LEGACY",null,null,null,s==null?null:digest(s.report(),chain));
        }
        // Missing evidence is not proof of corruption, and must never be upgraded to MATCH.
        if(e==null||e.positionAuthorization()==null||e.operationAuthorization()==null)throw new FuturesAuditSnapshot.Unavailable();
        var pa=e.positionAuthorization();var a=e.operationAuthorization();
        require(e.operationId().equals(o.id())&&e.positionId().equals(p.id())&&e.userId()==p.userId()
            &&chain.equals(pa.chain())&&chain.equals(a.chain())&&same(pa.owner(),a.owner())
            &&same(e.linkedWallet(),a.owner())&&p.accountRef().equals(UserIntentCodec.account(a.owner())));
        // Position routing is ownership metadata, never another outstanding trade approval.
        require(pa.approval()==null&&pa.userSignature()==null&&pa.intentV2()==null);
        if(chain.v2())require(same(e.v2PositionController(),chain.engine())&&same(e.v2OperationController(),chain.engine()));
        else require(e.v2PositionController()==null&&e.v2OperationController()==null);
        boolean user=UserIntentCodec.userAction(o.action().ordinal());
        FuturesReport active=null;String signature=null,intentHash=null;
        if(chain.v2()&&user){
            require(e.activeRevision()!=null&&e.activeRevision()>=0&&e.activeRevision()<=1
                &&e.revisions().size()==e.activeRevision()+1&&e.originalReport()!=null&&e.originalPriceSignature()!=null);
            var sorted=e.revisions().stream().sorted(Comparator.comparingInt(FuturesAuthorizedAuditEvidence.Revision::revision)).toList();
            for(int n=0;n<sorted.size();n++){
                var r=sorted.get(n);require(r.revision()==n&&r.report()!=null&&r.price()!=null
                    &&r.price().equals(r.report().priceE8())&&r.observedAt()==r.report().observedAt().longValueExact()
                    &&r.provider()!=null&&!r.provider().isBlank());
                require(same(r.provider(),sorted.get(0).provider())&&Objects.equals(r.generation(),sorted.get(0).generation()));
                checkReport(chain,p,o,r.report(),r.priceSignature(),a,false);
            }
            require(sorted.get(0).report().equals(e.originalReport())&&Objects.equals(sorted.get(0).priceSignature(),e.originalPriceSignature()));
            var selected=sorted.get(e.activeRevision());active=selected.report();signature=selected.priceSignature();
            intentHash=Numeric.toHexString(UserIntentV2Codec.digest(chain,a.intentV2()));
        }else{
            require(e.activeRevision()==null&&e.revisions().isEmpty());
            if(user){
                require(!chain.v2()&&e.originalReport()!=null&&e.originalPriceSignature()!=null);
                active=e.originalReport();signature=e.originalPriceSignature();
                checkReport(chain,p,o,active,signature,a,true);
                intentHash=Numeric.toHexString(UserIntentCodec.digest(chain,active,a.approval()));
            }else{
                require(a.approval()==null&&a.userSignature()==null&&a.intentV2()==null
                    &&e.originalReport()==null&&e.originalPriceSignature()==null);
                if(s!=null){active=s.report();signature=s.signature();}
            }
        }
        if(active!=null)checkReport(chain,p,o,active,signature,a,true);
        if(s!=null){
            require(Objects.equals(s.authorization(),a)&&Objects.equals(s.report(),active)&&Objects.equals(s.signature(),signature));
            validateStored(chain,p,o,s,a);
        }
        return new Result(chain.v2()?"B_V2":"B_V1",e.activeRevision(),a.owner(),intentHash,active==null?null:digest(active,chain));
    }
    private static void checkReport(FuturesChainIdentity c,Position p,Operation o,FuturesReport r,String signature,Authorization a,boolean current){
        require(r.operationId().equals(Hash.sha3String(o.id()))&&r.positionId().equals(Hash.sha3String(p.id()))
            &&r.accountRef().equals(p.accountRef())&&r.action()==o.action().ordinal()
            &&r.validUntil().equals(r.observedAt().add(java.math.BigInteger.valueOf(30))));
        UserIntentCodec.verify(r,signature,a);
        if(current)require(r.equals(FuturesReport.from(o,p,c.reportExecutor())));
    }
    private static void validateStored(FuturesChainIdentity c,Position p,Operation o,FuturesTransactionStore.Stored s,Authorization a){
        require(s.operationId().equals(o.id())&&c.chainId().equals(s.chainId())&&same(c.engine(),s.engine())
            &&same(c.executor(),s.sender())&&s.status().equals(o.state())
            &&s.report().equals(FuturesReport.from(o,p,c.reportExecutor()))
            &&FuturesReportCodec.signedBy(s.report(),c.chainId(),c.priceContract(),s.signature(),c.priceSigner()));
        FuturesTransactionStore.validateRaw(c,s.report(),s.signature(),s.raw(),s.hash(),s.nonce(),a);
    }
    private static String digest(FuturesReport r,FuturesChainIdentity c){return Numeric.toHexString(FuturesReportCodec.digest(r,c.chainId(),c.priceContract()));}
    private static boolean same(String a,String b){return a!=null&&b!=null&&a.equalsIgnoreCase(b);}
    private static void require(boolean valid){if(!valid)throw new Failure("AUDIT_AUTHORIZATION_CONFLICT");}
}
