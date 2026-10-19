package com.pricetrack.exchange.futures.audit;

import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import java.math.BigInteger;
import java.util.*;
import org.web3j.utils.Numeric;

/** Reconstructs the PRE-operation chain state, not today's deadline/risk status, for historical verification. */
public final class FuturesAuditVerifier {
    public static class HistoricalCutUnavailable extends RuntimeException {}
    private FuturesAuditVerifier() {}
    public static List<BigInteger> words(String value) {
        if(value==null||!value.matches("0x[0-9a-fA-F]{704}"))throw new Failure("AUDIT_STATE_CONFLICT");
        var result=new ArrayList<BigInteger>();for(int i=0;i<11;i++)result.add(new BigInteger(value.substring(2+64*i,66+64*i),16));return result;
    }
    public static Position before(Position p,Operation op,String encoded) {
        var w=words(encoded);
        if(op.action()==Action.OPEN) {
            if(w.stream().anyMatch(n->n.signum()!=0)||op.expectedVersion()!=0||op.expectedEpoch()!=0)throw new Failure("AUDIT_PRESTATE_CONFLICT");
            return copy(p,op,0,0,"HEALTHY");
        }
        // Block-level storage cannot distinguish multiple operations for this position within one block.
        if(!w.get(7).equals(BigInteger.valueOf(op.expectedVersion())))throw new HistoricalCutUnavailable();
        if(!w.subList(0,6).equals(List.of(Numeric.toBigInt(p.accountRef()),BigInteger.valueOf(p.side().ordinal()),BigInteger.valueOf(p.leverage()),p.quantity(),p.entryPrice(),op.collateralBefore()))
            || !Set.of(BigInteger.ONE,BigInteger.TWO).contains(w.get(6))||!w.get(8).equals(BigInteger.valueOf(op.expectedEpoch())))throw new Failure("AUDIT_PRESTATE_CONFLICT");
        return copy(p,op,w.get(9).longValueExact(),w.get(10).longValueExact(),w.get(6).equals(BigInteger.TWO)?"GRACE":"HEALTHY");
    }
    private static Position copy(Position p,Operation o,long deadline,long observed,String risk){return new Position(p.id(),p.userId(),p.accountRef(),p.side(),p.leverage(),p.quantity(),p.entryPrice(),o.collateralBefore(),"OPEN",risk,o.expectedVersion(),o.expectedEpoch(),deadline,observed,o.id(),null,null,null,p.createdAt());}
    public static boolean current(Position p,String encoded){
        var w=words(encoded);
        if(p.version()==0&&p.status().equals("OPEN_FAILED"))return w.stream().allMatch(n->n.signum()==0);
        int status=p.status().equals("CLOSED")?3:p.status().equals("LIQUIDATED")?4:p.riskStatus().equals("GRACE")?2:1;
        return w.equals(List.of(Numeric.toBigInt(p.accountRef()),BigInteger.valueOf(p.side().ordinal()),BigInteger.valueOf(p.leverage()),p.quantity(),p.entryPrice(),p.collateral(),BigInteger.valueOf(status),BigInteger.valueOf(p.version()),BigInteger.valueOf(p.callEpoch()),BigInteger.valueOf(p.deadline()),BigInteger.valueOf(p.lastObservedAt())));
    }
}
