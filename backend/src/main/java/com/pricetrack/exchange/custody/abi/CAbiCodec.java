package com.pricetrack.exchange.custody.abi;

import static com.pricetrack.exchange.custody.abi.CAbiModels.*;
import java.math.BigInteger;
import java.util.*;

/** Strict canonical calldata subset. Selector constants independently obtained with local cast sig 1.5.1.
 * No EIP-712 hashing/recovery, authentication, financial validation, Receipt decoding or RPC is performed. */
public final class CAbiCodec {
    public static final int MAX_CALLDATA_BYTES=16*1024;
    private static final HexFormat HEX=HexFormat.of();
    public String encode(Call call) {return "0x"+HEX.formatHex(encodeBytes(call));}
    public byte[] encodeBytes(Call call) {
        byte[] encoded;
        if(call instanceof Direct c) {
            byte[] tail=tail(c.priceSignature());
            encoded=join(selector("c24ad93a"),report(c.report()),word(BigInteger.valueOf(41*32)),intent(c.intent()),tail);
        } else if(call instanceof Relayed c) {
            byte[] price=tail(c.priceSignature()),user=tail(c.userSignature());
            encoded=join(selector("b6657fd9"),report(c.report()),word(BigInteger.valueOf(42*32)),intent(c.intent()),
                word(BigInteger.valueOf(42*32+price.length)),price,user);
        } else if(call instanceof Risk c) {
            String sel=switch(c.action()) {case START_CALL->"c5eeafdc";case CLEAR_CALL->"aade63fb";case LIQUIDATE->"3f23aa97";};
            encoded=join(selector(sel),report(c.report()),word(BigInteger.valueOf(19*32)),tail(c.priceSignature()));
        } else if(call instanceof Cancel c) encoded=join(selector("cfdd7aea"),word(c.nonce()));
        else if(call instanceof Pause c) encoded=join(selector("5fefcf07"),word(c.paused()?BigInteger.ONE:BigInteger.ZERO));
        else if(call instanceof VaultAmount c) {
            String sel=switch(c.action()) {case DEPOSIT->"b6b55f25";case WITHDRAW->"2e1a7d4d";case FUND_HOUSE->"8746cf98";};
            encoded=join(selector(sel),word(c.amount()));
        } else if(call instanceof ProcessClaim) encoded=selector("da28fbe7");
        else if(call instanceof Transfer c) encoded=join(selector("a9059cbb"),addressWord(c.to()),word(c.amount()));
        else if(call instanceof Approve c) encoded=join(selector("095ea7b3"),addressWord(c.spender()),word(c.amount()));
        else if(call instanceof TransferFrom c) encoded=join(selector("23b872dd"),addressWord(c.from()),addressWord(c.to()),word(c.amount()));
        else throw invalid("UNSUPPORTED_CALL");
        if(encoded.length>MAX_CALLDATA_BYTES)throw invalid("SIZE");return encoded;
    }
    public Call decode(Target target,String calldata) {
        if(calldata==null||calldata.length()>2+2*MAX_CALLDATA_BYTES||(calldata.length()&1)!=0||!calldata.matches("0x[0-9a-fA-F]*"))throw invalid("HEX");
        return decode(target,HEX.parseHex(calldata.substring(2)));
    }
    public Call decode(Target target,byte[] data) {
        if(target==null||data==null||data.length<4||data.length>MAX_CALLDATA_BYTES||(data.length-4)%32!=0)throw invalid("SIZE");
        String selector=HEX.formatHex(data,0,4);Call call;
        switch(selector) {
            case "c24ad93a" -> {minimum(data,41);Report report=readReport(data);Intent intent=readIntent(data,19);
                Dynamic price=dynamic(data,18,41*32);end(data,price.end());call=new Direct(report,price.hex(),intent);}
            case "b6657fd9" -> {minimum(data,42);Report report=readReport(data);Intent intent=readIntent(data,19);
                Dynamic price=dynamic(data,18,42*32),user=dynamic(data,41,price.end());end(data,user.end());call=new Relayed(report,price.hex(),intent,user.hex());}
            case "c5eeafdc","aade63fb","3f23aa97" -> {minimum(data,19);Report report=readReport(data);Dynamic price=dynamic(data,18,19*32);end(data,price.end());
                RiskAction action=selector.equals("c5eeafdc")?RiskAction.START_CALL:selector.equals("aade63fb")?RiskAction.CLEAR_CALL:RiskAction.LIQUIDATE;
                call=new Risk(action,report,price.hex());}
            case "cfdd7aea" -> {exact(data,1);call=new Cancel(number(data,0));}
            case "5fefcf07" -> {exact(data,1);BigInteger value=number(data,0);if(value.compareTo(BigInteger.ONE)>0)throw invalid("BOOL");call=new Pause(value.signum()!=0);}
            case "b6b55f25","2e1a7d4d","8746cf98" -> {exact(data,1);VaultAction action=selector.equals("b6b55f25")?VaultAction.DEPOSIT:selector.equals("2e1a7d4d")?VaultAction.WITHDRAW:VaultAction.FUND_HOUSE;call=new VaultAmount(action,number(data,0));}
            case "da28fbe7" -> {exact(data,0);call=new ProcessClaim();}
            case "a9059cbb" -> {exact(data,2);call=new Transfer(readAddress(data,0),number(data,1));}
            case "095ea7b3" -> {exact(data,2);call=new Approve(readAddress(data,0),number(data,1));}
            case "23b872dd" -> {exact(data,3);call=new TransferFrom(readAddress(data,0),readAddress(data,1),number(data,2));}
            default -> throw invalid("SELECTOR");
        }
        if(CAbiModels.target(call)!=target)throw invalid("TARGET");
        if(!Arrays.equals(data,encodeBytes(call)))throw invalid("NONCANONICAL");return call;
    }
    private record Dynamic(String hex,int end) {}
    private Dynamic dynamic(byte[] data,int offsetWord,int expectedOffset) {
        BigInteger offset=number(data,offsetWord);
        if(!offset.equals(BigInteger.valueOf(expectedOffset))||expectedOffset%32!=0)throw invalid("OFFSET");
        int absolute=4+expectedOffset;if(absolute>data.length-32)throw invalid("DYNAMIC_LENGTH");
        BigInteger length=new BigInteger(1,Arrays.copyOfRange(data,absolute,absolute+32));
        if(length.compareTo(BigInteger.valueOf(MAX_SIGNATURE_BYTES))>0)throw invalid("BYTES_LIMIT");
        int size=length.intValueExact(),padded=((size+31)/32)*32,end=expectedOffset+32+padded;
        if(4+end>data.length)throw invalid("DYNAMIC_LENGTH");
        for(int n=absolute+32+size;n<4+end;n++)if(data[n]!=0)throw invalid("PADDING");
        return new Dynamic("0x"+HEX.formatHex(data,absolute+32,absolute+32+size),end);
    }
    private byte[] tail(String signature) {
        byte[] value=HEX.parseHex(signature.substring(2)),padded=new byte[((value.length+31)/32)*32];
        System.arraycopy(value,0,padded,0,value.length);return join(word(BigInteger.valueOf(value.length)),padded);
    }
    private byte[] report(Report r) {return join(bytes32(r.operationId()),bytes32(r.positionId()),bytes32(r.accountRef()),bytes32(r.symbolHash()),
        word(r.action()),word(r.expectedVersion()),word(r.expectedCallEpoch()),word(r.side()),word(r.leverage()),word(r.quantity()),word(r.entryPriceE8()),
        word(r.collateralBefore()),word(r.collateralAfter()),word(r.priceE8()),word(r.observedAt()),word(r.validUntil()),bytes32(r.policyHash()),addressWord(r.executor()));}
    private byte[] intent(Intent i) {return join(addressWord(i.owner()),addressWord(i.relayer()),bytes32(i.operationId()),bytes32(i.positionId()),bytes32(i.accountRef()),bytes32(i.symbolHash()),
        word(i.action()),word(i.expectedVersion()),word(i.expectedCallEpoch()),word(i.side()),word(i.leverage()),word(i.entryPriceE8()),word(i.collateralBefore()),word(i.collateralAfter()),
        word(i.priceMinE8()),word(i.priceMaxE8()),word(i.quantityMin()),word(i.quantityMax()),word(i.nonce()),word(i.issuedAt()),word(i.deadline()),bytes32(i.policyHash()));}
    private Report readReport(byte[] d) {return new Report(readHash(d,0),readHash(d,1),readHash(d,2),readHash(d,3),number(d,4),number(d,5),number(d,6),number(d,7),number(d,8),
        number(d,9),number(d,10),number(d,11),number(d,12),number(d,13),number(d,14),number(d,15),readHash(d,16),readAddress(d,17));}
    private Intent readIntent(byte[] d,int s) {return new Intent(readAddress(d,s),readAddress(d,s+1),readHash(d,s+2),readHash(d,s+3),readHash(d,s+4),readHash(d,s+5),
        number(d,s+6),number(d,s+7),number(d,s+8),number(d,s+9),number(d,s+10),number(d,s+11),number(d,s+12),number(d,s+13),number(d,s+14),number(d,s+15),
        number(d,s+16),number(d,s+17),number(d,s+18),number(d,s+19),number(d,s+20),readHash(d,s+21));}
    private static byte[] selector(String hex) {return HEX.parseHex(hex);}
    private static byte[] bytes32(String hex) {return HEX.parseHex(hex.substring(2));}
    private static byte[] addressWord(String address) {byte[] word=new byte[32];System.arraycopy(HEX.parseHex(address.substring(2)),0,word,12,20);return word;}
    private static byte[] word(BigInteger value) {uint(value);byte[] raw=value.toByteArray(),word=new byte[32];int count=Math.min(raw.length,32);System.arraycopy(raw,raw.length-count,word,32-count,count);return word;}
    private static BigInteger number(byte[] data,int index) {int start=4+index*32;if(start<4||start>data.length-32)throw invalid("WORD");return new BigInteger(1,Arrays.copyOfRange(data,start,start+32));}
    private static String readHash(byte[] data,int index) {int start=4+index*32;number(data,index);return "0x"+HEX.formatHex(data,start,start+32);}
    private static String readAddress(byte[] data,int index) {int start=4+index*32;number(data,index);for(int n=start;n<start+12;n++)if(data[n]!=0)throw invalid("ADDRESS_PADDING");return "0x"+HEX.formatHex(data,start+12,start+32);}
    private static void minimum(byte[] data,int words) {if(data.length<4+words*32)throw invalid("HEAD");}
    private static void exact(byte[] data,int words) {if(data.length!=4+words*32)throw invalid("LENGTH");}
    private static void end(byte[] data,int bodyEnd) {if(data.length!=4+bodyEnd)throw invalid("TRAILING");}
    private static byte[] join(byte[]... chunks) {int size=0;for(byte[] chunk:chunks)size=Math.addExact(size,chunk.length);byte[] out=new byte[size];int at=0;for(byte[] chunk:chunks){System.arraycopy(chunk,0,out,at,chunk.length);at+=chunk.length;}return out;}
}
