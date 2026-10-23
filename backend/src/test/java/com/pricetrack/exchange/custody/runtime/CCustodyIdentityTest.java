package com.pricetrack.exchange.custody.runtime;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import static org.junit.jupiter.api.Assertions.*;

public class CCustodyIdentityTest {
    public static String address(int n){return "0x"+String.format("%040x",n);}
    public static String hash(int n){return "0x"+String.format("%064x",n);}
    public static CCustodyIdentity sample(){return sample("http://localhost:5173");}
    public static CCustodyIdentity sample(String origin){return new CCustodyIdentity(BigInteger.valueOf(31337),hash(1),
        new CCustodyIdentity.Contract(address(10),Hash.sha3("0x6001")),new CCustodyIdentity.Contract(address(11),Hash.sha3("0x6002")),
        new CCustodyIdentity.Contract(address(12),Hash.sha3("0x6003")),new CCustodyIdentity.Contract(address(13),Hash.sha3("0x6004")),address(20),address(21),address(22),hash(23),hash(24),origin);}
    @Test void identityContainsCBindingsAndNoKeys(){var i=sample();assertEquals(64,i.deploymentId().length());assertEquals(i.deploymentId(),sample().deploymentId());assertEquals("TokenizedMarketCFuturesUserIntent",i.publicConfig().get("userDomainName"));assertFalse(i.publicConfig().containsKey("rpc"));assertFalse(i.publicConfig().containsKey("privateKey"));}
    @Test void addressOriginAndUintValidationAreExact(){assertThrows(CCustodyFailure.class,()->CCustodyIdentity.address(address(0)));assertThrows(CCustodyFailure.class,()->sample("http://localhost:5173/path"));assertThrows(CCustodyFailure.class,()->sample("http://user@localhost:5173"));assertThrows(CCustodyFailure.class,()->CCustodyIdentity.uint("1e18"));assertThrows(CCustodyFailure.class,()->CCustodyIdentity.uint(BigInteger.ONE.shiftLeft(256).toString()));assertEquals(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE),CCustodyIdentity.uint(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE).toString()));}
    @Test void cAccountRefAndDomainsAreSeparateFromB(){String owner=address(99),symbol=hash(23);String b=Hash.sha3("0x"+Hash.sha3String("TMS_FUTURES_WALLET_ACCOUNT_V1").substring(2)+CReadAbi.addressWord(owner)+symbol.substring(2));assertNotEquals(b,CReadAbi.accountRef(owner,symbol));String raw=CReadAbi.domain(CCustodyIdentity.USER_DOMAIN,BigInteger.valueOf(31337),address(11));assertNotEquals(raw,CReadAbi.domain(CCustodyIdentity.PRICE_DOMAIN,BigInteger.valueOf(31337),address(12)));assertTrue(raw.startsWith("0x0f"));}
}
