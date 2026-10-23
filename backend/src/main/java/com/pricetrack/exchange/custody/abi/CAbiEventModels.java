package com.pricetrack.exchange.custody.abi;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

/** Full Solidity event fields. Syntax only: no deployment, signature, economics or canonicality proof. */
public final class CAbiEventModels {
    private CAbiEventModels() {}
    public static final BigInteger UINT_MAX=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    public static final BigInteger INT_MIN=BigInteger.ONE.shiftLeft(255).negate();
    public static final BigInteger INT_MAX=BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE);
    public enum EmitterKind { TOKEN, VAULT, CONTROLLER, RULES }
    public sealed interface Event permits Transfer,Approval,Deposited,Withdrawn,HouseFunded,CollateralLocked,
        PositionSettled,ProfitClaimCreated,ProfitClaimPaid,CUserIntentExecuted,CRiskExecuted,CNonceCancelled,
        NewPositionsPauseChanged,OperationApplied,FuturesReportConsumed,EIP712DomainChanged {}

    public record Transfer(String from,String to,BigInteger value) implements Event {
        public Transfer {from=address(from);to=address(to);uint(value);}
    }
    public record Approval(String owner,String spender,BigInteger value) implements Event {
        public Approval {owner=address(owner);spender=address(spender);uint(value);}
    }
    public record Deposited(String owner,BigInteger amount) implements Event {public Deposited {owner=address(owner);uint(amount);}}
    public record Withdrawn(String owner,BigInteger amount) implements Event {public Withdrawn {owner=address(owner);uint(amount);}}
    public record HouseFunded(String contributor,BigInteger amount) implements Event {public HouseFunded {contributor=address(contributor);uint(amount);}}
    public record CollateralLocked(String operationId,String positionId,String owner,BigInteger delta,BigInteger lockedAfter) implements Event {
        public CollateralLocked {operationId=hash(operationId);positionId=hash(positionId);owner=address(owner);uint(delta);uint(lockedAfter);}
    }
    public record PositionSettled(String operationId,String positionId,String owner,BigInteger collateral,
        BigInteger realizedPnl,BigInteger badDebt,BigInteger paidProfit,BigInteger claimId) implements Event {
        public PositionSettled {operationId=hash(operationId);positionId=hash(positionId);owner=address(owner);
            uint(collateral);signed(realizedPnl);uint(badDebt);uint(paidProfit);uint(claimId);}
    }
    public record ProfitClaimCreated(BigInteger claimId,String owner,String positionId,String operationId,BigInteger amount) implements Event {
        public ProfitClaimCreated {uint(claimId);owner=address(owner);positionId=hash(positionId);operationId=hash(operationId);uint(amount);}
    }
    public record ProfitClaimPaid(BigInteger claimId,String owner,BigInteger paidAmount,BigInteger remainingAmount) implements Event {
        public ProfitClaimPaid {uint(claimId);owner=address(owner);uint(paidAmount);uint(remainingAmount);}
    }
    public record CUserIntentExecuted(String operationId,String positionId,String owner,String intentDigest,
        String reportDigest,BigInteger action,BigInteger nonce,boolean direct) implements Event {
        public CUserIntentExecuted {operationId=hash(operationId);positionId=hash(positionId);owner=address(owner);
            intentDigest=hash(intentDigest);reportDigest=hash(reportDigest);u8(action);uint(nonce);}
    }
    public record CRiskExecuted(String operationId,String positionId,String owner,String reportDigest,BigInteger action) implements Event {
        public CRiskExecuted {operationId=hash(operationId);positionId=hash(positionId);owner=address(owner);reportDigest=hash(reportDigest);u8(action);}
    }
    public record CNonceCancelled(String owner,BigInteger nonce) implements Event {public CNonceCancelled {owner=address(owner);uint(nonce);}}
    public record NewPositionsPauseChanged(boolean paused) implements Event {}
    public record OperationApplied(String operationId,String positionId,BigInteger action,BigInteger version,
        BigInteger priceE8,BigInteger collateral,BigInteger unrealized,BigInteger realized,BigInteger badDebt,
        BigInteger callEpoch,BigInteger deadline) implements Event {
        public OperationApplied {operationId=hash(operationId);positionId=hash(positionId);u8(action);uint(version);
            uint(priceE8);uint(collateral);signed(unrealized);signed(realized);uint(badDebt);uint(callEpoch);uint(deadline);}
    }
    public record FuturesReportConsumed(String operationId,BigInteger observedAt,BigInteger validUntil) implements Event {
        public FuturesReportConsumed {operationId=hash(operationId);uint(observedAt);uint(validUntil);}
    }
    /** Inherited ABI syntax; current immutable C contracts expose no domain update function. */
    public record EIP712DomainChanged() implements Event {}
    /** An untrusted RPC-like log envelope; emitter is retained, never authenticated here. */
    public record Log(String emitter,List<String> topics,String data) {
        public Log {
            emitter=address(emitter);
            if(topics==null||topics.isEmpty()||topics.size()>4)throw invalid("TOPICS");
            topics=topics.stream().map(CAbiEventModels::hash).toList();
            if(data==null||data.length()>2+9*64||(data.length()&1)!=0||!data.matches("0x[0-9a-fA-F]*"))throw invalid("DATA_HEX");
            data=data.toLowerCase(Locale.ROOT);
        }
    }
    static void uint(BigInteger value) {if(value==null||value.signum()<0||value.compareTo(UINT_MAX)>0)throw invalid("UINT256");}
    static void u8(BigInteger value) {uint(value);if(value.bitLength()>8)throw invalid("UINT8");}
    static void signed(BigInteger value) {if(value==null||value.compareTo(INT_MIN)<0||value.compareTo(INT_MAX)>0)throw invalid("INT256");}
    static String address(String value) {return hex(value,20,"ADDRESS");}
    static String hash(String value) {return hex(value,32,"BYTES32");}
    private static String hex(String value,int bytes,String code) {
        if(value==null||value.length()!=2+bytes*2||!value.matches("0x[0-9a-fA-F]+"))throw invalid(code);
        return value.toLowerCase(Locale.ROOT);
    }
    static IllegalArgumentException invalid(String code) {return new IllegalArgumentException("C_ABI_EVENT_"+code);}
}
