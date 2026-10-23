package com.pricetrack.exchange.custody.abi;

import java.math.BigInteger;
import java.util.Locale;

/** ABI syntax only. Valid records do not prove signatures, owner consent or executable economics. */
public final class CAbiModels {
    private CAbiModels() {}
    public static final BigInteger UINT_MAX=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    public static final int MAX_SIGNATURE_BYTES=512;
    public enum Target { CONTROLLER, VAULT, TOKEN }
    public enum RiskAction { START_CALL, CLEAR_CALL, LIQUIDATE }
    public enum VaultAction { DEPOSIT, WITHDRAW, FUND_HOUSE }
    public record Report(String operationId,String positionId,String accountRef,String symbolHash,
            BigInteger action,BigInteger expectedVersion,BigInteger expectedCallEpoch,BigInteger side,
            BigInteger leverage,BigInteger quantity,BigInteger entryPriceE8,BigInteger collateralBefore,
            BigInteger collateralAfter,BigInteger priceE8,BigInteger observedAt,BigInteger validUntil,
            String policyHash,String executor) {
        public Report {
            operationId=hash(operationId);positionId=hash(positionId);accountRef=hash(accountRef);symbolHash=hash(symbolHash);
            policyHash=hash(policyHash);executor=address(executor);u8(action);u8(side);u8(leverage);
            uint(expectedVersion);uint(expectedCallEpoch);uint(quantity);uint(entryPriceE8);uint(collateralBefore);
            uint(collateralAfter);uint(priceE8);uint(observedAt);uint(validUntil);
        }
    }
    public record Intent(String owner,String relayer,String operationId,String positionId,String accountRef,
            String symbolHash,BigInteger action,BigInteger expectedVersion,BigInteger expectedCallEpoch,
            BigInteger side,BigInteger leverage,BigInteger entryPriceE8,BigInteger collateralBefore,
            BigInteger collateralAfter,BigInteger priceMinE8,BigInteger priceMaxE8,BigInteger quantityMin,
            BigInteger quantityMax,BigInteger nonce,BigInteger issuedAt,BigInteger deadline,String policyHash) {
        public Intent {
            owner=address(owner);relayer=address(relayer);operationId=hash(operationId);positionId=hash(positionId);
            accountRef=hash(accountRef);symbolHash=hash(symbolHash);policyHash=hash(policyHash);
            u8(action);u8(side);u8(leverage);uint(expectedVersion);uint(expectedCallEpoch);uint(entryPriceE8);
            uint(collateralBefore);uint(collateralAfter);uint(priceMinE8);uint(priceMaxE8);uint(quantityMin);
            uint(quantityMax);uint(nonce);uint(issuedAt);uint(deadline);
        }
    }
    public sealed interface Call permits Direct,Relayed,Risk,Cancel,Pause,VaultAmount,ProcessClaim,Transfer,Approve,TransferFrom {}
    public record Direct(Report report,String priceSignature,Intent intent) implements Call {
        public Direct { required(report);required(intent);priceSignature=signature(priceSignature); }
    }
    public record Relayed(Report report,String priceSignature,Intent intent,String userSignature) implements Call {
        public Relayed {required(report);required(intent);priceSignature=signature(priceSignature);userSignature=signature(userSignature);}
    }
    public record Risk(RiskAction action,Report report,String priceSignature) implements Call {
        public Risk {required(action);required(report);priceSignature=signature(priceSignature);}
    }
    public record Cancel(BigInteger nonce) implements Call {public Cancel {uint(nonce);}}
    public record Pause(boolean paused) implements Call {}
    public record VaultAmount(VaultAction action,BigInteger amount) implements Call {public VaultAmount {required(action);uint(amount);}}
    public record ProcessClaim() implements Call {}
    public record Transfer(String to,BigInteger amount) implements Call {public Transfer {to=address(to);uint(amount);}}
    public record Approve(String spender,BigInteger amount) implements Call {public Approve {spender=address(spender);uint(amount);}}
    public record TransferFrom(String from,String to,BigInteger amount) implements Call {public TransferFrom {from=address(from);to=address(to);uint(amount);}}
    public static Target target(Call call) {
        if(call instanceof VaultAmount||call instanceof ProcessClaim) return Target.VAULT;
        if(call instanceof Transfer||call instanceof Approve||call instanceof TransferFrom) return Target.TOKEN;
        required(call);return Target.CONTROLLER;
    }
    public static BigInteger uint(BigInteger n) {
        if(n==null||n.signum()<0||n.compareTo(UINT_MAX)>0) throw invalid("UINT256");return n;
    }
    private static void u8(BigInteger n) {if(uint(n).bitLength()>8)throw invalid("UINT8");}
    public static String address(String s) {return hex(s,20,"ADDRESS");}
    public static String hash(String s) {return hex(s,32,"BYTES32");}
    private static String hex(String s,int bytes,String code) {
        if(s==null||s.length()!=2+bytes*2||!s.matches("0x[0-9a-fA-F]+"))throw invalid(code);
        return s.toLowerCase(Locale.ROOT);
    }
    public static String signature(String s) {
        if(s==null||s.length()>2+2*MAX_SIGNATURE_BYTES||(s.length()&1)!=0||!s.matches("0x[0-9a-fA-F]*"))throw invalid("BYTES");
        return s.toLowerCase(Locale.ROOT);
    }
    private static void required(Object o) {if(o==null)throw invalid("NULL");}
    public static IllegalArgumentException invalid(String code) {return new IllegalArgumentException("C_ABI_"+code);}
}
