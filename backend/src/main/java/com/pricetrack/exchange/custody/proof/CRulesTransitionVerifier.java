package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CRulesTransitionModels.*;
import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.OperationApplied;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.FuturesReportConsumed;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;

/** Pure rules transition and expected full events under caller-supplied deployment/prestate.
 * Does not verify signatures, runtime executor msg.sender, owner consent or canonical chain state. */
public final class CRulesTransitionVerifier {
    public static final BigInteger REPORT_TTL=BigInteger.valueOf(30),GRACE_SECONDS=BigInteger.valueOf(60);
    private static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE,TWO=BigInteger.TWO;
    private static final BigInteger UINT_MAX=ONE.shiftLeft(256).subtract(ONE);
    private static final BigInteger INT_MIN=ONE.shiftLeft(255).negate(),INT_MAX=ONE.shiftLeft(255).subtract(ONE);
    public static final class Rejected extends IllegalArgumentException {
        public Rejected(String code) {super("C_RULES_"+code);}
    }

    public Result verify(Input input) {
        require(input!=null&&input.expectedAction()!=null&&input.report()!=null&&input.terms()!=null&&input.before()!=null&&input.prestateKeys()!=null,"INPUT");
        Report r=input.report();Terms terms=input.terms();Position old=input.before();Action action=input.expectedAction();
        require(input.prestateKeys().positionId().equals(r.positionId())&&input.prestateKeys().accountRef().equals(r.accountRef())
            &&input.prestateKeys().operationId().equals(r.operationId()),"PRESTATE_KEYS");
        BigInteger now=uint(input.blockTimestamp()); validatePrestate(old,input.activePositionForAccount(),r.positionId());
        require(!terms.executor().equals("0x"+"0".repeat(40))&&!terms.symbolHash().equals(ZERO_HASH)&&!terms.policyHash().equals(ZERO_HASH),"TERMS");
        require(r.executor().equals(terms.executor()),"EXECUTOR");
        require(!r.operationId().equals(ZERO_HASH)&&!r.positionId().equals(ZERO_HASH)&&!r.accountRef().equals(ZERO_HASH)
            &&r.symbolHash().equals(terms.symbolHash())&&r.policyHash().equals(terms.policyHash())
            &&r.action().equals(action.code())&&r.side().compareTo(ONE)<=0,"REPORT");
        require(r.observedAt().compareTo(UINT_MAX.subtract(REPORT_TTL))<=0
            &&r.validUntil().equals(r.observedAt().add(REPORT_TTL))
            &&r.observedAt().compareTo(add(now,TWO))<=0&&now.compareTo(r.validUntil())<=0,"REPORT_TIME");
        require(!input.operationUsed(),"OPERATION_USED");

        String account;BigInteger side,leverage,quantity,entry,collateral;
        Status status=old.status();BigInteger epoch=old.callEpoch(),deadline=old.deadline();
        if(action==Action.OPEN) {
            require(status==Status.NONE&&input.activePositionForAccount().equals(ZERO_HASH),"OPEN_STATE");
            require(r.expectedVersion().signum()==0&&r.expectedCallEpoch().signum()==0&&r.collateralBefore().signum()==0
                &&r.entryPriceE8().equals(r.priceE8()),"OPEN_REPORT");
            int l=leverage(r.leverage());
            BigInteger calculated=FuturesMath.quantity(r.collateralAfter(),l,r.priceE8());
            require(r.quantity().equals(calculated),"OPEN_QUANTITY");
            account=r.accountRef();side=r.side();leverage=r.leverage();quantity=r.quantity();
            entry=r.priceE8();collateral=r.collateralAfter();status=Status.ACTIVE;
        } else {
            require(status==Status.ACTIVE||status==Status.MARGIN_CALL,"STATE");
            require(old.version().equals(r.expectedVersion())&&old.callEpoch().equals(r.expectedCallEpoch()),"VERSION_EPOCH");
            require(old.accountRef().equals(r.accountRef())&&old.side().equals(r.side())&&old.leverage().equals(r.leverage())
                &&old.quantity().equals(r.quantity())&&old.entryPriceE8().equals(r.entryPriceE8())
                &&old.collateral().equals(r.collateralBefore())&&r.observedAt().compareTo(old.lastObservedAt())>=0,"POSITION_REPORT");
            account=old.accountRef();side=old.side();leverage=old.leverage();quantity=old.quantity();entry=old.entryPriceE8();
            collateral=old.collateral();
            if(action==Action.TOP_UP) {
                require(r.collateralAfter().compareTo(collateral)>0,"TOP_UP_AMOUNT");
                amount(r.collateralAfter());collateral=r.collateralAfter();
            } else require(r.collateralAfter().equals(collateral),"COLLATERAL");
        }
        FuturesMath.Mark mark=FuturesMath.mark(side.signum()==0?FuturesMath.Side.LONG:FuturesMath.Side.SHORT,
            quantity,entry,collateral,r.priceE8());
        signed(mark.unrealized());signed(mark.equity());signed(mark.realized());uint(mark.badDebt());uint(mark.maintenance());uint(mark.notional());
        boolean risk=mark.atRisk();
        if(action==Action.OPEN)require(!risk,"OPEN_RISK");
        if(action==Action.START_CALL) {
            require(status==Status.ACTIVE&&risk,"START_RISK");status=Status.MARGIN_CALL;epoch=add(epoch,ONE);deadline=add(now,GRACE_SECONDS);
        } else if(action==Action.CLEAR_CALL) {
            require(status==Status.MARGIN_CALL&&!risk,"CLEAR_RISK");status=Status.ACTIVE;deadline=ZERO;
        } else if(action==Action.TOP_UP&&status==Status.MARGIN_CALL&&!risk) {
            status=Status.ACTIVE;deadline=ZERO;
        } else if(action==Action.LIQUIDATE) {
            require(status==Status.MARGIN_CALL,"LIQUIDATE_STATE");
            require(now.compareTo(deadline)>=0&&r.observedAt().compareTo(deadline)>=0&&r.observedAt().compareTo(now)<=0,"GRACE");
            require(risk,"LIQUIDATE_RISK");status=Status.LIQUIDATED;
        } else if(action==Action.CLOSE) status=Status.CLOSED;
        Position after=new Position(account,side,leverage,quantity,entry,collateral,status,add(old.version(),ONE),epoch,deadline,r.observedAt());
        boolean terminal=status==Status.CLOSED||status==Status.LIQUIDATED;
        OperationApplied applied=new OperationApplied(r.operationId(),r.positionId(),r.action(),after.version(),r.priceE8(),collateral,
            mark.unrealized(),terminal?mark.realized():ZERO,terminal?mark.badDebt():ZERO,epoch,deadline);
        return new Result(after,terminal?ZERO_HASH:r.positionId(),mark,applied,
            new FuturesReportConsumed(r.operationId(),r.observedAt(),r.validUntil()),true);
    }

