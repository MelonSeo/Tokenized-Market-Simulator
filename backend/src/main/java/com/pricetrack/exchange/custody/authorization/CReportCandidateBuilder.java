package com.pricetrack.exchange.custody.authorization;

import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.proof.CRulesTransitionModels;
import com.pricetrack.exchange.custody.proof.CRulesTransitionVerifier;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.custody.runtime.CCustodyRpcReader.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.runtime.FuturesMarketGate;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;
import java.math.BigInteger;
import java.time.*;
import java.util.Objects;
import java.util.function.Function;
import org.web3j.crypto.Hash;

/** Unsigned candidate only. Consent/nonce admission, Oracle signing, revision, raw and execution are separate boundaries. */
public final class CReportCandidateBuilder {
    public static final String SCOPE="UNSIGNED_C_REPORT_CANDIDATE_NOT_PERSISTED_PREPARED_SIGNED_OR_EXECUTED";
    public record Candidate(Report report,String reportDigest,FuturesMarketGate.Mark market,
                            Observation<AdmissionPrestate> prestate,FuturesMath.Mark calculatedMark) {
        @com.fasterxml.jackson.annotation.JsonProperty("scope") public String scope(){return SCOPE;}
        @Override public String toString(){return "CUnsignedReportCandidate[operationId="+report.operationId()+"]";}
    }
    private final CCustodyIdentity identity;private final Clock clock;private final FuturesMarketGate market;
    private final Function<Intent,Observation<AdmissionPrestate>> observe;
    public CReportCandidateBuilder(CCustodyRpcReader reader,MarketPriceService market,Clock clock,String marketSymbol){
        this(Objects.requireNonNull(reader).identity(),reader::admissionPrestate,market,clock,marketSymbol);
    }
    /** Package-private observation seam for no-socket unit tests, never a public caller-supplied prestate. */
    CReportCandidateBuilder(CCustodyIdentity identity,Function<Intent,Observation<AdmissionPrestate>> observe,
                            MarketPriceService service,Clock clock,String marketSymbol){
        this.identity=Objects.requireNonNull(identity);this.clock=Objects.requireNonNull(clock);this.observe=Objects.requireNonNull(observe);
        if(marketSymbol==null||marketSymbol.isEmpty()||!Hash.sha3String(marketSymbol).equals(identity.symbolHash()))throw fail("SYMBOL_BINDING");
        Objects.requireNonNull(service);
        // Keep the existing product eligibility/freshness/generation/exact-price policy. Bind its symbol before it drops the snapshot field.
        MarketPriceProvider bound=new MarketPriceProvider(){
            private MarketPriceSnapshot check(MarketPriceSnapshot p){if(p==null||!marketSymbol.equals(p.symbol()))throw fail("MARKET_SYMBOL");return p;}
            public MarketPriceSnapshot current(){return check(service.current());}
            @Override public TradingPriceContext currentForTrading(){var c=service.currentForTrading();check(c.snapshot());return c;}
        };
        this.market=new FuturesMarketGate(new MarketPriceService(bound),clock);
    }
    public Candidate build(Intent intent){
        Instant started=clock.instant();CConsentModels.validate(identity,intent,started);
        long began=System.nanoTime();var pre=observe.apply(intent);checkPrestate(pre,intent,clock.instant());
        // Select the execution price only after the fresh on-chain prerequisite read, not before the user's signature delay.
        var fixed=market.capture();BigInteger price=fixed.price();
        if(price.compareTo(intent.priceMinE8())<0||price.compareTo(intent.priceMaxE8())>0)throw fail("PRICE_BOUNDS");
        boolean open=intent.action().signum()==0;
        BigInteger quantity=open?FuturesMath.quantity(intent.collateralAfter(),intent.leverage().intValueExact(),price):new BigInteger(pre.value().position().quantity());
        if(quantity.compareTo(intent.quantityMin())<0||quantity.compareTo(intent.quantityMax())>0)throw fail("QUANTITY_BOUNDS");
        BigInteger observed=BigInteger.valueOf(fixed.observedAt().getEpochSecond());
        Report report=new Report(intent.operationId(),intent.positionId(),intent.accountRef(),intent.symbolHash(),intent.action(),intent.expectedVersion(),intent.expectedCallEpoch(),
            intent.side(),intent.leverage(),quantity,open?price:intent.entryPriceE8(),intent.collateralBefore(),intent.collateralAfter(),price,observed,
            observed.add(CRulesTransitionVerifier.REPORT_TTL),intent.policyHash(),identity.controller().address());
        var p=pre.value().position();
        var before=new CRulesTransitionModels.Position(p.accountRef(),n(p.side()),n(p.leverage()),n(p.quantity()),n(p.entryPriceE8()),n(p.historicalCollateral()),
            CRulesTransitionModels.Status.valueOf(p.status()),n(p.version()),n(p.callEpoch()),n(p.deadline()),n(p.lastObservedAt()));
        var input=new CRulesTransitionModels.Input(CRulesTransitionModels.Action.values()[intent.action().intValueExact()],report,
            new CRulesTransitionModels.Terms(identity.controller().address(),identity.symbolHash(),identity.policyHash()),before,n(pre.block().timestamp()),false,
            pre.value().activePosition(),new CRulesTransitionModels.PrestateKeys(intent.positionId(),intent.accountRef(),intent.operationId()));
        var predicted=new CRulesTransitionVerifier().verify(input);
        String digest=new CEip712Verifier().reportDigest(report,identity.chainId(),identity.rules().address());
        market.validate(fixed); // No automatic refresh or retry when a tick/provider/generation changes.
        Instant finished=clock.instant();
        if(finished.isBefore(started)||System.nanoTime()-began>=CCustodyRpcReader.SNAPSHOT_NANOS)throw fail("PRESTATE_EXPIRED");
        checkPrestate(pre,intent,finished);CConsentModels.validate(identity,intent,finished);
        return new Candidate(report,digest,fixed,pre,predicted.mark());
    }
    /** Recheck the fixed candidate after an Oracle response. Never refreshes price, intent, evidence or signature. */
    public void revalidate(Candidate candidate){
        if(candidate==null||candidate.report()==null||candidate.market()==null||candidate.prestate()==null||candidate.prestate().value()==null||candidate.prestate().value().intent()==null)throw fail("CANDIDATE_SCOPE");
        var pre=candidate.prestate();var i=pre.value().intent();var r=candidate.report();var m=candidate.market();Instant now=clock.instant();checkPrestate(pre,i,now);CConsentModels.validate(identity,i,now);
        boolean open=i.action().signum()==0;BigInteger quantity=open?FuturesMath.quantity(i.collateralAfter(),i.leverage().intValueExact(),m.price()):n(pre.value().position().quantity());
        BigInteger observed=BigInteger.valueOf(m.observedAt().getEpochSecond());
        Report expected=new Report(i.operationId(),i.positionId(),i.accountRef(),i.symbolHash(),i.action(),i.expectedVersion(),i.expectedCallEpoch(),i.side(),i.leverage(),quantity,
            open?m.price():i.entryPriceE8(),i.collateralBefore(),i.collateralAfter(),m.price(),observed,observed.add(CRulesTransitionVerifier.REPORT_TTL),i.policyHash(),identity.controller().address());
        if(!expected.equals(r)||!new CEip712Verifier().reportDigest(r,identity.chainId(),identity.rules().address()).equals(candidate.reportDigest())
            ||r.priceE8().compareTo(i.priceMinE8())<0||r.priceE8().compareTo(i.priceMaxE8())>0||quantity.compareTo(i.quantityMin())<0||quantity.compareTo(i.quantityMax())>0
            ||BigInteger.valueOf(now.getEpochSecond()).compareTo(r.validUntil())>0)throw fail("CANDIDATE_SCOPE");
        market.validate(m);
        Instant finished=clock.instant();if(finished.isBefore(now))throw fail("PRESTATE_EXPIRED");checkPrestate(pre,i,finished);CConsentModels.validate(identity,i,finished);
    }
    private void checkPrestate(Observation<AdmissionPrestate> pre,Intent i,Instant now){
        if(pre==null||pre.value()==null||pre.block()==null||pre.observedAt()==null||pre.value().position()==null
            ||!identity.deploymentId().equals(pre.deploymentId())||!i.equals(pre.value().intent())||!i.nonce().toString().equals(pre.value().userNonce()))throw fail("PRESTATE_SCOPE");
        if(pre.observedAt().isAfter(now)||Duration.between(pre.observedAt(),now).compareTo(Duration.ofNanos(CCustodyRpcReader.SNAPSHOT_NANOS))>=0)throw fail("PRESTATE_EXPIRED");
        CConsentModels.validate(identity,i,Instant.ofEpochSecond(n(pre.block().timestamp()).longValueExact()));
    }
    private static BigInteger n(String value){return CCustodyIdentity.uint(value);}
    private static CCustodyFailure fail(String suffix){return new CCustodyFailure("C_REPORT_CANDIDATE_"+suffix,409);}
}
