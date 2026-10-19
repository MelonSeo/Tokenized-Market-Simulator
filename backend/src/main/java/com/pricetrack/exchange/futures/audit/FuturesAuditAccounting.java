package com.pricetrack.exchange.futures.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.reserve.ReserveModels.*;
import java.math.BigInteger;
import java.util.*;
import org.web3j.crypto.Hash;

/** Exact DB conservation only. Matching these checks is not evidence of ERC-20 collateral escrow. */
public final class FuturesAuditAccounting {
    private static final BigInteger Z=BigInteger.ZERO;
    private FuturesAuditAccounting() {}
    private static void eq(List<Check> c,String key,BigInteger e,BigInteger a){c.add(new Check(key,e.toString(),a.toString(),a.subtract(e).toString(),e.equals(a)));}
    private static void ok(List<Check> c,String key,boolean b){eq(c,key,BigInteger.ONE,b?BigInteger.ONE:Z);}
    public static FuturesImpact impact(FuturesAuditSnapshot s,ObjectMapper json) {
        var checks=new ArrayList<Check>();var wallets=new TreeMap<Long,BigInteger>();var locks=new TreeMap<Long,BigInteger>();
        var ops=new HashMap<String,Operation>();var positions=new HashMap<String,Position>();
        s.operations().forEach(o->ops.put(o.id(),o));s.positions().forEach(p->positions.put(p.id(),p));
        ok(checks,"FUTURES_POLICY",FuturesLedger.POLICY_HASH.equals(s.policy())&&FuturesLedger.INITIAL_CAPITAL.equals(s.initial()));
        BigInteger houseDelta=Z,houseReserved=Z;var byPosition=new HashMap<String,BigInteger>();var counts=new HashMap<String,Integer>();
        for(var j:s.journal()) {
            var o=ops.get(j.operationId());var p=positions.get(j.positionId());
            boolean link=o!=null&&p!=null&&o.positionId().equals(j.positionId())&&o.userId()==j.userId()&&p.userId()==j.userId();
            ok(checks,"FUTURES_JOURNAL_LINK_"+j.id(),link);
            counts.merge(j.operationId()+":"+j.type(),1,Integer::sum);houseDelta=houseDelta.add(j.house());
            byPosition.merge(j.positionId(),j.locked(),BigInteger::add);
            if(link) {
                boolean terminal=o.action()==Action.CLOSE||o.action()==Action.LIQUIDATE;
                BigInteger wallet=Z,locked=Z,house=Z,debt=Z;boolean valid=true;
                switch(j.type()) {
                    case "RESERVE" -> {locked=o.reservedMargin();valid=locked.signum()>0;}
                    case "CONFIRM" -> {valid=o.state().equals("CONFIRMED");wallet=o.expectedRealized();house=wallet.negate();locked=terminal?o.collateralBefore().negate():Z;debt=o.expectedBadDebt();}
                    case "RELEASE" -> {valid=o.state().equals("REJECTED");locked=o.reservedMargin().negate();}
                    case "REVERT_RELEASE" -> {valid=o.state().equals("REVERTED");locked=o.reservedMargin().negate();}
                    default -> valid=false;
                }
                ok(checks,"FUTURES_JOURNAL_VALUES_"+j.id(),valid&&wallet.equals(j.wallet())&&locked.equals(j.locked())&&house.equals(j.house())&&debt.equals(j.debt()));
                // Only finalized CONFIRM entries change the user's amount; margin is already inside amount.
                if(j.type().equals("CONFIRM")&&o.state().equals("CONFIRMED"))wallets.merge(j.userId(),j.wallet(),BigInteger::add);
            }
        }
        for(var o:s.operations()) {
            var p=positions.get(o.positionId());boolean link=p!=null&&p.userId()==o.userId();ok(checks,"FUTURES_OPERATION_LINK_"+o.id(),link);
            boolean pending=Set.of("PREPARED","SIGNED","SUBMITTED","REVIEW_REQUIRED").contains(o.state());
            if(pending)houseReserved=houseReserved.add(o.reservedHouse());
            ok(checks,"FUTURES_OPERATION_STATE_"+o.id(),pending||Set.of("CONFIRMED","REJECTED","REVERTED").contains(o.state()));
            boolean terminal=o.action()==Action.CLOSE||o.action()==Action.LIQUIDATE;
            if(link)try {
                var mark=FuturesMath.mark(p.side(),p.quantity(),p.entryPrice(),o.collateralAfter(),o.price());
                eq(checks,"FUTURES_PNL_"+o.id(),terminal?mark.realized():Z,o.expectedRealized());
                eq(checks,"FUTURES_DEBT_"+o.id(),terminal?mark.badDebt():Z,o.expectedBadDebt());
                eq(checks,"FUTURES_RESERVE_HOUSE_"+o.id(),terminal?mark.realized().max(Z):Z,o.reservedHouse());
                BigInteger added=o.action()==Action.OPEN?o.collateralAfter():o.action()==Action.TOP_UP?o.collateralAfter().subtract(o.collateralBefore()):Z;
                eq(checks,"FUTURES_RESERVE_MARGIN_"+o.id(),added,o.reservedMargin());
                if(o.action()==Action.OPEN)eq(checks,"FUTURES_OPEN_QUANTITY_"+o.id(),FuturesMath.quantity(o.collateralAfter(),p.leverage(),p.entryPrice()),p.quantity());
                ok(checks,"FUTURES_COLLATERAL_"+o.id(),o.action()==Action.OPEN?o.collateralBefore().signum()==0:o.action()==Action.TOP_UP?added.signum()>0:o.collateralAfter().equals(o.collateralBefore()));
            }catch(RuntimeException invalid){ok(checks,"FUTURES_MATH_"+o.id(),false);}
            for(String type:List.of("RESERVE","CONFIRM","RELEASE","REVERT_RELEASE")) {
                boolean expected=type.equals("RESERVE")?o.reservedMargin().signum()>0:type.equals("CONFIRM")?o.state().equals("CONFIRMED"):type.equals("RELEASE")?o.state().equals("REJECTED"):o.state().equals("REVERTED");
                eq(checks,"FUTURES_JOURNAL_COUNT_"+o.id()+":"+type,expected?BigInteger.ONE:Z,BigInteger.valueOf(counts.getOrDefault(o.id()+":"+type,0)));
            }
            long txs=s.transactions().stream().filter(t->t.stored().operationId().equals(o.id())).count();
            eq(checks,"FUTURES_TX_COUNT_"+o.id(),Set.of("PREPARED","REJECTED").contains(o.state())?Z:BigInteger.ONE,BigInteger.valueOf(txs));
        }
        for(var p:s.positions()) {
            ok(checks,"FUTURES_POSITION_STATE_"+p.id(),Set.of("OPEN_PENDING","OPEN","CLOSING","LIQUIDATING","REVIEW_REQUIRED","CLOSED","LIQUIDATED","OPEN_FAILED").contains(p.status()));
            ok(checks,"FUTURES_POSITION_SETTLEMENT_"+p.id(),settlementMatches(p,s.operations()));
            eq(checks,"FUTURES_POSITION_VERSION_"+p.id(),BigInteger.valueOf(s.operations().stream().filter(o->o.positionId().equals(p.id())&&o.state().equals("CONFIRMED")).count()),BigInteger.valueOf(p.version()));
            boolean terminal=Set.of("CLOSED","LIQUIDATED","OPEN_FAILED").contains(p.status());
            BigInteger expected=terminal?Z:p.version()>0?p.collateral():Z;
            if(p.pendingOperation()!=null) {
                var o=ops.get(p.pendingOperation());ok(checks,"FUTURES_PENDING_LINK_"+p.id(),o!=null&&o.positionId().equals(p.id())&&o.userId()==p.userId()&&Set.of("PREPARED","SIGNED","SUBMITTED","REVIEW_REQUIRED").contains(o.state()));
                if(o!=null)expected=expected.add(o.reservedMargin());
            }
            eq(checks,"FUTURES_POSITION_LOCK_"+p.id(),expected,byPosition.getOrDefault(p.id(),Z));locks.merge(p.userId(),expected,BigInteger::add);
            long active=s.positions().stream().filter(q->q.userId()==p.userId()&&!Set.of("CLOSED","LIQUIDATED","OPEN_FAILED").contains(q.status())).count();
            ok(checks,"FUTURES_SINGLE_ACTIVE_"+p.id(),active<=1);
            var guard=s.guards().stream().filter(g->g.userId()==p.userId()).findFirst().orElse(null);
            ok(checks,"FUTURES_GUARD_"+p.id(),guard!=null&&guard.accountRef().equals(p.accountRef())&&(terminal?!p.id().equals(guard.activePosition()):p.id().equals(guard.activePosition())));
        }
        for(var t:s.transactions())ok(checks,"FUTURES_TX_OPERATION_"+t.stored().operationId(),ops.containsKey(t.stored().operationId()));
        for(var g:s.guards())if(g.activePosition()!=null)ok(checks,"FUTURES_ACTIVE_GUARD_"+g.userId(),positions.containsKey(g.activePosition())&&positions.get(g.activePosition()).userId()==g.userId());
        eq(checks,"FUTURES_HOUSE",s.initial().add(houseDelta),s.house());eq(checks,"FUTURES_HOUSE_RESERVED",houseReserved,s.reserved());
        ok(checks,"FUTURES_HOUSE_RANGE",s.house().signum()>=0&&s.reserved().signum()>=0&&s.reserved().compareTo(s.house())<=0);
        try {return new FuturesImpact(wallets,locks,List.copyOf(checks),Hash.sha3String(json.writeValueAsString(s)),s.unsettled(),s.empty());}
        catch(Exception failure){throw new FuturesAuditSnapshot.Unavailable();}
    }
    /** Terminal projections must agree with the final canonical operation, not merely its wallet journal. */
    public static boolean settlementMatches(Position p,List<Operation> operations) {
        if(!Set.of("CLOSED","LIQUIDATED").contains(p.status()))return p.realized()==null&&p.badDebt()==null;
        var last=operations.stream().filter(o->o.positionId().equals(p.id())&&o.state().equals("CONFIRMED"))
            .max(Comparator.comparingLong(Operation::expectedVersion)).orElse(null);
        return last!=null&&last.expectedVersion()+1==p.version()
            &&last.action()==(p.status().equals("CLOSED")?Action.CLOSE:Action.LIQUIDATE)
            &&last.expectedRealized().equals(p.realized())&&last.expectedBadDebt().equals(p.badDebt());
    }
}
