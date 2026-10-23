package com.pricetrack.exchange.custody.auth;

import com.pricetrack.exchange.custody.runtime.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import static org.junit.jupiter.api.Assertions.*;

public class CWalletProofServiceTest {
    public static String sign(String message,ECKeyPair key){var s=Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8),key);byte[] out=new byte[65];System.arraycopy(s.getR(),0,out,0,32);System.arraycopy(s.getS(),0,out,32,32);System.arraycopy(s.getV(),0,out,64,1);return "0x"+HexFormat.of().formatHex(out);}
    @Test void correctPersonalSignatureLinksWithoutTradingApproval(){var f=CWalletLinkStoreTest.fixture();var p=f.service().challenge(1,CWalletLinkStoreTest.OWNER,f.identity().origin());assertTrue(p.message().startsWith("TMS C wallet link only. NOT a trade approval or token allowance."));assertTrue(p.message().contains("Controller: "+f.identity().controller().address()));assertTrue(p.message().contains("Deployment: "+f.identity().deploymentId()));assertEquals(CWalletLinkStoreTest.OWNER,f.service().link(1,p.id(),sign(p.message(),CWalletLinkStoreTest.KEY),f.identity().origin()).wallet());}
    @Test void nanoPrecisionClockRoundtripStillAcceptsOriginalUnchangedMessage(){
        var i=CCustodyIdentityTest.sample();var ds=CWalletLinkStoreTest.database();Clock clock=Clock.fixed(Instant.parse("2026-10-23T00:00:00.123456789Z"),ZoneOffset.UTC);var store=new CWalletLinkStore(ds,i,clock);store.installSchema();var service=new CWalletProofService(i,store,owner->{},clock);
        var p=service.challenge(1,CWalletLinkStoreTest.OWNER,i.origin());String original=p.message(),signature=sign(original,CWalletLinkStoreTest.KEY);assertEquals(original,store.challenge(1,p.id()).message());assertEquals(Instant.parse("2026-10-23T00:00:00.123Z"),store.challenge(1,p.id()).issuedAt());assertEquals(store.challenge(1,p.id()).issuedAt().plusSeconds(120),p.expiresAt());
        assertEquals(CWalletLinkStoreTest.OWNER,service.link(1,p.id(),signature,i.origin()).wallet());assertEquals(original,store.challenge(1,p.id()).message());
    }
    @Test void badOriginAndMissingUserDoNotTouchRpc(){var f=CWalletLinkStoreTest.fixture();AtomicInteger calls=new AtomicInteger();var service=new CWalletProofService(f.identity(),f.store(),owner->calls.incrementAndGet(),f.clock());assertEquals(403,assertThrows(CCustodyFailure.class,()->service.challenge(1,CWalletLinkStoreTest.OWNER,"http://localhost:evil")).status());assertEquals(401,assertThrows(CCustodyFailure.class,()->service.challenge(0,CWalletLinkStoreTest.OWNER,f.identity().origin())).status());assertEquals(0,calls.get());}
    @Test void eoaFailureCreatesNoProof(){var f=CWalletLinkStoreTest.fixture();var service=new CWalletProofService(f.identity(),f.store(),owner->{throw new CCustodyFailure("C_EOA_REQUIRED",400);},f.clock());assertThrows(CCustodyFailure.class,()->service.challenge(1,CWalletLinkStoreTest.OWNER,f.identity().origin()));assertEquals(0,CWalletLinkStoreTest.rows(f.ds(),"SELECT count(*) FROM tms_c_identity.wallet_challenges"));}
    @Test void changedMessageOrWrongSignerCannotConsumeChallenge(){var f=CWalletLinkStoreTest.fixture();var p=f.service().challenge(1,CWalletLinkStoreTest.OWNER,f.identity().origin());assertThrows(CCustodyFailure.class,()->f.service().link(1,p.id(),sign(p.message()+"\nchanged",CWalletLinkStoreTest.KEY),f.identity().origin()));assertThrows(CCustodyFailure.class,()->f.service().link(1,p.id(),sign(p.message(),ECKeyPair.create(BigInteger.ONE)),f.identity().origin()));assertFalse(f.store().challenge(1,p.id()).consumed());assertTrue(f.store().find(1).isEmpty());}
    @Test void expiryAndOtherUserChallengeAreBlocked(){var f=CWalletLinkStoreTest.fixture();var p=f.service().challenge(1,CWalletLinkStoreTest.OWNER,f.identity().origin());assertThrows(CCustodyFailure.class,()->f.service().link(2,p.id(),sign(p.message(),CWalletLinkStoreTest.KEY),f.identity().origin()));Clock late=Clock.fixed(p.expiresAt(),ZoneOffset.UTC);var service=new CWalletProofService(f.identity(),f.store(),owner->{},late);assertThrows(CCustodyFailure.class,()->service.link(1,p.id(),sign(p.message(),CWalletLinkStoreTest.KEY),f.identity().origin()));assertFalse(f.store().challenge(1,p.id()).consumed());}
}
