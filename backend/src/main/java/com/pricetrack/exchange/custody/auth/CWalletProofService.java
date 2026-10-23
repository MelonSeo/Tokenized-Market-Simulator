package com.pricetrack.exchange.custody.auth;

import com.pricetrack.exchange.custody.runtime.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** JWT-owned identity proof. Not an order approval, asset controller, sender or private-key store. */
public final class CWalletProofService {
    @FunctionalInterface public interface EoaVerifier {void verify(String owner);}
    public record ChallengeView(String id,String deploymentId,String wallet,String message,Instant expiresAt) {}
    private final CCustodyIdentity identity;private final CWalletLinkStore store;private final EoaVerifier eoa;private final Clock clock;private final SecureRandom random;
    public CWalletProofService(CCustodyIdentity identity,CWalletLinkStore store,EoaVerifier eoa,Clock clock){this(identity,store,eoa,clock,new SecureRandom());}
    CWalletProofService(CCustodyIdentity identity,CWalletLinkStore store,EoaVerifier eoa,Clock clock,SecureRandom random){this.identity=Objects.requireNonNull(identity);this.store=Objects.requireNonNull(store);this.eoa=Objects.requireNonNull(eoa);this.clock=Objects.requireNonNull(clock);this.random=Objects.requireNonNull(random);}
    public ChallengeView challenge(long uid,String wallet,String origin){
        user(uid);origin(origin);wallet=CCustodyIdentity.address(wallet);eoa.verify(wallet);
        String id=UUID.randomUUID().toString();byte[] nonce=new byte[32];random.nextBytes(nonce);String n=HexFormat.of().formatHex(nonce);
        // Fix precision BEFORE the user signs. PostgreSQL timestamp precision must not rewrite a signed message.
        Instant issued=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),expires=issued.plusSeconds(120);String message=message(identity,id,uid,wallet,n,issued,expires);
        var p=store.create(new CWalletLinkStore.Challenge(id,identity.deploymentId(),uid,wallet,origin,message,CWalletLinkStore.digest(message),n,issued,expires,false));
        return new ChallengeView(p.id(),p.deploymentId(),p.wallet(),p.message(),p.expiresAt());
    }
    public CWalletLinkStore.Link link(long uid,String id,String signature,String origin){
        user(uid);origin(origin);var p=store.challenge(uid,id);
        if(p.consumed()||clock.instant().isBefore(p.issuedAt())||!clock.instant().isBefore(p.expiresAt()))throw new CCustodyFailure("C_WALLET_EXPIRED_OR_USED",409);
        eoa.verify(p.wallet());return store.link(uid,id,p.messageHash(),signature,clock.instant());
    }
    public CWalletLinkStore.Link wallet(long uid){user(uid);return store.find(uid).orElseThrow(()->new CCustodyFailure("C_WALLET_NOT_LINKED",404));}
    public static String message(CCustodyIdentity identity,String id,long uid,String owner,String nonce,Instant issued,Instant expires){
        return "TMS C wallet link only. NOT a trade approval or token allowance.\nOrigin: "+identity.origin()+"\nAccount: "+uid+"\nWallet: "+owner+"\nChain ID: "+identity.chainId()+"\nController: "+identity.controller().address()+"\nDeployment: "+identity.deploymentId()+"\nChallenge: "+id+"\nNonce: "+nonce+"\nIssued: "+issued+"\nExpires: "+expires;
    }
    private void origin(String actual){if(!identity.origin().equals(actual))throw new CCustodyFailure("C_WALLET_ORIGIN_CONFLICT",403);}
    private static void user(long uid){if(uid<=0)throw new CCustodyFailure("C_WALLET_USER_REQUIRED",401);}
}
