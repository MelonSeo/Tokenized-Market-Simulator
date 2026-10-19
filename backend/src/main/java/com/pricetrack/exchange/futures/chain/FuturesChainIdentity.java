package com.pricetrack.exchange.futures.chain;

import java.math.BigInteger;
import java.util.Locale;

/** Public deployment identity; private keys are deliberately not part of this value. */
public record FuturesChainIdentity(BigInteger chainId, String engine, String executor, String priceSigner) {
    public FuturesChainIdentity {
        if (chainId==null || chainId.signum()<=0 || chainId.bitLength()>63) throw new IllegalArgumentException("INVALID_CHAIN_ID");
        engine=normalize(engine); executor=normalize(executor); priceSigner=normalize(priceSigner);
        if(executor.equals(priceSigner)) throw new IllegalArgumentException("FUTURES_KEYS_MUST_DIFFER");
    }
    private static String normalize(String s) {
        if(s==null || !s.matches("0x[0-9a-fA-F]{40}") || s.equals("0x"+"0".repeat(40))) throw new IllegalArgumentException("INVALID_CHAIN_ADDRESS");
        return s.toLowerCase(Locale.ROOT);
    }
}
