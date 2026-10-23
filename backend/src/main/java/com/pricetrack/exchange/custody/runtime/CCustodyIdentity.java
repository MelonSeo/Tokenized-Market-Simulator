package com.pricetrack.exchange.custody.runtime;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** C-only public deployment binding. Contains no key, balance, price or RPC credentials. */
public record CCustodyIdentity(BigInteger chainId,String genesisHash,Contract token,Contract controller,
        Contract rules,Contract vault,String relayer,String oracle,String pauseAdmin,String symbolHash,
        String policyHash,String origin) {
    public static final BigInteger SUPPLY=BigInteger.TEN.pow(18).multiply(BigInteger.valueOf(20_000_000));
    public static final String USER_DOMAIN="TokenizedMarketCFuturesUserIntent",PRICE_DOMAIN="TokenizedMarketFutures";
    public record Contract(String address,String codeHash) {
        public Contract {address=CCustodyIdentity.address(address);codeHash=CCustodyIdentity.hash(codeHash);}
    }
    public CCustodyIdentity {
        if(chainId==null||chainId.signum()<=0||chainId.bitLength()>256)throw bad("IDENTITY");
        genesisHash=hash(genesisHash);Objects.requireNonNull(token);Objects.requireNonNull(controller);Objects.requireNonNull(rules);Objects.requireNonNull(vault);
        relayer=address(relayer);oracle=address(oracle);pauseAdmin=address(pauseAdmin);symbolHash=hash(symbolHash);policyHash=hash(policyHash);
        if(Set.of(token.address(),controller.address(),rules.address(),vault.address()).size()!=4
                ||Set.of(relayer,oracle,pauseAdmin).size()!=3)throw bad("IDENTITY");
        validOrigin(origin);
    }
    public String deploymentId() {
        String canonical=String.join("|","TMS_C_READ_WALLET_V1",chainId.toString(),genesisHash,
            token.address(),token.codeHash(),controller.address(),controller.codeHash(),rules.address(),rules.codeHash(),
            vault.address(),vault.codeHash(),relayer,oracle,pauseAdmin,symbolHash,policyHash,USER_DOMAIN,"1",PRICE_DOMAIN,"1");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException("C_IDENTITY_HASH");}
    }
    public Map<String,String> publicConfig(){return Map.ofEntries(
        Map.entry("deploymentId",deploymentId()),Map.entry("chainId",chainId.toString()),Map.entry("genesisHash",genesisHash),
        Map.entry("token",token.address()),Map.entry("controller",controller.address()),Map.entry("rules",rules.address()),Map.entry("vault",vault.address()),
        Map.entry("relayer",relayer),Map.entry("oracle",oracle),Map.entry("pauseAdmin",pauseAdmin),Map.entry("symbolHash",symbolHash),Map.entry("policyHash",policyHash),
        Map.entry("userDomainName",USER_DOMAIN),Map.entry("userDomainVersion","1"),Map.entry("priceDomainName",PRICE_DOMAIN),Map.entry("priceDomainVersion","1"),
        Map.entry("custody","C_ERC20_VAULT_CANONICAL_ASSETS"));}
    public static String address(String raw){if(raw==null||!raw.matches("0x[0-9a-fA-F]{40}")||raw.equalsIgnoreCase("0x"+"0".repeat(40)))throw bad("ADDRESS");return raw.toLowerCase(Locale.ROOT);}
    public static String hash(String raw){if(raw==null||!raw.matches("0x[0-9a-fA-F]{64}"))throw bad("HASH");return raw.toLowerCase(Locale.ROOT);}
    public static BigInteger uint(String raw){if(raw==null||!raw.matches("0|[1-9][0-9]{0,77}"))throw bad("UINT");BigInteger n=new BigInteger(raw);if(n.bitLength()>256)throw bad("UINT");return n;}
    public static void validOrigin(String origin){try{URI u=URI.create(origin);if(origin.length()>256||!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!u.getRawPath().isEmpty()||!u.toASCIIString().equals(origin))throw bad("ORIGIN");}catch(RuntimeException invalid){throw bad("ORIGIN");}}
    static CCustodyFailure bad(String suffix){return new CCustodyFailure("C_INVALID_"+suffix,400);}
}
