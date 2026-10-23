package com.pricetrack.exchange.custody.proof;

import java.math.BigInteger;
import java.util.*;
import org.web3j.crypto.Hash;

/** Authentic signed-envelope bytes only; not a Receipt, canonical inclusion or EVM execution proof. */
public final class CSignedTransactionVerifier {
    public static final int MAX_RAW_BYTES=64*1024, MAX_CALLDATA_BYTES=16*1024;
    public static final int MAX_ACCESS_ENTRIES=64, MAX_STORAGE_KEYS=256, MAX_RLP_NODES=2048, MAX_RLP_DEPTH=4;
    private static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE,TWO=BigInteger.TWO;
    private static final BigInteger UINT_MAX=ONE.shiftLeft(256).subtract(ONE);
    private static final HexFormat HEX=HexFormat.of();
    public enum Type { LEGACY_EIP155, EIP1559 }
    public enum FailureKind { UNSUPPORTED_INPUT, MALFORMED_INPUT, RESOURCE_LIMIT, IDENTITY_CONFLICT, SIGNATURE_INVALID }
    public static final class Failure extends IllegalArgumentException {
        private final FailureKind kind;
        public Failure(FailureKind kind,String code) {super("C_TX_"+code);this.kind=kind;}
        public FailureKind kind() {return kind;}
    }
    public record AccessEntry(String address,List<String> storageKeys) {
        public AccessEntry {storageKeys=List.copyOf(storageKeys);}
    }
    public record Proof(Type type,String transactionHash,String signingHash,BigInteger chainId,String sender,
            String to,BigInteger nonce,BigInteger gasLimit,BigInteger value,BigInteger gasPrice,
            BigInteger maxPriorityFeePerGas,BigInteger maxFeePerGas,String calldata,List<AccessEntry> accessList) {
        public Proof {accessList=List.copyOf(accessList);}
        public boolean contractCreation() {return to.equals("0x");}
        public String scope() {return "SIGNED_ENVELOPE_ONLY_NOT_RECEIPT_CANONICAL_EXECUTION_OR_AUTHORIZATION_PROOF";}
        @Override public String toString() {return "CTransactionProof[type="+type+", hash="+transactionHash+", chainId="+chainId
            +", sender="+sender+", to="+to+", calldataBytes="+(calldata.length()-2)/2+"]";}
    }
    private final CEip712Verifier recovery=new CEip712Verifier();
    public Proof verify(String rawHex,String expectedHash,BigInteger expectedChainId) {
        if(expectedChainId==null||expectedChainId.signum()<=0||expectedChainId.compareTo(UINT_MAX)>0)throw bad("EXPECTED_CHAIN");
        String expected=hash(expectedHash);
        if(rawHex==null||rawHex.length()>2+2*MAX_RAW_BYTES)throw limit("RAW_SIZE");
        if((rawHex.length()&1)!=0||!rawHex.matches("0x[0-9a-fA-F]*"))throw bad("RAW_HEX");
        byte[] raw=HEX.parseHex(rawHex.substring(2));if(raw.length==0)throw bad("RAW_EMPTY");
        boolean typed=(raw[0]&255)<128;
        if(typed&&raw[0]!=2)throw new Failure(FailureKind.UNSUPPORTED_INPUT,"UNSUPPORTED_TYPE");
        byte[] body=typed?Arrays.copyOfRange(raw,1,raw.length):raw;
        Parser parser=new Parser(body);Node node=parser.read(0);if(parser.at!=body.length)throw bad("RLP_TRAILING");
        if(!(node instanceof Sequence root))throw bad("RLP_ROOT");
        if(!Arrays.equals(body,encode(root)))throw bad("RLP_NONCANONICAL");
        List<Node> f=root.children();if(f.size()!=(typed?12:9))throw bad("FIELD_COUNT");
        String actual="0x"+HEX.formatHex(Hash.sha3(raw));
        if(!actual.equals(expected))throw new Failure(FailureKind.IDENTITY_CONFLICT,"HASH_MISMATCH");
        return typed?type2(f,actual,expectedChainId):legacy(f,actual,expectedChainId);
    }
    private Proof legacy(List<Node> f,String hash,BigInteger expectedChain) {
        BigInteger nonce=uint(f.get(0)),gasPrice=uint(f.get(1)),gas=uint(f.get(2)),value=uint(f.get(4));
        String to=target(f.get(3)),data=data(f.get(5));
        BigInteger v=unsigned(f.get(6),33);
        if(v.compareTo(BigInteger.valueOf(35))<0)throw new Failure(FailureKind.UNSUPPORTED_INPUT,"UNPROTECTED_LEGACY");
        BigInteger adjusted=v.subtract(BigInteger.valueOf(35)),chain=adjusted.shiftRight(1);
        int parity=adjusted.testBit(0)?1:0;
        if(chain.signum()<=0||chain.compareTo(UINT_MAX)>0)throw bad("CHAIN_RANGE");
        if(!v.equals(chain.multiply(TWO).add(BigInteger.valueOf(35+parity))))throw bad("LEGACY_V");
        chain(chain,expectedChain);
        List<Node> unsigned=new ArrayList<>(f.subList(0,6));unsigned.add(number(chain));unsigned.add(number(ZERO));unsigned.add(number(ZERO));
        String signing="0x"+HEX.formatHex(Hash.sha3(encode(new Sequence(unsigned))));
        String sender=recover(signing,uint(f.get(7)),uint(f.get(8)),parity);
        return new Proof(Type.LEGACY_EIP155,hash,signing,chain,sender,to,nonce,gas,value,gasPrice,ZERO,ZERO,data,List.of());
    }
    private Proof type2(List<Node> f,String hash,BigInteger expectedChain) {
        BigInteger chain=uint(f.get(0));if(chain.signum()==0)throw bad("CHAIN_RANGE");chain(chain,expectedChain);
        BigInteger nonce=uint(f.get(1)),priority=uint(f.get(2)),maxFee=uint(f.get(3)),gas=uint(f.get(4)),value=uint(f.get(6));
        String to=target(f.get(5)),data=data(f.get(7));List<AccessEntry> access=access(f.get(8));
        BigInteger parity=uint(f.get(9));if(parity.compareTo(ONE)>0)throw bad("Y_PARITY");
        byte[] unsigned=encode(new Sequence(f.subList(0,9)));
        String signing="0x"+HEX.formatHex(Hash.sha3(join(new byte[]{2},unsigned)));
        String sender=recover(signing,uint(f.get(10)),uint(f.get(11)),parity.intValueExact());
        return new Proof(Type.EIP1559,hash,signing,chain,sender,to,nonce,gas,value,ZERO,priority,maxFee,data,access);
    }
    private String recover(String signing,BigInteger r,BigInteger s,int parity) {
        String signature="0x"+HEX.formatHex(join(word(r),word(s),new byte[]{(byte)(27+parity)}));
        try {return recovery.recover(signing,signature);}
        catch(IllegalArgumentException invalid) {throw new Failure(FailureKind.SIGNATURE_INVALID,"SIGNATURE");}
    }
    private void chain(BigInteger actual,BigInteger expected) {if(!actual.equals(expected))throw new Failure(FailureKind.IDENTITY_CONFLICT,"CHAIN_MISMATCH");}
    private List<AccessEntry> access(Node node) {
        if(!(node instanceof Sequence list))throw bad("ACCESS_LIST");
        if(list.children().size()>MAX_ACCESS_ENTRIES)throw limit("ACCESS_ENTRIES");
        List<AccessEntry> result=new ArrayList<>();int keys=0;
        for(Node item:list.children()) {
            if(!(item instanceof Sequence row)||row.children().size()!=2)throw bad("ACCESS_ENTRY");
            byte[] address=bytes(row.children().get(0));if(address.length!=20)throw bad("ACCESS_ADDRESS");
            if(!(row.children().get(1) instanceof Sequence storage))throw bad("ACCESS_KEYS");
            keys+=storage.children().size();if(keys>MAX_STORAGE_KEYS)throw limit("STORAGE_KEYS");
            List<String> values=new ArrayList<>();for(Node key:storage.children()) {byte[] b=bytes(key);if(b.length!=32)throw bad("STORAGE_KEY");values.add("0x"+HEX.formatHex(b));}
            // EVM syntax allows duplicates; preserve exact order, multiplicity and signing hash.
            result.add(new AccessEntry("0x"+HEX.formatHex(address),values));
        }
        return List.copyOf(result);
    }
    private static String target(Node n) {byte[] b=bytes(n);if(b.length!=0&&b.length!=20)throw bad("TO_LENGTH");return "0x"+HEX.formatHex(b);}
    private static String data(Node n) {byte[] b=bytes(n);if(b.length>MAX_CALLDATA_BYTES)throw limit("CALLDATA");return "0x"+HEX.formatHex(b);}
    private static BigInteger uint(Node n) {return unsigned(n,32);}
    private static BigInteger unsigned(Node n,int maxBytes) {
        byte[] b=bytes(n);if(b.length>maxBytes)throw bad("UINT_RANGE");
        if(b.length>0&&b[0]==0)throw bad("INTEGER_LEADING_ZERO");return b.length==0?ZERO:new BigInteger(1,b);
    }
    private static String hash(String value) {if(value==null||!value.matches("0x[0-9a-fA-F]{64}"))throw bad("EXPECTED_HASH");return value.toLowerCase(Locale.ROOT);}
    private static byte[] word(BigInteger n) {byte[] raw=n.toByteArray(),fixed=new byte[32];int len=Math.min(32,raw.length);System.arraycopy(raw,raw.length-len,fixed,32-len,len);return fixed;}
    private sealed interface Node permits Value,Sequence {}
    private record Value(byte[] data) implements Node {}
    private record Sequence(List<Node> children) implements Node {private Sequence {children=List.copyOf(children);}}
    private static byte[] bytes(Node node) {if(!(node instanceof Value value))throw bad("EXPECTED_BYTES");return value.data();}
    private static Node number(BigInteger n) {if(n.signum()==0)return new Value(new byte[0]);byte[] b=n.toByteArray();if(b[0]==0)b=Arrays.copyOfRange(b,1,b.length);return new Value(b);}
    private static byte[] encode(Node node) {
        if(node instanceof Value v) {
            byte[] b=v.data();if(b.length==1&&(b[0]&255)<128)return b;
            return prefix(b,128);
        }
        Sequence list=(Sequence)node;List<byte[]> encoded=new ArrayList<>();for(Node n:list.children())encoded.add(encode(n));
        return prefix(join(encoded.toArray(byte[][]::new)),192);
    }
    private static byte[] prefix(byte[] payload,int offset) {
        if(payload.length<56)return join(new byte[]{(byte)(offset+payload.length)},payload);
        byte[] length=bytes(number(BigInteger.valueOf(payload.length)));
        return join(new byte[]{(byte)(offset+55+length.length)},length,payload);
    }
    private static byte[] join(byte[]... arrays) {int size=0;for(byte[] b:arrays)size=Math.addExact(size,b.length);byte[] out=new byte[size];int at=0;for(byte[] b:arrays){System.arraycopy(b,0,out,at,b.length);at+=b.length;}return out;}
    private static final class Parser {
        final byte[] data;int at,nodes;
        Parser(byte[] data) {this.data=data;}
        Node read(int depth) {
            if(depth>MAX_RLP_DEPTH)throw limit("RLP_DEPTH");if(++nodes>MAX_RLP_NODES)throw limit("RLP_NODES");
            if(at>=data.length)throw bad("RLP_TRUNCATED");int first=data[at++]&255;
            if(first<=127)return new Value(new byte[]{(byte)first});
            boolean list=first>=192;int length;
            if(first<=183)length=first-128;
            else if(first<=191)length=longLength(first-183);
            else if(first<=247)length=first-192;
            else length=longLength(first-247);
            if(length>data.length-at)throw bad("RLP_TRUNCATED");int end=at+length;
            if(!list) {
                byte[] value=Arrays.copyOfRange(data,at,end);at=end;
                if(value.length==1&&(value[0]&255)<128)throw bad("RLP_SINGLE_BYTE");return new Value(value);
            }
            List<Node> children=new ArrayList<>();while(at<end)children.add(read(depth+1));
            if(at!=end)throw bad("RLP_LIST_BOUNDARY");return new Sequence(children);
        }
        int longLength(int count) {
            if(count<1||count>8||count>data.length-at)throw bad("RLP_LENGTH");
            if(data[at]==0)throw bad("RLP_LENGTH_ZERO");BigInteger n=new BigInteger(1,Arrays.copyOfRange(data,at,at+count));at+=count;
            if(n.compareTo(BigInteger.valueOf(56))<0)throw bad("RLP_LONG_FORM");
            if(n.compareTo(BigInteger.valueOf(MAX_RAW_BYTES))>0)throw limit("RLP_LENGTH");return n.intValueExact();
        }
    }
    private static Failure bad(String code) {return new Failure(FailureKind.MALFORMED_INPUT,code);}
    private static Failure limit(String code) {return new Failure(FailureKind.RESOURCE_LIMIT,code);}
}
