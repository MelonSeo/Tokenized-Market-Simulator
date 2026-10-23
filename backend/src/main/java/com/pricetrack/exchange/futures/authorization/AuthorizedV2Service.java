package com.pricetrack.exchange.futures.authorization;

import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.FuturesMarketGate;
import java.math.BigInteger;
import java.util.*;
import org.web3j.crypto.*;

/** Stable signed conditions; capture/sign execution report only AFTER verifying user consent. */
public final class AuthorizedV2Service {
    public record Request(@jakarta.validation.constraints.NotNull Action action,@jakarta.validation.constraints.Size(max=36) String positionId,@jakarta.validation.constraints.Min(0) long expectedVersion,@jakarta.validation.constraints.NotNull Side side,int leverage,@jakarta.validation.constraints.NotNull BigInteger margin,
        @jakarta.validation.constraints.NotNull BigInteger priceMinE8,@jakarta.validation.constraints.NotNull BigInteger priceMaxE8,@jakarta.validation.constraints.NotNull BigInteger quantityMin,@jakarta.validation.constraints.NotNull BigInteger quantityMax,@jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120) int ttlSeconds){}
    public record DraftView(String id,String positionId,String operationId,Map<String,Object> typedData){}
    private final AuthorizedV2Store store;private final AuthorizedFuturesStore common;private final FuturesLedger ledger;private final FuturesMarketGate market;
    private final FuturesChainIdentity chain;private final Credentials signer;private final AuthorizedFuturesService.ChainAccess rpc;private final Runnable wake;
    public AuthorizedV2Service(AuthorizedV2Store store,AuthorizedFuturesStore common,FuturesLedger ledger,FuturesMarketGate market,FuturesChainIdentity chain,Credentials signer,AuthorizedFuturesService.ChainAccess rpc,Runnable wake){
        if(!chain.v2()||!signer.getAddress().equalsIgnoreCase(chain.priceSigner()))throw new IllegalArgumentException("AUTHORIZED_SIGNER_CONFLICT");this.store=store;this.common=common;this.ledger=ledger;this.market=market;this.chain=chain;this.signer=signer;this.rpc=rpc;this.wake=wake;
    }
    public DraftView draft(long uid,String key,Request r){
        return FuturesDiagnostics.work(FuturesDiagnostics.Stage.DRAFT,()->draftInternal(uid,key,r));
    }
    private DraftView draftInternal(long uid,String key,Request r){
        var old=store.byKey(uid,key);var meta=new AuthorizedV2Store.Metadata(chain,r);if(old.isPresent()){if(!old.get().metadata().equals(meta))throw new Failure("IDEMPOTENCY_CONFLICT");return view(old.get());}
        if(r.action()==null||!Set.of(Action.OPEN,Action.TOP_UP,Action.CLOSE).contains(r.action())||r.ttlSeconds()<1||r.ttlSeconds()>120||r.margin()==null||r.side()==null)throw new Failure("INVALID_USER_INTENT");
        String owner=common.wallet(uid);rpc.verify();if(!rpc.eoa(owner))throw new Failure("EOA_REQUIRED");var nonce=rpc.nonce(owner);
        String pid=r.action()==Action.OPEN?UUID.randomUUID().toString():r.positionId(),op=UUID.randomUUID().toString();
        var p=r.action()==Action.OPEN?null:ledger.position(pid,uid);
        if(p==null){if(r.expectedVersion()!=0||r.positionId()!=null)throw new Failure("INVALID_USER_INTENT");FuturesMath.quantity(r.margin(),r.leverage(),r.priceMinE8());}
        else if(!common.position(pid,uid).chain().equals(chain)||!p.status().equals("OPEN")||p.pendingOperation()!=null||p.version()!=r.expectedVersion()||p.side()!=r.side()||p.leverage()!=r.leverage()||!r.quantityMin().equals(p.quantity())||!r.quantityMax().equals(p.quantity()))throw new Failure("AUTHORIZED_POSITION_CONFLICT");
        if(r.action()==Action.CLOSE&&r.margin().signum()!=0||r.action()==Action.TOP_UP&&r.margin().signum()<=0)throw new Failure("INVALID_MARGIN");
        BigInteger before=p==null?BigInteger.ZERO:p.collateral(),after=r.action()==Action.CLOSE?before:before.add(r.margin()),issued=BigInteger.valueOf(market.now().getEpochSecond());
        var i=new UserIntentV2Codec.Intent(owner,chain.executor(),Hash.sha3String(op),Hash.sha3String(pid),UserIntentCodec.account(owner),FuturesReport.SYMBOL_HASH,r.action().ordinal(),BigInteger.valueOf(p==null?0:p.version()),BigInteger.valueOf(p==null?0:p.callEpoch()),r.side().ordinal(),r.leverage(),p==null?BigInteger.ZERO:p.entryPrice(),before,after,r.priceMinE8(),r.priceMaxE8(),r.quantityMin(),r.quantityMax(),nonce,issued,issued.add(BigInteger.valueOf(r.ttlSeconds())),FuturesLedger.POLICY_HASH);
        return view(store.create(uid,key,pid,op,i,meta,market.now()));
    }
    private DraftView view(AuthorizedV2Store.Draft d){if(!d.metadata().chain().equals(chain))throw new Failure("AUTHORIZED_DEPLOYMENT_CONFLICT");return new DraftView(d.id(),d.position(),d.operation(),UserIntentV2Codec.typedData(chain,d.intent()));}
    public Operation submit(long uid,String id,String key,String signature){
        return FuturesDiagnostics.work(FuturesDiagnostics.Stage.SUBMIT,()->submitInternal(uid,id,key,signature));
    }
    private Operation submitInternal(long uid,String id,String key,String signature){
        var d=store.draft(uid,id,false);if(!d.metadata().chain().equals(chain))throw new Failure("AUTHORIZED_DEPLOYMENT_CONFLICT");
        if(d.state().equals("ACCEPTED"))return store.replay(d,key,signature);
        if(!d.key().equals(FuturesLedger.fingerprint(key)))throw new Failure("IDEMPOTENCY_CONFLICT");
        var i=d.intent();UserIntentV2Codec.verifyConsent(chain,i,signature);UserIntentV2Codec.time(i,market.now().getEpochSecond());
        rpc.verify();if(!rpc.eoa(i.owner())||!rpc.nonce(i.owner()).equals(i.nonce()))throw new Failure("USER_NONCE_OR_EOA_CHANGED");
        var mark=FuturesDiagnostics.work(FuturesDiagnostics.Stage.MARKET_CAPTURE,market::capture);var p=i.action()==0?null:ledger.position(d.position(),uid);
        var q=i.action()==0?FuturesMath.quantity(i.collateralAfter(),i.leverage(),mark.price()):p.quantity();
        var report=new FuturesReport(i.operationId(),i.positionId(),i.accountRef(),i.symbolHash(),i.action(),i.expectedVersion(),i.expectedCallEpoch(),i.side(),i.leverage(),q,i.action()==0?mark.price():p.entryPrice(),i.collateralBefore(),i.collateralAfter(),mark.price(),BigInteger.valueOf(mark.observedAt().getEpochSecond()),BigInteger.valueOf(mark.observedAt().getEpochSecond()+30),i.policyHash(),chain.engine());
        String ps=FuturesDiagnostics.work(FuturesDiagnostics.Stage.REPORT_SIGN,()->FuturesReportCodec.sign(report,chain.chainId(),chain.rules(),signer.getEcKeyPair()));UserIntentV2Codec.verifyReport(report,ps,new Authorization(chain,i.owner(),null,signature,i));market.validate(mark);
        var op=FuturesDiagnostics.work(FuturesDiagnostics.Stage.PREPARE_COMMIT,()->store.accept(uid,id,key,signature,report,ps,mark,market.now()));wake.run();return op;
    }
    public Map<String,Object> nonce(long uid){return FuturesDiagnostics.work(FuturesDiagnostics.Stage.NONCE_READ,()->{String owner=common.wallet(uid);rpc.verify();return Map.of("owner",owner,"nonce",rpc.nonce(owner).toString(),"verification","OBSERVED_LATEST_NOT_CANCELLATION_RECEIPT");});}
}
