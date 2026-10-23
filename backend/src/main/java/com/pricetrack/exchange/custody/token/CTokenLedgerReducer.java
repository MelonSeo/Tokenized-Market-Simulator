package com.pricetrack.exchange.custody.token;

import static com.pricetrack.exchange.custody.token.CTokenLedgerModels.*;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.Transfer;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.Approval;
import java.math.BigInteger;
import java.util.*;

/** Immutable fixed-supply token-log replay. Never changes Vault/DB/chain state or claims real chain coverage. */
public final class CTokenLedgerReducer {
    private static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE,BEFORE=BigInteger.valueOf(-1);
    public record Limits(int transactions,int logsPerTransaction,int holders,int approvalPairs) {
        public Limits {
            if(transactions<1||transactions>2048||logsPerTransaction<1||logsPerTransaction>128
                ||holders<1||holders>4096||approvalPairs<1||approvalPairs>4096)throw new IllegalArgumentException("C_TOKEN_LIMITS");
        }
    }
    public static final class Rejected extends IllegalArgumentException {public Rejected(String code){super("C_TOKEN_"+code);}}
    private final Limits limits;
    public CTokenLedgerReducer(Limits limits) {this.limits=Objects.requireNonNull(limits);}
    public State initial(Deployment deployment,Block anchor) {
        require(deployment!=null&&anchor!=null,"INPUT");
        require(anchor.number().add(ONE).equals(deployment.deploymentBlock().number())
            &&anchor.hash().equals(deployment.deploymentBlock().parentHash()),"ANCHOR");
        if(anchor.number().signum()==0)require(anchor.hash().equals(deployment.genesisHash()),"GENESIS");
        return new State(deployment,anchor,BEFORE,false,ZERO,Map.of(),Map.of(),Map.of(),List.of(anchor));
    }
    public State apply(State state,Transaction tx) {
        validate(state);require(tx!=null&&state.deployment().equals(tx.deployment()),"IDENTITY");
        TxKey key=new TxKey(tx.block().hash(),tx.hash());Transaction old=state.observations().get(key);
        if(old!=null){require(old.equals(tx),"REPLAY_CONFLICT");return state;}
        require(state.observations().size()<limits.transactions(),"TRANSACTION_LIMIT");
        require(tx.logs().size()<=limits.logsPerTransaction(),"LOG_LIMIT");
        require(state.observations().values().stream().noneMatch(t->t.hash().equals(tx.hash())),"TRANSACTION_REINCLUDED");
        order(state,tx);boolean minted=state.minted();BigInteger supply=state.totalSupply();
        Map<String,BigInteger> balances=new HashMap<>(state.balances());
        Map<ApprovalKey,ApprovalObservation> approvals=new HashMap<>(state.lastObservedApprovals());
        if(!minted) {
            require(tx.block().equals(tx.deployment().deploymentBlock())&&tx.hash().equals(tx.deployment().deploymentTransactionHash())
                &&tx.index().equals(tx.deployment().deploymentTransactionIndex())&&tx.outcome()==Outcome.SUCCESS
                &&!tx.logs().isEmpty(),"FIRST_MINT_TRANSACTION");
        }
        if(tx.outcome()==Outcome.REVERTED)require(tx.logs().isEmpty(),"REVERTED_LOGS");
        for(Log log:tx.logs()) {
            require(log.emitter().equals(tx.deployment().token()),"EMITTER");
            if(log.event() instanceof Transfer transfer) {
                uint(transfer.value());String from=transfer.from(),to=transfer.to();
                require(!to.equals(ZERO_ADDRESS),"BURN_OR_ZERO_RECEIVER");
                if(from.equals(ZERO_ADDRESS)) {
                    require(!minted&&log==tx.logs().getFirst()&&log.index().equals(tx.deployment().mintLogIndex())
                        &&to.equals(tx.deployment().treasury())&&transfer.value().equals(FIXED_SUPPLY),"MINT");
                    minted=true;supply=FIXED_SUPPLY;balances.put(to,FIXED_SUPPLY);
                } else {
                    require(minted,"MINT_REQUIRED");BigInteger before=balances.getOrDefault(from,ZERO);
                    require(before.compareTo(transfer.value())>=0,"INSUFFICIENT_BALANCE");
                    // Self transfer still checks balance, then restores it exactly like OZ _update.
                    if(from.equals(to))balances.put(from,before);
                    else {
                        balances.put(from,before.subtract(transfer.value()));
                        balances.put(to,uint(balances.getOrDefault(to,ZERO).add(transfer.value())));
                    }
                }
            } else if(log.event() instanceof Approval approval) {
                require(minted,"MINT_REQUIRED");nonzero(approval.owner());nonzero(approval.spender());uint(approval.value());
                approvals.put(new ApprovalKey(approval.owner(),approval.spender()),
                    new ApprovalObservation(approval.value(),new LogKey(tx.block().hash(),tx.hash(),log.index())));
            } else throw rejected("UNSUPPORTED_EVENT");
        }
        require(minted,"MINT_REQUIRED");
        Map<TxKey,Transaction> observations=new HashMap<>(state.observations());observations.put(key,tx);
        List<Block> headers=new ArrayList<>(state.headers());
        if(!tx.block().equals(state.cursor()))headers.add(tx.block());
        State result=new State(state.deployment(),tx.block(),tx.index(),minted,supply,balances,approvals,observations,headers);
        validate(result);return result;
    }
    /** Caller asserts the observed block had no relevant token transactions. This does not prove scanner completeness. */
    public State advanceEmptyBlock(State state,Block next) {
        validate(state);require(next!=null&&next.number().equals(state.cursor().number().add(ONE))
            &&next.parentHash().equals(state.cursor().hash()),"ANCESTRY");
        require(state.minted(),"MINT_REQUIRED");
        List<Block> headers=new ArrayList<>(state.headers());headers.add(next);
        State result=new State(state.deployment(),next,BEFORE,true,state.totalSupply(),state.balances(),state.lastObservedApprovals(),state.observations(),headers);
        validate(result);return result;
    }
    private void order(State state,Transaction tx) {
        if(tx.block().number().equals(state.cursor().number()))require(tx.block().equals(state.cursor())&&tx.index().compareTo(state.lastTransactionIndex())>0,"TRANSACTION_ORDER");
        else require(tx.block().number().equals(state.cursor().number().add(ONE))&&tx.block().parentHash().equals(state.cursor().hash()),"ANCESTRY");
        BigInteger last=BEFORE;
        for(Log log:tx.logs()){require(log.index().compareTo(last)>0,"LOG_ORDER");last=log.index();}
        if(!tx.logs().isEmpty()) {
            BigInteger previous=state.observations().values().stream().filter(t->t.block().hash().equals(tx.block().hash()))
                .flatMap(t->t.logs().stream()).map(Log::index).max(BigInteger::compareTo).orElse(BEFORE);
            require(tx.logs().getFirst().index().compareTo(previous)>0,"LOG_ORDER");
        }
    }
    private void validate(State s) {
        require(s!=null&&s.deployment()!=null&&s.cursor()!=null&&s.lastTransactionIndex()!=null,"STATE");
        require(s.lastTransactionIndex().equals(BEFORE)||s.lastTransactionIndex().signum()>=0,"CURSOR");
        require(s.observations().size()<=limits.transactions()&&s.balances().size()<=limits.holders()
            &&s.lastObservedApprovals().size()<=limits.approvalPairs()&&!s.headers().isEmpty()&&s.headers().size()<=2048,"STATE_LIMIT");
        Map<BigInteger,Block> headers=new HashMap<>();Block previous=null;
        for(Block header:s.headers()) {
            require(header!=null,"HEADER");
            if(previous==null) {
                require(header.number().add(ONE).equals(s.deployment().deploymentBlock().number())
                    &&header.hash().equals(s.deployment().deploymentBlock().parentHash()),"HEADER_ANCHOR");
                if(header.number().signum()==0)require(header.hash().equals(s.deployment().genesisHash()),"GENESIS");
            } else require(header.number().equals(previous.number().add(ONE))&&header.parentHash().equals(previous.hash()),"HEADER_ANCESTRY");
            require(headers.put(header.number(),header)==null,"HEADER_DUPLICATE");previous=header;
        }
        require(s.cursor().equals(previous),"HEADER_CURSOR");
        BigInteger sum=ZERO;for(var balance:s.balances().entrySet()) {
            require(nonzero(balance.getKey()).equals(balance.getKey()),"ADDRESS_CANONICAL");sum=sum.add(uint(balance.getValue()));
        }
        uint(sum);require(sum.equals(uint(s.totalSupply()))&&s.totalSupply().equals(s.minted()?FIXED_SUPPLY:ZERO),"SUPPLY_SUM");
        if(!s.minted()) {
            require(s.balances().isEmpty()&&s.lastObservedApprovals().isEmpty()&&s.observations().isEmpty(),"UNMINTED_STATE");
            require(s.cursor().number().add(ONE).equals(s.deployment().deploymentBlock().number())
                &&s.cursor().hash().equals(s.deployment().deploymentBlock().parentHash())&&s.lastTransactionIndex().equals(BEFORE),"UNMINTED_CURSOR");
        }
        Set<String> hashes=new HashSet<>();Map<BigInteger,Block> blockIdentities=new HashMap<>();
        Map<ApprovalKey,ApprovalObservation> latest=new HashMap<>();
        Map<String,BigInteger> derivedBalances=new HashMap<>();
        int mints=0;Transaction deploymentTx=null;BigInteger lastAtCursor=BEFORE;
        List<Map.Entry<TxKey,Transaction>> ordered=new ArrayList<>(s.observations().entrySet());
        ordered.sort(Comparator.comparing((Map.Entry<TxKey,Transaction> e)->e.getValue().block().number()).thenComparing(e->e.getValue().index()));
        BigInteger priorLog=BEFORE,priorTx=BEFORE,priorBlock=BEFORE;
        for(var entry:ordered) {
            Transaction tx=entry.getValue();require(entry.getKey().equals(new TxKey(tx.block().hash(),tx.hash()))
                &&tx.deployment().equals(s.deployment())&&tx.block().number().compareTo(s.cursor().number())<=0
                &&tx.block().equals(headers.get(tx.block().number()))&&hashes.add(tx.hash())&&tx.logs().size()<=limits.logsPerTransaction(),"OBSERVATION");
            require(tx.block().number().compareTo(s.deployment().deploymentBlock().number())>=0,"PREDEPLOYMENT_OBSERVATION");
            Block prior=blockIdentities.putIfAbsent(tx.block().number(),tx.block());require(prior==null||prior.equals(tx.block()),"BLOCK_VARIANT");
            if(tx.block().number().equals(s.cursor().number())){require(tx.block().equals(s.cursor()),"CURSOR_HASH");lastAtCursor=lastAtCursor.max(tx.index());}
            if(tx.block().number().equals(priorBlock))require(tx.index().compareTo(priorTx)>0,"TRANSACTION_ORDER");
            else {priorLog=BEFORE;priorBlock=tx.block().number();}
            priorTx=tx.index();
            if(tx.hash().equals(s.deployment().deploymentTransactionHash()))deploymentTx=tx;
            require(tx.outcome()!=Outcome.REVERTED||tx.logs().isEmpty(),"REVERTED_LOGS");
            BigInteger last=BEFORE;for(Log log:tx.logs()) {
                require(log.index().compareTo(last)>0&&log.index().compareTo(priorLog)>0&&log.emitter().equals(s.deployment().token()),"OBSERVATION_LOG");last=log.index();priorLog=log.index();
                if(log.event() instanceof Transfer t){
                    require(!t.to().equals(ZERO_ADDRESS),"BURN_OR_ZERO_RECEIVER");uint(t.value());
                    if(t.from().equals(ZERO_ADDRESS)) {
                        require(mints==0&&derivedBalances.isEmpty()&&tx.hash().equals(s.deployment().deploymentTransactionHash())
                            &&log==tx.logs().getFirst(),"MINT_PROVENANCE");
                        mints++;derivedBalances.put(t.to(),t.value());
                    } else {
                        require(mints==1,"MINT_REQUIRED");BigInteger before=derivedBalances.getOrDefault(t.from(),ZERO);
                        require(before.compareTo(t.value())>=0,"INSUFFICIENT_BALANCE");
                        if(t.from().equals(t.to()))derivedBalances.put(t.from(),before);
                        else {derivedBalances.put(t.from(),before.subtract(t.value()));
                            derivedBalances.put(t.to(),uint(derivedBalances.getOrDefault(t.to(),ZERO).add(t.value())));}
                    }
                }
                else if(log.event() instanceof Approval a){nonzero(a.owner());nonzero(a.spender());uint(a.value());
                    require(mints==1,"MINT_REQUIRED");
                    latest.put(new ApprovalKey(a.owner(),a.spender()),new ApprovalObservation(a.value(),new LogKey(tx.block().hash(),tx.hash(),log.index())));
                    require(latest.size()<=limits.approvalPairs(),"STATE_LIMIT");}
                else throw rejected("UNSUPPORTED_EVENT");
            }
        }
        require(lastAtCursor.equals(s.lastTransactionIndex()),"CURSOR_INDEX");
        require(derivedBalances.equals(s.balances()),"BALANCE_OBSERVATIONS");
        require(latest.equals(s.lastObservedApprovals()),"LATEST_APPROVAL_OBSERVATIONS");
        if(s.minted()) {
            require(mints==1&&deploymentTx!=null&&deploymentTx.outcome()==Outcome.SUCCESS&&deploymentTx.block().equals(s.deployment().deploymentBlock())
                &&deploymentTx.index().equals(s.deployment().deploymentTransactionIndex())&&!deploymentTx.logs().isEmpty(),"MINT_PROVENANCE");
            Log mint=deploymentTx.logs().getFirst();require(mint.index().equals(s.deployment().mintLogIndex())
                &&mint.event().equals(new Transfer(ZERO_ADDRESS,s.deployment().treasury(),FIXED_SUPPLY)),"MINT_PROVENANCE");
        }
        for(var entry:s.lastObservedApprovals().entrySet()) {
            ApprovalKey key=entry.getKey();nonzero(key.owner());nonzero(key.spender());ApprovalObservation observed=entry.getValue();uint(observed.observedValue());
            LogKey source=observed.source();require(source!=null,"APPROVAL_SOURCE");
            Transaction tx=s.observations().get(new TxKey(source.blockHash(),source.transactionHash()));
            require(tx!=null&&tx.logs().stream().anyMatch(l->l.index().equals(source.logIndex())
                &&l.event().equals(new Approval(key.owner(),key.spender(),observed.observedValue()))),"APPROVAL_SOURCE");
        }
    }
    private static BigInteger uint(BigInteger n) {try{return CAbiModels.uint(n);}catch(IllegalArgumentException invalid){throw rejected("UINT256");}}
    private static void require(boolean condition,String code) {if(!condition)throw rejected(code);}
    private static Rejected rejected(String code) {return new Rejected(code);}
}
