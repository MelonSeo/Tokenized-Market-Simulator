package com.pricetrack.exchange.custody.direct;

import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.authorization.*;
import com.pricetrack.exchange.custody.auth.CWalletProofService;
import com.pricetrack.exchange.custody.direct.CDirectModels.*;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.custody.runtime.CCustodyRpcReader.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.*;
import static com.pricetrack.exchange.custody.direct.CDirectExecutionStore.fail;

/** Owner-transaction preparation only. No user signature creation, relayer, raw signing, broadcast or financial ledger. */
public final class CDirectService {
    private final CCustodyIdentity identity;private final CConsentStore drafts;private final CDirectExecutionStore executions;
    private final Clock clock;private final CDirectOraclePort oracle;private final LongFunction<String> wallet;
    private final Function<String,Observation<Account>> accounts;private final BiFunction<String,String,Observation<PositionView>> positions;
    private final Function<Intent,CReportCandidateBuilder.Candidate> build;private final Consumer<CReportCandidateBuilder.Candidate> revalidate;private final SecureRandom random=new SecureRandom();
    public CDirectService(CConsentStore drafts,CDirectExecutionStore executions,CWalletProofService proof,CCustodyRpcReader reader,
                          CReportCandidateBuilder builder,Clock clock,CDirectOraclePort oracle){
        this(reader.identity(),drafts,executions,clock,oracle,uid->proof.wallet(uid).wallet(),reader::account,reader::position,builder::build,builder::revalidate);
    }
    /** Trusted no-socket test seams, not public request-supplied identity, observations or Oracle. */
    CDirectService(CCustodyIdentity identity,CConsentStore drafts,CDirectExecutionStore executions,Clock clock,CDirectOraclePort oracle,
                   LongFunction<String> wallet,Function<String,Observation<Account>> accounts,BiFunction<String,String,Observation<PositionView>> positions,
                   Function<Intent,CReportCandidateBuilder.Candidate> build,Consumer<CReportCandidateBuilder.Candidate> revalidate){
        this.identity=Objects.requireNonNull(identity);this.drafts=Objects.requireNonNull(drafts);this.executions=Objects.requireNonNull(executions);this.clock=Objects.requireNonNull(clock);this.oracle=Objects.requireNonNull(oracle);this.wallet=Objects.requireNonNull(wallet);this.accounts=Objects.requireNonNull(accounts);this.positions=Objects.requireNonNull(positions);this.build=Objects.requireNonNull(build);this.revalidate=Objects.requireNonNull(revalidate);
    }
    public DraftView draft(long uid,String key,Request body){
        user(uid);String owner=CCustodyIdentity.address(wallet.apply(uid));Request r=normalize(body);CConsentModels.keyHash(key);
        var prior=drafts.findByKey(uid,key);if(prior.isPresent()){Intent i=prior.get().intent();owned(i,owner);matches(r,i);return CDirectModels.draft(identity,i);}
        var account=accounts.apply(owner);if(!identity.deploymentId().equals(account.deploymentId())||!owner.equals(account.value().owner()))throw fail("RPC_SCOPE",503);
        BigInteger nonce=number(account.value().userNonce());if(nonce.equals(CAbiModels.UINT_MAX))throw fail("NONCE_OVERFLOW",409);
        BigInteger wall=BigInteger.valueOf(clock.instant().getEpochSecond()),chain=number(account.block().timestamp()),issued=wall.min(chain),deadline=issued.add(BigInteger.valueOf(120));
        boolean open=r.action().equals("OPEN");BigInteger action=BigInteger.valueOf(open?0:4),version=BigInteger.ZERO,epoch=BigInteger.ZERO,entry=BigInteger.ZERO,before=BigInteger.ZERO;
        String position=open?newId():r.positionId();
        if(!open){var p=positions.apply(owner,position);if(!identity.deploymentId().equals(p.deploymentId())||!position.equals(p.value().positionId())||!owner.equals(p.value().owner())||!Set.of("ACTIVE","MARGIN_CALL").contains(p.value().status()))throw fail("POSITION",409);
            var v=p.value();version=number(v.version());epoch=number(v.callEpoch());entry=number(v.entryPriceE8());before=number(v.historicalCollateral());
            if(!number(r.leverage()).equals(number(v.leverage()))||!side(r.side()).equals(number(v.side()))||!number(r.collateralAfterE18()).equals(before)||!number(r.quantityMinE18()).equals(number(v.quantity()))||!number(r.quantityMaxE18()).equals(number(v.quantity())))throw fail("POSITION_TERMS",409);
        }
        Intent i=new Intent(owner,identity.relayer(),newId(),position,CConsentModels.accountRef(owner,identity.symbolHash()),identity.symbolHash(),action,version,epoch,
            side(r.side()),number(r.leverage()),entry,before,number(r.collateralAfterE18()),number(r.priceMinE8()),number(r.priceMaxE8()),number(r.quantityMinE18()),number(r.quantityMaxE18()),nonce,issued,deadline,identity.policyHash());
        CConsentModels.validate(identity,i,clock.instant());CConsentModels.validate(identity,i,Instant.ofEpochSecond(chain.longValueExact()));
        return CDirectModels.draft(identity,drafts.createDraft(uid,key,i).intent());
    }
    public DraftView findIntent(long uid,String id){return CDirectModels.draft(identity,intent(uid,id));}
    public ExecutionView findExecution(long uid,String id){Intent i=intent(uid,id);return view(executions.find(uid,i.operationId()).orElseThrow(()->fail("EXECUTION_NOT_READY",409)));}
    public ExecutionView execute(long uid,String id){
        Intent i=intent(uid,id);var old=executions.find(uid,i.operationId());if(old.isPresent())return view(old.get());
        if(!executions.claim(uid,i.operationId()))return view(executions.find(uid,i.operationId()).orElseThrow(()->fail("EXECUTION_CLAIMED_NOT_READY",409)));
        // Once claimed, every exception remains fail-closed. There is no automatic Oracle re-request or new price revision.
        var candidate=build.apply(i);if(candidate==null||candidate.prestate()==null||candidate.prestate().value()==null||!i.equals(candidate.prestate().value().intent()))throw fail("CANDIDATE_SCOPE",503);
        String signature;try{signature=oracle.sign(identity,candidate);new CEip712Verifier().verifyReport(candidate.report(),identity.chainId(),identity.rules().address(),identity.oracle(),signature);}
        catch(RuntimeException invalid){throw fail("ORACLE_UNAVAILABLE_OR_INVALID",503);}
        revalidate.accept(candidate);
        Execution e=new Execution(i,candidate.report(),signature,candidate.reportDigest(),candidate.market());
        return view(executions.save(uid,e));
    }
    private Intent intent(long uid,String id){user(uid);String owner=CCustodyIdentity.address(wallet.apply(uid));Intent i=drafts.findDraft(uid,id).intent();owned(i,owner);return i;}
    private void owned(Intent i,String owner){if(!owner.equals(i.owner())||!Set.of(BigInteger.ZERO,BigInteger.valueOf(4)).contains(i.action()))throw fail("OWNER_OR_ACTION",403);}
    private ExecutionView view(Execution e){return CDirectModels.execution(identity,e,executions.calldata(e));}
    private static Request normalize(Request r){
        if(r==null||!Set.of("OPEN","CLOSE").contains(r.action()==null?"":r.action())||!Set.of("LONG","SHORT").contains(r.side()==null?"":r.side())||!Set.of("2","5","10").contains(r.leverage()==null?"":r.leverage()))throw fail("INVALID_REQUEST",400);
        if(r.action().equals("OPEN")&&r.positionId()!=null)throw fail("INVALID_POSITION",400);
        String position=r.action().equals("CLOSE")?hash(r.positionId()):null;
        BigInteger amount=number(r.collateralAfterE18()),pmin=number(r.priceMinE8()),pmax=number(r.priceMaxE8()),qmin=number(r.quantityMinE18()),qmax=number(r.quantityMaxE18());
        if(amount.signum()<=0||amount.compareTo(FuturesMath.AMOUNT_LIMIT)>=0||pmin.signum()<=0||pmin.compareTo(pmax)>0||pmax.compareTo(FuturesMath.PRICE_LIMIT)>=0||qmin.signum()<=0||qmin.compareTo(qmax)>0||qmax.compareTo(FuturesMath.AMOUNT_LIMIT)>=0)throw fail("INVALID_BOUNDS",400);
        return new Request(r.action(),position,r.side(),r.leverage(),amount.toString(),pmin.toString(),pmax.toString(),qmin.toString(),qmax.toString());
    }
    private static void matches(Request r,Intent i){
        if(!i.action().equals(BigInteger.valueOf(r.action().equals("OPEN")?0:4))||r.action().equals("CLOSE")&&!i.positionId().equals(r.positionId())||!i.side().equals(side(r.side()))||!i.leverage().equals(number(r.leverage()))||!i.collateralAfter().equals(number(r.collateralAfterE18()))||!i.priceMinE8().equals(number(r.priceMinE8()))||!i.priceMaxE8().equals(number(r.priceMaxE8()))||!i.quantityMin().equals(number(r.quantityMinE18()))||!i.quantityMax().equals(number(r.quantityMaxE18())))throw fail("IDEMPOTENCY_CONFLICT",409);
    }
    private String newId(){byte[] b=new byte[32];do{random.nextBytes(b);}while(Arrays.equals(b,new byte[32]));return "0x"+HexFormat.of().formatHex(b);}
    private static BigInteger side(String s){return s.equals("LONG")?BigInteger.ZERO:BigInteger.ONE;}
    private static BigInteger number(String value){try{return CCustodyIdentity.uint(value);}catch(RuntimeException e){throw fail("INVALID_INTEGER",400);}}
    private static String hash(String value){try{return CAbiModels.hash(value);}catch(RuntimeException e){throw fail("INVALID_POSITION",400);}}
    private static void user(long uid){if(uid<=0)throw fail("AUTHENTICATION_REQUIRED",401);}
}
