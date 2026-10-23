package com.pricetrack.exchange.futures.chain;

import java.math.BigInteger;
import java.util.Locale;

/** Public deployment identity; private keys are deliberately not part of this value. */
public record FuturesChainIdentity(BigInteger chainId, String engine, String executor, String priceSigner, String rules,String authorizationVersion) {
    public FuturesChainIdentity(BigInteger id,String engine,String executor,String signer) { this(id,engine,executor,signer,null); }
    public FuturesChainIdentity(BigInteger id,String engine,String executor,String signer,String rules){this(id,engine,executor,signer,rules,null);}
    public FuturesChainIdentity {
        if (chainId==null || chainId.signum()<=0 || chainId.bitLength()>63) throw new IllegalArgumentException("INVALID_CHAIN_ID");
        engine=normalize(engine); executor=normalize(executor); priceSigner=normalize(priceSigner);
        if(executor.equals(priceSigner)) throw new IllegalArgumentException("FUTURES_KEYS_MUST_DIFFER");
        if(rules!=null) { rules=normalize(rules);if(rules.equals(engine)||rules.equals(executor)||engine.equals(executor))throw new IllegalArgumentException("AUTHORIZED_DEPLOYMENT_CONFLICT"); }
        if(authorizationVersion==null)authorizationVersion=rules==null?"LEGACY":"1";
        if(rules==null?!authorizationVersion.equals("LEGACY"):!java.util.Set.of("1","2").contains(authorizationVersion))throw new IllegalArgumentException("INVALID_AUTHORIZATION_VERSION");
    }
    public boolean authorized() { return rules!=null; }
    public boolean v2(){return "2".equals(authorizationVersion);}
    public String priceContract() { return authorized()?rules:engine; }
    public String reportExecutor() { return authorized()?engine:executor; }
    private static String normalize(String s) {
        if(s==null || !s.matches("0x[0-9a-fA-F]{40}") || s.equals("0x"+"0".repeat(40))) throw new IllegalArgumentException("INVALID_CHAIN_ADDRESS");
        return s.toLowerCase(Locale.ROOT);
    }
}
