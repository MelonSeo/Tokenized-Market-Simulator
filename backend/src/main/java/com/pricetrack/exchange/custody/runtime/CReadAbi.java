package com.pricetrack.exchange.custody.runtime;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.web3j.crypto.Hash;

/** Bounded exact view ABI. No transaction builder or state-changing selectors. */
final class CReadAbi {
    static final BigInteger MAX=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    static final String ZERO="0x"+"0".repeat(64);
    static String selector(String signature){return Hash.sha3String(signature).substring(0,10);}
    static String word(BigInteger n){if(n==null||n.signum()<0||n.compareTo(MAX)>0)throw CCustodyIdentity.bad("UINT");return String.format("%064x",n);}
    static String addressWord(String address){return "0".repeat(24)+CCustodyIdentity.address(address).substring(2);}
    static String data(String raw,int words){if(raw==null||raw.length()!=2+words*64||!raw.matches("0x[0-9a-fA-F]*"))throw fail("ABI");return raw.substring(2).toLowerCase(java.util.Locale.ROOT);}
    static BigInteger number(String raw,int words,int index){String d=data(raw,words);return new BigInteger(d.substring(index*64,(index+1)*64),16);}
    static String hashAt(String raw,int words,int index){String d=data(raw,words);return "0x"+d.substring(index*64,(index+1)*64);}
    static String addressAt(String raw,int words,int index){String d=data(raw,words).substring(index*64,(index+1)*64);if(!d.startsWith("0".repeat(24))||d.equals("0".repeat(64)))throw fail("ABI_ADDRESS");return "0x"+d.substring(24);}
    static boolean boolAt(String raw,int words,int index){BigInteger n=number(raw,words,index);if(!n.equals(BigInteger.ZERO)&&!n.equals(BigInteger.ONE))throw fail("ABI_BOOL");return n.equals(BigInteger.ONE);}
    static String accountRef(String owner,String symbol){String encoded=Hash.sha3String("TMS_C_FUTURES_WALLET_ACCOUNT_V1").substring(2)+addressWord(owner)+CCustodyIdentity.hash(symbol).substring(2);return Hash.sha3("0x"+encoded);}
    static String stringTail(String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);return word(BigInteger.valueOf(bytes.length))+HexFormat.of().formatHex(bytes)+"0".repeat((32-bytes.length%32)%32*2);}
    static String domain(String name,BigInteger chain,String target){String n=stringTail(name),v=stringTail("1");int start=7*32;return "0x"+"0f"+"0".repeat(62)+word(BigInteger.valueOf(start))+word(BigInteger.valueOf(start+n.length()/2))+word(chain)+addressWord(target)+"0".repeat(64)+word(BigInteger.valueOf(start+n.length()/2+v.length()/2))+n+v+word(BigInteger.ZERO);}
    static CCustodyFailure fail(String suffix){return new CCustodyFailure("C_READ_"+suffix,503);}
}
