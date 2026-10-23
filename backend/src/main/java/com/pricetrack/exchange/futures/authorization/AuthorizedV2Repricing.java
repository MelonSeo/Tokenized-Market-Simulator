package com.pricetrack.exchange.futures.authorization;

import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.runtime.FuturesMarketGate;
import java.math.BigInteger;
import java.util.Objects;
import org.web3j.crypto.Credentials;

/** Network/signing outside DB locks. The DB enforces the durable one-revision allowance. */
public final class AuthorizedV2Repricing {
    private final AuthorizedV2RevisionStore revisions;private final FuturesTransactionStore tx;
    private final FuturesMarketGate market;private final FuturesChainIdentity chain;private final Credentials signer;
    private final java.util.function.Consumer<String> authority;
    public AuthorizedV2Repricing(AuthorizedV2RevisionStore revisions,FuturesTransactionStore tx,FuturesMarketGate market,FuturesChainIdentity chain,Credentials signer,java.util.function.Consumer<String> authority){this.revisions=revisions;this.tx=tx;this.market=market;this.chain=chain;this.signer=signer;this.authority=authority;}
    public boolean reprice(String id,FuturesReport old,FuturesMarketGate.Mark fixed){
        if(!chain.v2() || !UserIntentCodec.userAction(old.action()) || tx.find(id).isPresent())return false;
        var a=tx.authorization(id);if(a==null||a.intentV2()==null||!a.chain().equals(chain))return false;
        if(tx.executionRevision(id)!=0)return false;
        // Never turn stale/provider-generation changes into a repricing opportunity.
        changedOnly(fixed);
        authority.accept(id);changedOnly(fixed);var mark=market.capture();
        if(!fixed.provider().equalsIgnoreCase(mark.provider())||!Objects.equals(fixed.generation(),mark.generation()))throw new Failure("MARKET_CONNECTION_CHANGED");
        var q=old.action()==0?FuturesMath.quantity(old.collateralAfter(),old.leverage(),mark.price()):old.quantity();
        var next=new FuturesReport(old.operationId(),old.positionId(),old.accountRef(),old.symbolHash(),old.action(),old.expectedVersion(),old.expectedCallEpoch(),old.side(),old.leverage(),q,old.action()==0?mark.price():old.entryPriceE8(),old.collateralBefore(),old.collateralAfter(),mark.price(),BigInteger.valueOf(mark.observedAt().getEpochSecond()),BigInteger.valueOf(mark.observedAt().getEpochSecond()+30),old.policyHash(),old.executor());
        var signature=FuturesReportCodec.sign(next,chain.chainId(),chain.rules(),signer.getEcKeyPair());
        UserIntentV2Codec.verifyReport(next,signature,a);market.validate(mark);
        revisions.replaceUnsigned(id,old,next,signature,mark);return true;
    }
    private void changedOnly(FuturesMarketGate.Mark fixed){try{market.validate(fixed);}catch(Failure changed){if(!changed.code.equals("PRICE_CHANGED"))throw changed;}}
}