    /** Full-field comparison; broader receipt order/count/emitter checks belong to a later wrapper. */
    public Result verifyEvents(Input input,OperationApplied operation,FuturesReportConsumed consumed) {
        Result expected=verify(input);
        require(expected.operationApplied().equals(operation),"OPERATION_EVENT");
        require(expected.reportConsumed().equals(consumed),"CONSUMED_EVENT");return expected;
    }

    private void validatePrestate(Position p,String active,String id) {
        require(p.status()!=null,"PRESTATE_STATUS");
        uint(p.side());uint(p.leverage());uint(p.quantity());uint(p.entryPriceE8());uint(p.collateral());
        uint(p.version());uint(p.callEpoch());uint(p.deadline());uint(p.lastObservedAt());
        if(p.status()==Status.NONE) {require(p.equals(Position.empty()),"PRESTATE_NONE");return;}
        require(!p.accountRef().equals(ZERO_HASH)&&p.version().signum()>0&&p.side().compareTo(ONE)<=0,"PRESTATE");
        leverage(p.leverage());amount(p.quantity());amount(p.collateral());price(p.entryPriceE8());
        if(p.status()==Status.ACTIVE||p.status()==Status.MARGIN_CALL) {
            require(active.equals(id),"PRESTATE_ACTIVE_POINTER");
            require(p.status()!=Status.ACTIVE||p.deadline().signum()==0,"PRESTATE_DEADLINE");
            require(p.status()!=Status.MARGIN_CALL||(p.deadline().signum()>0&&p.callEpoch().signum()>0),"PRESTATE_DEADLINE");
        } else require(active.equals(ZERO_HASH),"PRESTATE_TERMINAL_POINTER");
    }
    private static int leverage(BigInteger value) {
        require(value.equals(BigInteger.TWO)||value.equals(BigInteger.valueOf(5))||value.equals(BigInteger.TEN),"LEVERAGE");return value.intValueExact();
    }
    private static void amount(BigInteger value) {require(value.signum()>0&&value.compareTo(FuturesMath.AMOUNT_LIMIT)<0,"AMOUNT");}
    private static void price(BigInteger value) {require(value.signum()>0&&value.compareTo(FuturesMath.PRICE_LIMIT)<0,"PRICE");}
    private static BigInteger uint(BigInteger value) {require(value!=null&&value.signum()>=0&&value.compareTo(UINT_MAX)<=0,"UINT256");return value;}
    private static void signed(BigInteger value) {require(value!=null&&value.compareTo(INT_MIN)>=0&&value.compareTo(INT_MAX)<=0,"INT256");}
    private static BigInteger add(BigInteger a,BigInteger b) {BigInteger value=a.add(b);require(value.compareTo(UINT_MAX)<=0,"OVERFLOW");return value;}
    private static void require(boolean condition,String code) {if(!condition)throw new Rejected(code);}
}
