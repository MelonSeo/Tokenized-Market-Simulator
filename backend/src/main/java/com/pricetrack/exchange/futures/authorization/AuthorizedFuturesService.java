package com.pricetrack.exchange.futures.authorization;

import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.web3j.crypto.*;

/** JWT identity is supplied by the controller, never by a client userId or private key. */
public final class AuthorizedFuturesService {
    public interface ChainAccess { void verify(); boolean eoa(String owner); BigInteger nonce(String owner); }
    public record Request(Action action,String positionId,long expectedVersion,Side side,int leverage,BigInteger margin) {}
    public record DraftView(String id,String positionId,String operationId,String owner,String rules,Instant expiresAt,Map<String,Object> typedData) {}
    private final AuthorizedFuturesStore store;private final FuturesLedger ledger;private final FuturesMarketGate market;
    private final FuturesChainIdentity chain;private final Credentials priceSigner;private final ChainAccess rpc;private final Runnable wake;private final String origin;
    public AuthorizedFuturesService(AuthorizedFuturesStore store,FuturesLedger ledger,FuturesMarketGate market,FuturesChainIdentity chain,Credentials signer,ChainAccess rpc,Runnable wake,String origin){
        if(!chain.authorized()||!chain.priceSigner().equalsIgnoreCase(signer.getAddress()))throw new IllegalArgumentException("AUTHORIZED_SIGNER_CONFLICT");
        var uri=java.net.URI.create(origin);if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("http","https").contains(uri.getScheme())||(!uri.getPath().isEmpty()&&!uri.getPath().equals("/")))throw new IllegalArgumentException("WALLET_ORIGIN_REQUIRED");
        this.store=store;this.ledger=ledger;this.market=market;this.chain=chain;this.priceSigner=signer;this.rpc=rpc;this.wake=wake;this.origin=origin;
    }
    public AuthorizedFuturesStore.Challenge challenge(long uid,String wallet,String browserOrigin){
        if(!origin.equals(browserOrigin))throw new Failure("WALLET_ORIGIN_CONFLICT");wallet=UserIntentCodec.address(wallet);rpc.verify();if(!rpc.eoa(wallet))throw new Failure("EOA_REQUIRED");
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);
        return store.challenge(uid,wallet,origin,chain.chainId(),market.now(),HexFormat.of().formatHex(random));
    }
    public void link(long uid,String challenge,String signature,String browserOrigin){
        if(!origin.equals(browserOrigin))throw new Failure("WALLET_ORIGIN_CONFLICT");var c=store.challenge(uid,challenge);rpc.verify();if(!rpc.eoa(c.wallet()))throw new Failure("EOA_REQUIRED");store.link(uid,challenge,signature,market.now());
    }
    public DraftView draft(long uid,String key,Request request){
        if(chain.v2())throw new Failure("FUTURES_CONTRACT_VERSION_CONFLICT");
        var old=store.byKey(uid,key);if(old.isPresent()) {checkRequest(old.get().report(),request);return view(old.get());}
        if(request.action()==null||!Set.of(Action.OPEN,Action.TOP_UP,Action.CLOSE).contains(request.action()))throw new Failure("USER_ACTION_REQUIRED");
        String owner=store.wallet(uid);rpc.verify();if(!rpc.eoa(owner))throw new Failure("EOA_REQUIRED");BigInteger nonce=rpc.nonce(owner);
        var mark=market.capture();String pid=request.action()==Action.OPEN?UUID.randomUUID().toString():request.positionId(),op=UUID.randomUUID().toString();
        var p=request.action()==Action.OPEN?null:ledger.position(pid,uid);
        if(p!=null&&(!p.status().equals("OPEN")||p.pendingOperation()!=null||p.version()!=request.expectedVersion()||!store.position(pid,uid).chain().equals(chain)))throw new Failure("AUTHORIZED_POSITION_CONFLICT");
        BigInteger before=p==null?BigInteger.ZERO:p.collateral(),after=before,quantity,entry;Side side;int leverage;
        if(p==null){side=request.side();leverage=request.leverage();if(side==null||request.margin()==null)throw new Failure("INVALID_MARGIN");after=request.margin();quantity=FuturesMath.quantity(after,leverage,mark.price());entry=mark.price();}
        else{side=p.side();leverage=p.leverage();quantity=p.quantity();entry=p.entryPrice();
            if(request.action()==Action.TOP_UP){if(request.margin()==null||request.margin().signum()<=0)throw new Failure("INVALID_MARGIN");after=before.add(request.margin());FuturesMath.quantity(after,leverage,entry);}}
        var r=new FuturesReport(Hash.sha3String(op),Hash.sha3String(pid),UserIntentCodec.account(owner),FuturesReport.SYMBOL_HASH,request.action().ordinal(),BigInteger.valueOf(p==null?0:p.version()),BigInteger.valueOf(p==null?0:p.callEpoch()),side.ordinal(),leverage,quantity,entry,before,after,mark.price(),BigInteger.valueOf(mark.observedAt().getEpochSecond()),BigInteger.valueOf(mark.observedAt().getEpochSecond()+30),FuturesLedger.POLICY_HASH,chain.engine());
        var a=new Authorization(chain,owner,new Approval(owner,nonce,r.validUntil(),chain.executor()),null);
        String ps=FuturesReportCodec.sign(r,chain.chainId(),chain.rules(),priceSigner.getEcKeyPair());market.validate(mark);
        return view(store.create(uid,key,pid,op,r,ps,a,mark,market.now()));
    }
    private void checkRequest(FuturesReport r,Request q){
        if(q.action()==null||r.action()!=q.action().ordinal()||q.action()!=Action.OPEN&&(!r.positionId().equals(Hash.sha3String(q.positionId()))||r.expectedVersion().longValueExact()!=q.expectedVersion())
            ||q.action()==Action.OPEN&&(q.side()==null||r.side()!=q.side().ordinal()||r.leverage()!=q.leverage())
            ||(q.action()==Action.OPEN||q.action()==Action.TOP_UP)&&!r.collateralAfter().subtract(r.collateralBefore()).equals(q.margin()))throw new Failure("IDEMPOTENCY_CONFLICT");
    }
    private DraftView view(AuthorizedFuturesStore.Draft d){if(!d.authorization().chain().equals(chain))throw new Failure("AUTHORIZED_DEPLOYMENT_CONFLICT");return new DraftView(d.id(),d.position(),d.operation(),d.authorization().owner(),chain.rules(),d.expiresAt(),UserIntentCodec.typedData(chain,d.report(),d.authorization().approval()));}
    public Operation submit(long uid,String id,String key,String signature){
        if(chain.v2())throw new Failure("FUTURES_CONTRACT_VERSION_CONFLICT");
        var d=store.draft(uid,id,false);if(!d.authorization().chain().equals(chain))throw new Failure("AUTHORIZED_DEPLOYMENT_CONFLICT");
        if(!d.state().equals("ACCEPTED")){rpc.verify();if(!rpc.eoa(d.authorization().owner()))throw new Failure("EOA_REQUIRED");if(!rpc.nonce(d.authorization().owner()).equals(d.authorization().approval().nonce()))throw new Failure("USER_NONCE_CHANGED");market.validate(d.mark());}
        Operation op=store.accept(uid,id,key,signature,market.now());wake.run();return op;
    }
}
