package com.pricetrack.exchange.custody.store;

import static com.pricetrack.exchange.custody.model.CCustodyModels.*;
import com.pricetrack.exchange.custody.model.CCustodyModels;
import com.pricetrack.exchange.custody.projection.CCustodyReducer;
import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Versioned whitelist binary format. Not Java serialization, authentication or canonical RPC evidence.
 * Integers are exactly 32 bytes (uint256 unsigned or int256 two's complement); no alternate encodings.
 * Tags and enum ordering are format v1: changes require a new version, never silent reinterpretation. */
public final class CCustodyStateCodec {
    public static final int VERSION=1, MAX_PAYLOAD=4*1024*1024, MAX_STRING=1024, MAX_ENTRIES=4096;
    private static final int MAGIC=0x544d5343;
    private static final BigInteger INT_MIN=BigInteger.ONE.shiftLeft(255).negate();
    private static final BigInteger INT_MAX=BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE);
    private final CCustodyReducer reducer;
    public CCustodyStateCodec(CCustodyReducer reducer) { this.reducer=Objects.requireNonNull(reducer); }

    public byte[] encodeDeployment(Deployment d) { return encode(1,o->deployment(o,d)); }
    public Deployment decodeDeployment(byte[] bytes) { return decode(bytes,1,this::deployment,this::encodeDeployment); }
    public byte[] encodeBundle(Bundle b) { return encode(2,o->bundle(o,b)); }
    public Bundle decodeBundle(byte[] bytes) { return decode(bytes,2,this::bundle,this::encodeBundle); }
    public byte[] encodeState(State s) { reducer.validateState(s); return encode(3,o->state(o,s)); }
    public State decodeState(byte[] bytes) {
        State state=decode(bytes,3,this::state,this::encodeState); reducer.validateState(state); return state;
    }
    public static String sha256(byte[] bytes) {
        Objects.requireNonNull(bytes);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface private interface Reader<T> { T read(DataInputStream in) throws IOException; }
    private byte[] encode(int kind,Writer body) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            OutputStream bounded=new OutputStream() {
                private int count;
                @Override public void write(int value) { if(++count>MAX_PAYLOAD) throw bad("PAYLOAD_LIMIT"); bytes.write(value); }
                @Override public void write(byte[] value,int offset,int length) {
                    if(length<0||count>MAX_PAYLOAD-length) throw bad("PAYLOAD_LIMIT"); count+=length; bytes.write(value,offset,length);
                }
            };
            DataOutputStream out=new DataOutputStream(bounded);
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeByte(kind); body.write(out); out.flush();
            return bytes.toByteArray();
        } catch(IOException impossible) { throw bad("WRITE"); }
    }
    private <T>T decode(byte[] bytes,int kind,Reader<T> body,java.util.function.Function<T,byte[]> encoder) {
        if(bytes==null||bytes.length>MAX_PAYLOAD) throw bad("PAYLOAD_LIMIT");
        try {
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION||in.readUnsignedByte()!=kind) throw bad("HEADER");
            T value=body.read(in);
            if(in.available()!=0) throw bad("TRAILING");
            if(!Arrays.equals(bytes,encoder.apply(value))) throw bad("NONCANONICAL");
            return value;
        } catch(IOException malformed) { throw bad("FORMAT"); }
    }
    private static IllegalArgumentException bad(String code) { return new IllegalArgumentException("C_CODEC_"+code); }
    private void text(DataOutputStream out,String text) throws IOException {
        if(text==null) throw bad("STRING");
        if(text.length()>MAX_STRING) throw bad("STRING_LIMIT");
        try {
            ByteBuffer buffer=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if(buffer.remaining()>MAX_STRING) throw bad("STRING_LIMIT");
            byte[] value=new byte[buffer.remaining()]; buffer.get(value); out.writeInt(value.length); out.write(value);
        } catch(CharacterCodingException invalid) { throw bad("UTF8"); }
    }
    private String text(DataInputStream in) throws IOException {
        int length=in.readInt(); if(length<0||length>MAX_STRING||length>in.available()) throw bad("STRING_LIMIT");
        byte[] value=new byte[length]; in.readFully(value);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString(); }
        catch(CharacterCodingException invalid) { throw bad("UTF8"); }
    }
    private void addr(DataOutputStream out,String value) throws IOException {
        if(!CCustodyModels.address(value).equals(value)) throw bad("ADDRESS"); text(out,value);
    }
    private String addr(DataInputStream in) throws IOException {
        String value=text(in); if(!CCustodyModels.address(value).equals(value)) throw bad("ADDRESS"); return value;
    }
    private void hash(DataOutputStream out,String value) throws IOException {
        if(!CCustodyModels.hash(value).equals(value)) throw bad("HASH"); text(out,value);
    }
    private String hash(DataInputStream in) throws IOException {
        String value=text(in); if(!CCustodyModels.hash(value).equals(value)) throw bad("HASH"); return value;
    }
    private void uint(DataOutputStream out,BigInteger value) throws IOException {
        if(value==null||value.signum()<0||value.compareTo(UINT_MAX)>0) throw bad("UINT256"); integer(out,value,false);
    }
    private BigInteger uint(DataInputStream in) throws IOException { byte[] bytes=new byte[32]; in.readFully(bytes); return new BigInteger(1,bytes); }
    private void signed(DataOutputStream out,BigInteger value) throws IOException {
        if(value==null||value.compareTo(INT_MIN)<0||value.compareTo(INT_MAX)>0) throw bad("INT256"); integer(out,value,true);
    }
    private BigInteger signed(DataInputStream in) throws IOException { byte[] bytes=new byte[32]; in.readFully(bytes); return new BigInteger(bytes); }
    private void integer(DataOutputStream out,BigInteger value,boolean signed) throws IOException {
        byte[] raw=value.toByteArray(),fixed=new byte[32];
        if(signed&&value.signum()<0) Arrays.fill(fixed,(byte)0xff);
        int copy=Math.min(raw.length,32); System.arraycopy(raw,raw.length-copy,fixed,32-copy,copy); out.write(fixed);
    }
    private void bool(DataOutputStream out,boolean value) throws IOException { out.writeByte(value?1:0); }
    private boolean bool(DataInputStream in) throws IOException { int b=in.readUnsignedByte(); if(b>1) throw bad("BOOLEAN"); return b==1; }
    private void count(DataOutputStream out,int count) throws IOException { if(count<0||count>MAX_ENTRIES) throw bad("COUNT"); out.writeInt(count); }
    private int count(DataInputStream in) throws IOException { int count=in.readInt(); if(count<0||count>MAX_ENTRIES) throw bad("COUNT"); return count; }
    private void tag(DataOutputStream out,Enum<?> value) throws IOException { if(value==null) throw bad("ENUM"); out.writeByte(value.ordinal()+1); }
    private <T>T tag(DataInputStream in,T[] values) throws IOException { int n=in.readUnsignedByte()-1; if(n<0||n>=values.length) throw bad("ENUM"); return values[n]; }
    private String action(DataInputStream in) throws IOException { String a=text(in); validAction(a); return a; }
    private void action(DataOutputStream out,String a) throws IOException { validAction(a); text(out,a); }
    private void validAction(String a) {
        if(!Set.of("OPEN","TOP_UP","CLOSE","START_CALL","CLEAR_CALL","LIQUIDATE").contains(a)) throw bad("ACTION");
    }
    private void deployment(DataOutputStream o,Deployment d) throws IOException {
        text(o,d.id());uint(o,d.chainId());hash(o,d.genesisHash());addr(o,d.token());addr(o,d.vault());addr(o,d.controller());
        addr(o,d.rules());addr(o,d.relayer());addr(o,d.pauseAdmin());addr(o,d.oracleSigner());hash(o,d.policyHash());
    }
    private Deployment deployment(DataInputStream i) throws IOException {
        return new Deployment(text(i),uint(i),hash(i),addr(i),addr(i),addr(i),addr(i),addr(i),addr(i),addr(i),hash(i));
    }
    private void block(DataOutputStream o,Block b) throws IOException { if(b.number()<0) throw bad("BLOCK");o.writeLong(b.number());hash(o,b.hash());hash(o,b.parentHash()); }
    private Block block(DataInputStream i) throws IOException { return new Block(i.readLong(),hash(i),hash(i)); }
    private void bundle(DataOutputStream o,Bundle b) throws IOException {
        deployment(o,b.deployment());block(o,b.block());hash(o,b.transactionHash());o.writeInt(b.transactionIndex());
        addr(o,b.from());addr(o,b.to());tag(o,b.outcome());call(o,b.call());count(o,b.logs().size());
        for(Log log:b.logs()) { o.writeInt(log.index());addr(o,log.emitter());event(o,log.event()); }
    }
    private Bundle bundle(DataInputStream i) throws IOException {
        Deployment d=deployment(i);Block b=block(i);String tx=hash(i);int index=i.readInt();String from=addr(i),to=addr(i);
        Outcome outcome=tag(i,Outcome.values());Call call=call(i);int count=count(i);List<Log> logs=new ArrayList<>();
        for(int n=0;n<count;n++) logs.add(new Log(i.readInt(),addr(i),event(i)));
        return new Bundle(d,b,tx,index,from,to,outcome,call,logs);
    }
    private void call(DataOutputStream o,Call c) throws IOException {
        if(c instanceof VaultCall v) {o.writeByte(1);tag(o,v.action());addr(o,v.owner());uint(o,v.amount());}
        else if(c instanceof UserCall u) {o.writeByte(2);tag(o,u.action());addr(o,u.owner());hash(o,u.operationId());hash(o,u.positionId());
            uint(o,u.nonce());uint(o,u.expectedVersion());uint(o,u.collateralBefore());uint(o,u.collateralAfter());hash(o,u.intentDigest());hash(o,u.reportDigest());bool(o,u.direct());}
        else if(c instanceof RiskCall r) {o.writeByte(3);tag(o,r.action());addr(o,r.owner());hash(o,r.operationId());hash(o,r.positionId());
            uint(o,r.expectedVersion());uint(o,r.collateral());hash(o,r.reportDigest());}
        else if(c instanceof CancelCall c0) {o.writeByte(4);addr(o,c0.owner());uint(o,c0.expectedNonce());}
        else if(c instanceof PauseCall p) {o.writeByte(5);bool(o,p.paused());}
        else if(c instanceof ProcessClaimCall) o.writeByte(6);
        else if(c instanceof DonationCall d) {o.writeByte(7);uint(o,d.amount());}
        else throw bad("CALL_TAG");
    }
    private Call call(DataInputStream i) throws IOException {
        return switch(i.readUnsignedByte()) {
            case 1 -> new VaultCall(tag(i,VaultAction.values()),addr(i),uint(i));
            case 2 -> new UserCall(tag(i,UserAction.values()),addr(i),hash(i),hash(i),uint(i),uint(i),uint(i),uint(i),hash(i),hash(i),bool(i));
            case 3 -> new RiskCall(tag(i,RiskAction.values()),addr(i),hash(i),hash(i),uint(i),uint(i),hash(i));
            case 4 -> new CancelCall(addr(i),uint(i)); case 5 -> new PauseCall(bool(i));
            case 6 -> new ProcessClaimCall(); case 7 -> new DonationCall(uint(i)); default -> throw bad("CALL_TAG");
        };
    }
    private void event(DataOutputStream o,Event e) throws IOException {
        if(e instanceof Transfer x) {o.writeByte(1);addr(o,x.from());addr(o,x.to());uint(o,x.amount());}
        else if(e instanceof Deposited x) {o.writeByte(2);addr(o,x.owner());uint(o,x.amount());}
        else if(e instanceof Withdrawn x) {o.writeByte(3);addr(o,x.owner());uint(o,x.amount());}
        else if(e instanceof HouseFunded x) {o.writeByte(4);addr(o,x.contributor());uint(o,x.amount());}
        else if(e instanceof Locked x) {o.writeByte(5);hash(o,x.operationId());hash(o,x.positionId());addr(o,x.owner());uint(o,x.delta());uint(o,x.lockedAfter());}
        else if(e instanceof RuleApplied x) {o.writeByte(6);hash(o,x.operationId());hash(o,x.positionId());action(o,x.action());uint(o,x.version());uint(o,x.collateral());signed(o,x.realized());uint(o,x.badDebt());uint(o,x.callEpoch());uint(o,x.deadline());}
        else if(e instanceof ReportConsumed x) {o.writeByte(7);hash(o,x.operationId());uint(o,x.observedAt());uint(o,x.validUntil());}
        else if(e instanceof UserExecuted x) {o.writeByte(8);hash(o,x.operationId());hash(o,x.positionId());addr(o,x.owner());hash(o,x.intentDigest());hash(o,x.reportDigest());action(o,x.action());uint(o,x.nonce());bool(o,x.direct());}
        else if(e instanceof RiskExecuted x) {o.writeByte(9);hash(o,x.operationId());hash(o,x.positionId());addr(o,x.owner());hash(o,x.reportDigest());action(o,x.action());}
        else if(e instanceof PositionSettled x) {o.writeByte(10);hash(o,x.operationId());hash(o,x.positionId());addr(o,x.owner());uint(o,x.collateral());signed(o,x.realized());uint(o,x.badDebt());uint(o,x.paidProfit());uint(o,x.claimId());}
        else if(e instanceof ClaimCreated x) {o.writeByte(11);uint(o,x.claimId());addr(o,x.owner());hash(o,x.positionId());hash(o,x.operationId());uint(o,x.amount());}
        else if(e instanceof ClaimPaid x) {o.writeByte(12);uint(o,x.claimId());addr(o,x.owner());uint(o,x.amount());uint(o,x.remaining());}
        else if(e instanceof NonceCancelled x) {o.writeByte(13);addr(o,x.owner());uint(o,x.nonce());}
        else if(e instanceof PauseChanged x) {o.writeByte(14);bool(o,x.paused());}
        else throw bad("EVENT_TAG");
    }
    private Event event(DataInputStream i) throws IOException {
        return switch(i.readUnsignedByte()) {
            case 1 -> new Transfer(addr(i),addr(i),uint(i)); case 2 -> new Deposited(addr(i),uint(i));
            case 3 -> new Withdrawn(addr(i),uint(i)); case 4 -> new HouseFunded(addr(i),uint(i));
            case 5 -> new Locked(hash(i),hash(i),addr(i),uint(i),uint(i));
            case 6 -> new RuleApplied(hash(i),hash(i),action(i),uint(i),uint(i),signed(i),uint(i),uint(i),uint(i));
            case 7 -> new ReportConsumed(hash(i),uint(i),uint(i));
            case 8 -> new UserExecuted(hash(i),hash(i),addr(i),hash(i),hash(i),action(i),uint(i),bool(i));
            case 9 -> new RiskExecuted(hash(i),hash(i),addr(i),hash(i),action(i));
            case 10 -> new PositionSettled(hash(i),hash(i),addr(i),uint(i),signed(i),uint(i),uint(i),uint(i));
            case 11 -> new ClaimCreated(uint(i),addr(i),hash(i),hash(i),uint(i));
            case 12 -> new ClaimPaid(uint(i),addr(i),uint(i),uint(i));
            case 13 -> new NonceCancelled(addr(i),uint(i)); case 14 -> new PauseChanged(bool(i)); default -> throw bad("EVENT_TAG");
        };
    }
    private void state(DataOutputStream o,State s) throws IOException {
        deployment(o,s.deployment());block(o,s.cursor());o.writeInt(s.lastTransactionIndex());
        count(o,s.balances().size());for(var e:new TreeMap<>(s.balances()).entrySet()) {addr(o,e.getKey());uint(o,e.getValue().free());uint(o,e.getValue().locked());}
        count(o,s.positions().size());for(var e:new TreeMap<>(s.positions()).entrySet()) {
            hash(o,e.getKey());Position p=e.getValue();addr(o,p.owner());uint(o,p.collateral());uint(o,p.version());uint(o,p.callEpoch());uint(o,p.deadline());tag(o,p.status());
        }
        count(o,s.claims().size());for(var e:new TreeMap<>(s.claims()).entrySet()) {
            uint(o,e.getKey());Claim c=e.getValue();addr(o,c.owner());hash(o,c.operationId());hash(o,c.positionId());uint(o,c.original());uint(o,c.paid());uint(o,c.remaining());
        }
        count(o,s.nonces().size());for(var e:new TreeMap<>(s.nonces()).entrySet()) {addr(o,e.getKey());uint(o,e.getValue());}
        count(o,s.usedOperations().size());for(var e:new TreeMap<>(s.usedOperations()).entrySet()) {hash(o,e.getKey());hash(o,e.getValue());}
        uint(o,s.totalFree());uint(o,s.totalLocked());uint(o,s.house());uint(o,s.actualAssets());uint(o,s.totalUnpaid());uint(o,s.head());uint(o,s.next());bool(o,s.paused());
        var sorted=new TreeMap<TxKey,Bundle>(Comparator.comparing(TxKey::blockHash).thenComparing(TxKey::transactionHash));sorted.putAll(s.observations());
        count(o,sorted.size());for(var e:sorted.entrySet()) {hash(o,e.getKey().blockHash());hash(o,e.getKey().transactionHash());bundle(o,e.getValue());}
    }
    private <K,V>void put(Map<K,V> map,K key,V value) { if(map.putIfAbsent(key,value)!=null) throw bad("DUPLICATE_KEY"); }
    private State state(DataInputStream i) throws IOException {
        Deployment d=deployment(i);Block cursor=block(i);int index=i.readInt();int count=count(i);
        Map<String,Balance> balances=new HashMap<>(); for(int n=0;n<count;n++) put(balances,addr(i),new Balance(uint(i),uint(i)));
        Map<String,Position> positions=new HashMap<>();count=count(i);for(int n=0;n<count;n++) put(positions,hash(i),new Position(addr(i),uint(i),uint(i),uint(i),uint(i),tag(i,Status.values())));
        Map<BigInteger,Claim> claims=new HashMap<>();count=count(i);for(int n=0;n<count;n++) put(claims,uint(i),new Claim(addr(i),hash(i),hash(i),uint(i),uint(i),uint(i)));
        Map<String,BigInteger> nonces=new HashMap<>();count=count(i);for(int n=0;n<count;n++) put(nonces,addr(i),uint(i));
        Map<String,String> operations=new HashMap<>();count=count(i);for(int n=0;n<count;n++) put(operations,hash(i),hash(i));
        BigInteger free=uint(i),locked=uint(i),house=uint(i),actual=uint(i),unpaid=uint(i),head=uint(i),next=uint(i);boolean paused=bool(i);
        Map<TxKey,Bundle> observations=new HashMap<>();count=count(i);for(int n=0;n<count;n++) put(observations,new TxKey(hash(i),hash(i)),bundle(i));
        return new State(d,cursor,index,balances,positions,claims,nonces,operations,free,locked,house,actual,unpaid,head,next,paused,observations);
    }
}
