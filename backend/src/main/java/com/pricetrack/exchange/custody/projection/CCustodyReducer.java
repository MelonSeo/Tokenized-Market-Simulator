package com.pricetrack.exchange.custody.projection;

import static com.pricetrack.exchange.custody.model.CCustodyModels.*;
import com.pricetrack.exchange.custody.model.CCustodyModels;
import java.math.BigInteger;
import java.util.*;

/** Pure bounded Vault accounting replay. Requires separately verified receipt/calldata inputs.
 * Never reads RPC, proves signatures, selects canonical forks, declares MATCH or mutates financial systems. */
public final class CCustodyReducer {
    private static final BigInteger ONE=BigInteger.ONE;
    private static final BigInteger INT_MAX=ONE.shiftLeft(255).subtract(ONE), INT_MIN=ONE.shiftLeft(255).negate();
    private static final BigInteger COLLATERAL_LIMIT=BigInteger.TEN.pow(30);
    public record Limits(int transactions,int logsPerTransaction,int owners,int positions,int claims) {
        public Limits { if(transactions<1||logsPerTransaction<1||owners<1||positions<1||claims<1) throw new IllegalArgumentException("C_LIMIT"); }
    }
    public static final class Rejected extends IllegalArgumentException {
        public Rejected(String reason) { super(reason); }
    }
    private final Limits limits;
    public CCustodyReducer(Limits limits) { this.limits=Objects.requireNonNull(limits); }
    /** Validate a persisted accounting snapshot without applying a fabricated observation.
     * Does not elevate the snapshot to a canonical RPC or signature proof. */
    public void validateState(State state) { validate(Objects.requireNonNull(state)); }
    /** Empty Vault anchor is a caller-supplied bootstrap assertion, not a verified deployment baseline. */
    public State initial(Deployment deployment,Block anchor) {
        return new State(deployment,anchor,-1,Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),
            ZERO,ZERO,ZERO,ZERO,ZERO,ONE,ONE,false,Map.of());
    }
    /** Explicit empty observed block; does not assert that a canonical scanner actually found no relevant tx. */
    public State advanceEmptyBlock(State state,Block block) {
        validate(state); require(block.number()==state.cursor().number()+1&&block.parentHash().equals(state.cursor().hash()),"C_ANCESTRY");
        return copyCursor(state,block,-1);
    }
    private State copyCursor(State s,Block block,int index) {
        return new State(s.deployment(),block,index,s.balances(),s.positions(),s.claims(),s.nonces(),s.usedOperations(),
            s.totalFree(),s.totalLocked(),s.house(),s.actualAssets(),s.totalUnpaid(),s.head(),s.next(),s.paused(),s.observations());
    }
    public State apply(State state,Bundle bundle) {
        Objects.requireNonNull(bundle); validate(state);
        require(state.deployment().equals(bundle.deployment()),"C_DEPLOYMENT_MISMATCH");
        TxKey key=new TxKey(bundle.block().hash(),bundle.transactionHash());
        Bundle previous=state.observations().get(key);
        if(previous!=null) { require(previous.equals(bundle),"C_OBSERVATION_CONFLICT"); return state; }
        require(state.observations().values().stream().noneMatch(t->t.transactionHash().equals(bundle.transactionHash())),"C_TX_REINCLUDED");
        require(state.observations().size()<limits.transactions(),"C_TX_LIMIT");
        require(bundle.logs().size()<=limits.logsPerTransaction(),"C_LOG_LIMIT");
        order(state,bundle);
        Work w=new Work(state);
        if(bundle.outcome()==Outcome.REVERTED) require(bundle.logs().isEmpty(),"C_REVERTED_LOGS");
        else execute(w,bundle);
        w.observations.put(key,bundle);
        State result=w.freeze(bundle.block(),bundle.transactionIndex());
        validate(result); return result;
    }
    private void order(State s,Bundle b) {
        if(b.block().number()==s.cursor().number()) {
            require(b.block().equals(s.cursor())&&b.transactionIndex()>s.lastTransactionIndex(),"C_TX_ORDER");
        } else require(b.block().number()==s.cursor().number()+1&&b.block().parentHash().equals(s.cursor().hash()),"C_ANCESTRY");
        int last=-1;
        for(Log l:b.logs()) { require(l.index()>last,"C_LOG_ORDER"); last=l.index(); }
        if(!b.logs().isEmpty()) {
            int old=s.observations().values().stream().filter(t->t.block().hash().equals(b.block().hash()))
                .flatMap(t->t.logs().stream()).mapToInt(Log::index).max().orElse(-1);
            require(b.logs().getFirst().index()>old,"C_LOG_ORDER");
        }
    }
    private void execute(Work w,Bundle b) {
        Call c=b.call();
        if(c instanceof VaultCall v) vault(w,b,v);
        else if(c instanceof DonationCall d) {
            target(b,b.deployment().token()); positive(d.amount());
            require(!b.from().equals(b.deployment().vault()),"C_SELF_DONATION");
            expect(b,List.of(new Transfer(b.from(),b.deployment().vault(),d.amount())),List.of(b.deployment().token()));
            w.actual=w.actual.add(d.amount());
        } else if(c instanceof UserCall u) user(w,b,u);
        else if(c instanceof RiskCall r) risk(w,b,r);
        else if(c instanceof CancelCall n) {
            target(b,b.deployment().controller()); String owner=owner(n.owner()); require(b.from().equals(owner),"C_OWNER_CALL");
            require(w.nonce(owner).equals(uint(n.expectedNonce())),"C_NONCE");
            expect(b,List.of(new NonceCancelled(owner,n.expectedNonce())),List.of(b.deployment().controller()));
            w.nonces.put(owner,n.expectedNonce().add(ONE));
        } else if(c instanceof PauseCall p) {
            target(b,b.deployment().controller()); require(b.from().equals(b.deployment().pauseAdmin()),"C_ADMIN");
            expect(b,List.of(new PauseChanged(p.paused())),List.of(b.deployment().controller())); w.paused=p.paused();
        } else if(c instanceof ProcessClaimCall) process(w,b);
        else throw reject("C_UNSUPPORTED_CALL");
    }
    private void vault(Work w,Bundle b,VaultCall c) {
        target(b,b.deployment().vault()); String owner=owner(c.owner());
        require(!owner.equals(b.deployment().vault()),"C_VAULT_OWNER");
        require(b.from().equals(owner),"C_OWNER_CALL"); positive(c.amount());
        BigInteger amount=c.amount(); String token=b.deployment().token(), vault=b.deployment().vault();
        switch(c.action()) {
            case DEPOSIT -> {
                expect(b,List.of(new Transfer(owner,vault,amount),new Deposited(owner,amount)),List.of(token,vault));
                w.free(owner,amount); w.actual=w.actual.add(amount);
            }
            case FUND_HOUSE -> {
                expect(b,List.of(new Transfer(owner,vault,amount),new HouseFunded(owner,amount)),List.of(token,vault));
                w.house=w.house.add(amount); w.actual=w.actual.add(amount);
            }
            case WITHDRAW -> {
                // CMarginVault emits ERC20 Transfer during safeTransfer, then Withdrawn.
                expect(b,List.of(new Transfer(vault,owner,amount),new Withdrawn(owner,amount)),List.of(token,vault));
                w.free(owner,amount.negate()); w.actual=w.actual.subtract(amount);
            }
        }
    }
    private void user(Work w,Bundle b,UserCall c) {
        target(b,b.deployment().controller()); String owner=owner(c.owner());
        require(b.from().equals(c.direct()?owner:b.deployment().relayer()),"C_USER_CALLER");
        hash(c.intentDigest()); hash(c.reportDigest());
        require(w.nonce(owner).equals(uint(c.nonce())),"C_NONCE");
        String op=operation(w,c.operationId()), pos=hash(c.positionId());
        require(!pos.equals("0x"+"0".repeat(64)),"C_POSITION_ID");
        RuleApplied a=rulePrefix(b,op,pos,c.action().name(),c.expectedVersion(),c.collateralAfter());
        List<Event> expected=new ArrayList<>(); List<String> emitters=new ArrayList<>();
        expected.add(a); emitters.add(b.deployment().rules());
        expected.add(b.logs().get(1).event()); emitters.add(b.deployment().rules());
        Position prior=w.positions.get(pos); BigInteger after=collateral(c.collateralAfter());
        if(c.action()==UserAction.OPEN) {
            require(!w.paused&&w.unpaid.signum()==0,"C_OPEN_BLOCKED");
            require(prior==null&&uint(c.expectedVersion()).signum()==0&&uint(c.collateralBefore()).signum()==0,"C_OPEN_STATE");
            require(w.positions.values().stream().noneMatch(p->p.owner().equals(owner)&&active(p.status())),"C_ACTIVE_POSITION");
            require(a.callEpoch().signum()==0&&a.deadline().signum()==0,"C_OPEN_RULE_STATE");
            w.free(owner,after.negate()); w.locked(owner,after);
            expected.add(new Locked(op,pos,owner,after,after)); emitters.add(b.deployment().vault());
            w.positions.put(pos,new Position(owner,after,a.version(),ZERO,ZERO,Status.ACTIVE));
        } else {
            prior=prior(w,pos,owner,c.expectedVersion(),c.collateralBefore());
            if(c.action()==UserAction.TOP_UP) {
                BigInteger delta=after.subtract(prior.collateral()); positive(delta);
                w.free(owner,delta.negate()); w.locked(owner,delta);
                require(a.callEpoch().equals(prior.callEpoch()),"C_EPOCH");
                Status status=prior.status();
                if(status==Status.MARGIN_CALL&&a.deadline().signum()==0) status=Status.ACTIVE;
                else require(a.deadline().equals(prior.deadline()),"C_TOPUP_DEADLINE");
                expected.add(new Locked(op,pos,owner,delta,after)); emitters.add(b.deployment().vault());
                w.positions.put(pos,new Position(owner,after,a.version(),a.callEpoch(),a.deadline(),status));
            } else {
                require(after.equals(prior.collateral())&&a.callEpoch().equals(prior.callEpoch())&&a.deadline().equals(prior.deadline()),"C_CLOSE_STATE");
                settle(w,b,op,pos,owner,after,a,expected,emitters);
                w.positions.put(pos,new Position(owner,ZERO,a.version(),a.callEpoch(),a.deadline(),Status.CLOSED));
            }
        }
        if(c.action()!=UserAction.CLOSE) require(a.realized().signum()==0&&a.badDebt().signum()==0,"C_NONTERMINAL_PNL");
        expected.add(new UserExecuted(op,pos,owner,c.intentDigest(),c.reportDigest(),c.action().name(),c.nonce(),c.direct()));
        emitters.add(b.deployment().controller()); expect(b,expected,emitters);
        w.nonces.put(owner,c.nonce().add(ONE)); w.operations.put(op,pos);
    }
    private void risk(Work w,Bundle b,RiskCall c) {
        target(b,b.deployment().controller()); require(b.from().equals(b.deployment().relayer()),"C_KEEPER");
        String owner=owner(c.owner()),op=operation(w,c.operationId()),pos=hash(c.positionId()); hash(c.reportDigest());
        Position prior=prior(w,pos,owner,c.expectedVersion(),c.collateral());
        RuleApplied a=rulePrefix(b,op,pos,c.action().name(),c.expectedVersion(),c.collateral());
        List<Event> expected=new ArrayList<>(List.of(a,b.logs().get(1).event()));
        List<String> emitters=new ArrayList<>(List.of(b.deployment().rules(),b.deployment().rules()));
        Status status;
        switch(c.action()) {
            case START_CALL -> {
                require(prior.status()==Status.ACTIVE&&a.callEpoch().equals(prior.callEpoch().add(ONE))&&a.deadline().signum()>0,"C_START_STATE"); status=Status.MARGIN_CALL;
            }
            case CLEAR_CALL -> {
                require(prior.status()==Status.MARGIN_CALL&&a.callEpoch().equals(prior.callEpoch())&&a.deadline().signum()==0,"C_CLEAR_STATE"); status=Status.ACTIVE;
            }
            case LIQUIDATE -> {
                require(prior.status()==Status.MARGIN_CALL&&a.callEpoch().equals(prior.callEpoch())&&a.deadline().equals(prior.deadline()),"C_LIQUIDATE_STATE");
                settle(w,b,op,pos,owner,c.collateral(),a,expected,emitters); status=Status.LIQUIDATED;
            }
            default -> throw reject("C_RISK_ACTION");
        }
        if(c.action()!=RiskAction.LIQUIDATE) require(a.realized().signum()==0&&a.badDebt().signum()==0,"C_NONTERMINAL_PNL");
        BigInteger locked=status==Status.LIQUIDATED?ZERO:c.collateral();
        w.positions.put(pos,new Position(owner,locked,a.version(),a.callEpoch(),a.deadline(),status));
        expected.add(new RiskExecuted(op,pos,owner,c.reportDigest(),c.action().name())); emitters.add(b.deployment().controller());
        expect(b,expected,emitters); w.operations.put(op,pos);
    }
    private void settle(Work w,Bundle b,String op,String pos,String owner,BigInteger collateral,
            RuleApplied a,List<Event> expected,List<String> emitters) {
        BigInteger pnl=signed(a.realized()),badDebt=uint(a.badDebt());
        require(pnl.compareTo(collateral.negate())>=0&&(badDebt.signum()==0||pnl.equals(collateral.negate())),"C_SETTLEMENT");
        w.locked(owner,collateral.negate()); BigInteger paid=ZERO,id=ZERO;
        if(pnl.signum()<=0) { w.free(owner,collateral.add(pnl)); w.house=w.house.subtract(pnl); }
        else {
            w.free(owner,collateral); paid=w.unpaid.signum()==0?pnl.min(w.house):ZERO;
            w.house=w.house.subtract(paid); w.free(owner,paid);
            BigInteger remaining=pnl.subtract(paid);
            if(remaining.signum()>0) {
                id=w.next; w.next=w.next.add(ONE);
                w.claims.put(id,new Claim(owner,op,pos,remaining,ZERO,remaining)); w.unpaid=w.unpaid.add(remaining);
                expected.add(new ClaimCreated(id,owner,pos,op,remaining)); emitters.add(b.deployment().vault());
            }
        }
        expected.add(new PositionSettled(op,pos,owner,collateral,pnl,badDebt,paid,id)); emitters.add(b.deployment().vault());
    }
    private void process(Work w,Bundle b) {
        target(b,b.deployment().vault());
        if(w.unpaid.signum()==0||w.house.signum()==0) { expect(b,List.of(),List.of()); return; }
        Claim head=w.claims.get(w.head); require(head!=null&&head.remaining().signum()>0,"C_HEAD");
        BigInteger paid=head.remaining().min(w.house),remaining=head.remaining().subtract(paid);
        expect(b,List.of(new ClaimPaid(w.head,head.owner(),paid,remaining)),List.of(b.deployment().vault()));
        w.claims.put(w.head,new Claim(head.owner(),head.operationId(),head.positionId(),head.original(),head.paid().add(paid),remaining));
        w.free(head.owner(),paid); w.house=w.house.subtract(paid); w.unpaid=w.unpaid.subtract(paid);
        if(remaining.signum()==0) w.head=w.head.add(ONE);
    }
    private RuleApplied rulePrefix(Bundle b,String op,String pos,String action,BigInteger version,BigInteger collateral) {
        require(b.logs().size()>=2&&b.logs().get(0).event() instanceof RuleApplied&&b.logs().get(1).event() instanceof ReportConsumed,"C_RULE_LOGS");
        RuleApplied a=(RuleApplied)b.logs().get(0).event(); ReportConsumed r=(ReportConsumed)b.logs().get(1).event();
        require(a.operationId().equals(op)&&a.positionId().equals(pos)&&a.action().equals(action)
            &&a.version().equals(uint(version).add(ONE))&&a.collateral().equals(collateral)
            &&r.operationId().equals(op),"C_RULE_LINK");
        uint(a.version()); uint(a.callEpoch()); uint(a.deadline()); uint(r.observedAt()); uint(r.validUntil());
        signed(a.realized()); uint(a.badDebt()); return a;
    }
    private Position prior(Work w,String pos,String owner,BigInteger version,BigInteger collateral) {
        Position p=w.positions.get(pos);
        require(p!=null&&p.owner().equals(owner)&&active(p.status())&&p.version().equals(uint(version))
            &&p.collateral().equals(collateral),"C_POSITION_STATE"); return p;
    }
    private String operation(Work w,String op) {
        op=hash(op); require(!op.equals("0x"+"0".repeat(64))&&!w.operations.containsKey(op),"C_OPERATION"); return op;
    }
    private void expect(Bundle b,List<Event> expected,List<String> emitters) {
        require(b.logs().size()==expected.size(),"C_EVENT_COUNT");
        for(int n=0;n<expected.size();n++) require(b.logs().get(n).emitter().equals(emitters.get(n))&&b.logs().get(n).event().equals(expected.get(n)),"C_EVENT_MISMATCH");
    }
    private void target(Bundle b,String target) { require(b.to().equals(target),"C_TARGET"); }
    private static boolean active(Status status) { return status==Status.ACTIVE||status==Status.MARGIN_CALL; }
    private static String owner(String owner) {
        String normalized=CCustodyModels.address(owner);
        require(normalized.equals(owner),"C_NONCANONICAL_ADDRESS"); return normalized;
    }
    private static BigInteger uint(BigInteger n) { require(n!=null&&n.signum()>=0&&n.compareTo(UINT_MAX)<=0,"C_UINT256"); return n; }
    private static BigInteger positive(BigInteger n) { uint(n); require(n.signum()>0,"C_POSITIVE"); return n; }
    private static BigInteger collateral(BigInteger n) { positive(n); require(n.compareTo(COLLATERAL_LIMIT)<0,"C_COLLATERAL"); return n; }
    private static BigInteger signed(BigInteger n) { require(n!=null&&n.compareTo(INT_MIN)>=0&&n.compareTo(INT_MAX)<=0,"C_INT256"); return n; }
    private static void require(boolean valid,String reason) { if(!valid) throw reject(reason); }
    private static Rejected reject(String reason) { return new Rejected(reason); }

    /** Entire working copy is discarded if any expected event or invariant fails. */
    private static final class Work {
        final Deployment deployment;
        final Map<String,Balance> balances; final Map<String,Position> positions; final Map<BigInteger,Claim> claims;
        final Map<String,BigInteger> nonces; final Map<String,String> operations; final Map<TxKey,Bundle> observations;
        BigInteger free,locked,house,actual,unpaid,head,next; boolean paused;
        Work(State s) {
            deployment=s.deployment(); balances=new HashMap<>(s.balances()); positions=new HashMap<>(s.positions());
            claims=new HashMap<>(s.claims()); nonces=new HashMap<>(s.nonces()); operations=new HashMap<>(s.usedOperations());
            observations=new HashMap<>(s.observations()); free=s.totalFree(); locked=s.totalLocked(); house=s.house();
            actual=s.actualAssets(); unpaid=s.totalUnpaid(); head=s.head(); next=s.next(); paused=s.paused();
        }
        BigInteger nonce(String owner) { return nonces.getOrDefault(owner,ZERO); }
        void free(String owner,BigInteger delta) {
            Balance b=balances.getOrDefault(owner,new Balance(ZERO,ZERO)); BigInteger n=b.free().add(delta); uint(n);
            balances.put(owner,new Balance(n,b.locked())); free=free.add(delta);
        }
        void locked(String owner,BigInteger delta) {
            Balance b=balances.getOrDefault(owner,new Balance(ZERO,ZERO)); BigInteger n=b.locked().add(delta); uint(n);
            balances.put(owner,new Balance(b.free(),n)); locked=locked.add(delta);
        }
        State freeze(Block block,int index) { return new State(deployment,block,index,balances,positions,claims,nonces,operations,
            free,locked,house,actual,unpaid,head,next,paused,observations); }
    }
    private void validate(State s) {
        require(s!=null&&s.deployment()!=null&&s.cursor()!=null,"C_STATE");
        require(s.lastTransactionIndex()>=-1,"C_CURSOR_INDEX");
        require(s.balances().size()<=limits.owners()&&s.nonces().size()<=limits.owners()
            &&s.usedOperations().size()<=limits.transactions()&&s.positions().size()<=limits.positions()
            &&s.claims().size()<=limits.claims()&&s.observations().size()<=limits.transactions(),"C_STATE_LIMIT");
        BigInteger free=ZERO,locked=ZERO,unpaid=ZERO;
        Map<String,BigInteger> locks=new HashMap<>(); Set<String> activeOwners=new HashSet<>();
        for(var e:s.balances().entrySet()) { owner(e.getKey()); require(!e.getKey().equals(s.deployment().vault()),"C_VAULT_OWNER"); free=free.add(uint(e.getValue().free())); locked=locked.add(uint(e.getValue().locked())); }
        for(var e:s.positions().entrySet()) {
            require(hash(e.getKey()).equals(e.getKey()),"C_NONCANONICAL_HASH"); Position p=e.getValue(); owner(p.owner()); uint(p.version()); uint(p.callEpoch()); uint(p.deadline());
            require(p.status()!=null&&p.version().signum()>0,"C_POSITION_STATUS");
            if(active(p.status())) {
                require(activeOwners.add(p.owner()),"C_ACTIVE_POSITION");
                require(p.status()!=Status.ACTIVE||p.deadline().signum()==0,"C_ACTIVE_DEADLINE");
                require(p.status()!=Status.MARGIN_CALL||p.deadline().signum()>0,"C_MARGIN_DEADLINE");
                collateral(p.collateral()); locks.merge(p.owner(),p.collateral(),BigInteger::add);
            }
            else require(p.collateral().signum()==0,"C_TERMINAL_LOCK");
        }
        for(var e:s.balances().entrySet()) require(e.getValue().locked().equals(locks.getOrDefault(e.getKey(),ZERO)),"C_OWNER_LOCK_SUM");
        for(var e:locks.entrySet()) require(s.balances().containsKey(e.getKey()),"C_OWNER_MISSING");
        require(s.next().equals(BigInteger.valueOf(s.claims().size()).add(ONE)),"C_CLAIM_SEQUENCE");
        BigInteger first=s.next();
        for(BigInteger id=ONE;id.compareTo(s.next())<0;id=id.add(ONE)) {
            Claim c=s.claims().get(id); require(c!=null,"C_CLAIM_GAP"); owner(c.owner()); hash(c.operationId()); hash(c.positionId());
            require(positive(c.original()).equals(uint(c.paid()).add(uint(c.remaining()))),"C_CLAIM_SUM");
            Position p=s.positions().get(c.positionId());
            require(p!=null&&!active(p.status())&&p.owner().equals(c.owner())
                &&c.positionId().equals(s.usedOperations().get(c.operationId())),"C_CLAIM_POSITION");
            if(!first.equals(s.next())) require(c.paid().signum()==0,"C_FIFO_SEED");
            if(c.remaining().signum()>0&&first.equals(s.next())) first=id; unpaid=unpaid.add(c.remaining());
        }
        require(uint(s.head()).equals(first)&&s.head().signum()>0,"C_HEAD");
        require(free.equals(uint(s.totalFree()))&&locked.equals(uint(s.totalLocked()))&&unpaid.equals(uint(s.totalUnpaid())),"C_TOTALS");
        require(uint(s.actualAssets()).compareTo(free.add(locked).add(uint(s.house())))>=0,"C_ASSET_DEFICIT");
        uint(s.recognizedAssets()); s.nonces().forEach((o,n)->{owner(o);uint(n);});
        s.usedOperations().forEach((op,pos)->{
            require(hash(op).equals(op)&&hash(pos).equals(pos),"C_NONCANONICAL_HASH");
            require(!op.equals("0x"+"0".repeat(64))&&s.positions().containsKey(pos),"C_OPERATION_SEED");
        });
        Set<String> txHashes=new HashSet<>();
        s.observations().forEach((key,b)->{
            require(key.equals(new TxKey(b.block().hash(),b.transactionHash()))&&b.deployment().equals(s.deployment())
                &&b.block().number()<=s.cursor().number()&&b.logs().size()<=limits.logsPerTransaction()
                &&txHashes.add(b.transactionHash()),"C_OBSERVATION_SEED");
        });
    }
}
