package com.pricetrack.exchange.futures.audit;

import com.pricetrack.exchange.futures.authorization.UserIntentCodec;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.reserve.*;
import com.pricetrack.exchange.reserve.ReserveModels.Check;
import static com.pricetrack.exchange.futures.audit.FuturesAuditModels.*;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.Log;

/** Explicit opt-in, read-only multi-deployment observation. Never calls settlement, recovery or transaction submission. */
public final class FuturesAuthorizedAuditObserver {
    public static final int MAX_RPC=512,MAX_BLOCKS=250,MAX_TRANSACTIONS=50;
    private static final String LIMITATION="PostgreSQL simulated collateral, not ERC-20 escrow or payout solvency. Cancellation coverage supports canonical direct EOA type0/type2 calls only.";
    private static final String CANCEL=Hash.sha3String("UserNonceCancelledV2(address,uint256)");
    private static final String CANCEL_SELECTOR=Hash.sha3String("cancelNonce(uint256)").substring(0,10);
    public record Context(FuturesAuditDeployment target,FuturesRpcGateway rpc){
        public Context{Objects.requireNonNull(target);Objects.requireNonNull(rpc);}
    }
    public record CancellationCoverage(int observedTransactions,int verifiedSuccess,int verifiedFailed,int nonceGroups,boolean complete) {}
    public record TargetResult(String targetKey,String route,Baseline baseline,Block block,String status,String reason,
            Coverage coverage,List<Item> items,CancellationCoverage cancellationCoverage){
        public TargetResult{items=List.copyOf(items);Objects.requireNonNull(cancellationCoverage);}
        public TargetResult(String key,String route,Baseline baseline,Block block,String status,String reason,Coverage coverage,List<Item> items){
            this(key,route,baseline,block,status,reason,coverage,items,new CancellationCoverage(0,0,0,0,coverage.complete()));
        }
    }
    public record Observation(String status,String reason,Instant observedAt,long actorId,Instant dbAt,boolean complete,
            long checkCount,long mismatchCount,List<Check> checks,List<TargetResult> targets,String limitation){
        public Observation{checks=List.copyOf(checks);targets=List.copyOf(targets);}
    }
    private static final class Bounded extends RuntimeException{}
    private static final class Budget{
        int calls;final long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        final Set<String> transactions=new HashSet<>();
        void use(int count){FuturesAuditCancellation.check();if(Thread.currentThread().isInterrupted()||System.nanoTime()>until||(calls+=count)>MAX_RPC)throw new Bounded();}
        /** Whole-observation union: database, receipts and reverse evidence never multiply one tx. */
        void transaction(BigInteger chainId,String hash){
            use(0);
            if(chainId==null||chainId.signum()<=0||chainId.bitLength()>256||hash==null||!hash.matches("0x[0-9a-fA-F]{64}"))throw new FuturesAuditSnapshot.Unavailable();
            transactions.add(chainId+":"+lower(hash));
            if(transactions.size()>MAX_TRANSACTIONS)throw new Bounded();
        }
    }
    private final FuturesAuditStore store;private final FuturesAuditDeploymentStore deployments;
    private final List<Context> contexts;private final FuturesAuditDeployment.Registry registry;
    public FuturesAuthorizedAuditObserver(FuturesAuditStore store,FuturesAuditDeploymentStore deployments,List<Context> contexts){
        this.store=Objects.requireNonNull(store);this.deployments=Objects.requireNonNull(deployments);this.contexts=List.copyOf(contexts);
        registry=new FuturesAuditDeployment.Registry(this.contexts.stream().map(Context::target).toList());
    }
    /** Fresh target only, never automatically adopts the old legacy baseline or rewrites a baseline. */
    public FuturesAuditDeployment.Envelope baseline(String targetKey,long actor){
        if(actor<=0)throw new IllegalArgumentException("AUDIT_ACTOR_REQUIRED");
        var ctx=contexts.stream().filter(c->c.target().key().equals(targetKey)).findFirst().orElseThrow(()->new IllegalArgumentException("AUDIT_TARGET_NOT_REGISTERED"));
        if(deployments.find(ctx.target()).isPresent())throw new IllegalStateException("AUDIT_BASELINE_ALREADY_FIXED");
        var budget=new Budget();
        try{
            budget.use(16);ctx.rpc().verifyDeployment(ctx.target().chain());Block block=head(ctx,budget);
            if(block.number()>=MAX_BLOCKS)throw new Bounded();
            verifyHashes(ctx,block,budget);var cut=store.authorizedCut();
            if(!ctx.target().policyHash().equals(cut.futures().policy()))throw new IllegalStateException("AUDIT_BASELINE_POLICY_CONFLICT");
            if(cut.futures().unsettled()||spotUnsettled(cut)||ReserveCalculator.walletChecks(cut.spot()).stream().anyMatch(c->!c.matches()))throw new IllegalStateException("FRESH_TARGET_LEDGER_REQUIRED");
            for(var p:cut.futures().positions())if(route(cut.futures(),p).map(c->c.target().key().equals(targetKey)).orElse(true))throw new IllegalStateException("FRESH_TARGET_LEDGER_REQUIRED");
            // Two-address reverse scan includes failed direct rules/controller calls and cancellation activity.
            budget.use(1);if(!ctx.rpc().auditLogs(BigInteger.ZERO,BigInteger.valueOf(block.number())).isEmpty())throw new IllegalStateException("AUDIT_TARGET_HISTORY_EXISTS");
            for(long n=0;n<=block.number();n++){budget.use(1);if(!ctx.rpc().auditTransactions(BigInteger.valueOf(n)).isEmpty())throw new IllegalStateException("AUDIT_TARGET_HISTORY_EXISTS");}
            if(!stable(cut,Map.of(ctx,block),budget))throw new IllegalStateException("AUDIT_BASELINE_CUT_CHANGED");
            budget.use(0); // stable RPC may have ignored interruption; never proceed to registration blindly.
            var t=ctx.target();var base=new Baseline(UUID.randomUUID().toString(),t.executionId(),t.chain(),t.rulesCodeHash(),t.policyHash(),cut.futures().initial().toString(),block,cut.spot().at(),store.fingerprint(cut),actor);
            budget.use(0);return deployments.register(new FuturesAuditDeployment.Envelope(t,base));
        }catch(IOException unavailable){throw new IllegalStateException("AUDIT_BASELINE_RPC_UNAVAILABLE");}
    }
    /** No persistent history write and no background thread: caller owns admission and bounded execution. */
    public Observation observe(long actor){
        if(actor<=0)throw new IllegalArgumentException("AUDIT_ACTOR_REQUIRED");
        var budget=new Budget();var checks=new ArrayList<Check>();var results=new ArrayList<TargetResult>();
        FuturesAuditStore.Cut cut=null;String reason="REGISTERED_TARGET_REQUIRED";boolean complete=false;
        try{
            if(contexts.isEmpty())return result(actor,null,checks,results,false,reason);
            cut=store.authorizedCut();var snapshot=cut.futures();
            if(snapshot.transactions().size()>MAX_TRANSACTIONS)throw new Bounded();
            // Seed before routing/approval checks: corrupt or unroutable DB evidence still consumes budget.
            for(var tx:snapshot.transactions()){
                if(tx==null||tx.stored()==null)throw new FuturesAuditSnapshot.Unavailable();
                budget.transaction(tx.stored().chainId(),tx.stored().hash());
            }
            if(snapshot.unsettled()||spotUnsettled(cut))return result(actor,cut,checks,results,false,"UNSETTLED_OR_REVIEW");
            checks.addAll(ReserveCalculator.walletChecks(cut.spot()));
            if(cut.spot().orders().stream().anyMatch(o->"UNKNOWN".equals(o.mode())))return result(actor,cut,checks,results,false,"SPOT_EXECUTION_UNVERIFIABLE");
            var routes=new HashMap<String,Context>();boolean unknown=false;
            for(var p:snapshot.positions())if(route(snapshot,p).isEmpty())unknown=true;
            for(var o:snapshot.operations()){
                var p=snapshot.positions().stream().filter(v->v.id().equals(o.positionId())).findFirst().orElse(null);
                var c=p==null?Optional.<Context>empty():route(snapshot,p);
                if(c.isEmpty())unknown=true;else routes.put(o.id(),c.get());
            }
            for(var tx:snapshot.transactions())if(!routes.containsKey(tx.stored().operationId()))unknown=true;
            var heads=new LinkedHashMap<Context,Block>();var nonceUses=new HashSet<String>();var intents=new HashSet<String>();
            for(var ctx:contexts){
                budget.use(16);ctx.rpc().verifyDeployment(ctx.target().chain());Block block=head(ctx,budget);heads.put(ctx,block);verifyHashes(ctx,block,budget);
                var envelope=deployments.find(ctx.target()).orElse(null);
                if(envelope==null){results.add(target(ctx,null,block,List.of(),0,0,0,false,"BASELINE_REQUIRED"));continue;}
                var base=envelope.baseline();budget.use(1);
                if(!envelope.deployment().equals(ctx.target())||!base.initialHouse().equals(snapshot.initial().toString())||!base.policyHash().equals(snapshot.policy())
                        ||!same(ctx.rpc().block(BigInteger.valueOf(base.block().number())).getHash(),base.block().hash())){
                    results.add(target(ctx,base,block,List.of(),0,0,0,false,"BASELINE_ENVIRONMENT_CONFLICT"));continue;
                }
                if(block.number()<base.block().number()||block.number()-base.block().number()>MAX_BLOCKS)throw new Bounded();
                var items=new ArrayList<Item>();var expectedEvents=new HashSet<String>();var knownTx=new HashSet<String>();
                var nonceTransitions=new ArrayList<FuturesAuditNonceFold.Transition>();
                int verified=0;boolean targetComplete=true;String targetReason="EXACT_LEDGER_CHAIN_MATCH";
                for(var o:snapshot.operations())if(ctx.equals(routes.get(o.id()))){
                    var p=snapshot.positions().stream().filter(v->v.id().equals(o.positionId())).findFirst().orElseThrow(FuturesAuditSnapshot.Unavailable::new);
                    var matching=snapshot.transactions().stream().filter(t->t.stored().operationId().equals(o.id())).toList();
                    String verdict="MATCH",why="CANONICAL_AUTHORIZATION_RECEIPT";FuturesAuditSnapshot.Tx evidence=matching.size()==1?matching.get(0):null;
                    try{
                        if(matching.size()>1)throw new FuturesLedger.Failure("AUDIT_DUPLICATE_TRANSACTION");
                        var s=evidence==null?null:evidence.stored();
                        var auth=FuturesAuthorizedAuditVerifier.verify(ctx.target().chain(),p,o,s,snapshot.authorizations().get(o.id()));
                        if(evidence==null){if(!o.state().equals("REJECTED"))throw new FuturesAuditSnapshot.Unavailable();why="UNSIGNED_REJECTED_NO_TRANSACTION";}
                        else{
                            budget.transaction(ctx.target().chain().chainId(),s.hash());knownTx.add(lower(s.hash()));budget.use(3);
                            var receipt=ctx.rpc().receipt(s.hash()).orElseThrow(FuturesAuditSnapshot.Unavailable::new);
                            var tx=ctx.rpc().transaction(s.hash()).orElseThrow(FuturesAuditSnapshot.Unavailable::new);
                            if(evidence.block()==null||evidence.block()<=base.block().number()||evidence.block()>block.number())throw new FuturesAuditSnapshot.Unavailable();
                            var mined=ctx.rpc().block(BigInteger.valueOf(evidence.block()));
                            if(!same(mined.getHash(),receipt.getBlockHash()))throw new FuturesAuditSnapshot.Unavailable();
                            if(!same(evidence.blockHash(),receipt.getBlockHash())||!receipt.getBlockNumber().equals(BigInteger.valueOf(evidence.block())))throw new FuturesLedger.Failure("AUDIT_BLOCK_LINK_CONFLICT");
                            FuturesReceiptVerifier.transaction(s,receipt,tx,mined);
                            if(o.state().equals("CONFIRMED")){
                                if(mined.getNumber().signum()==0)throw new FuturesAuditSnapshot.Unavailable();
                                budget.use(2);String prior=ctx.rpc().position(p.id(),mined.getNumber().subtract(BigInteger.ONE)),after=ctx.rpc().position(p.id(),mined.getNumber());
                                if(!FuturesAuditVerifier.words(after).get(7).equals(BigInteger.valueOf(o.expectedVersion()+1)))throw new FuturesAuditVerifier.HistoricalCutUnavailable();
                                FuturesReceiptVerifier.success(s,FuturesAuditVerifier.before(p,o,prior),receipt,tx,mined,after);
                                if(ctx.target().chain().authorized()){
                                    budget.use(1);if(!same(ctx.rpc().positionOwner(p.id(),mined.getNumber()),auth.owner()))throw new FuturesLedger.Failure("AUDIT_OWNER_CONFLICT");
                                    if(auth.intentHash()!=null){
                                        var approval=s.authorization();var nonce=approval.intentV2()==null?approval.approval().nonce():approval.intentV2().nonce();
                                        if(!nonceUses.add(ctx.target().key()+":"+lower(auth.owner())+":"+nonce)||!intents.add(ctx.target().key()+":"+auth.intentHash()))throw new FuturesLedger.Failure("AUDIT_APPROVAL_REPLAY");
                                    }
                                }
                                receipt.getLogs().forEach(l->expectedEvents.add(eventKey(l)));
                            }else if(o.state().equals("REVERTED")){
                                if(!"0x0".equalsIgnoreCase(receipt.getStatus())||receipt.getLogs()==null||!receipt.getLogs().isEmpty())throw new FuturesLedger.Failure("AUDIT_REVERT_CONFLICT");
                                why="CANONICAL_REVERTED_CAUSE_UNDETERMINED";
                            }else throw new FuturesAuditSnapshot.Unavailable();
                            if(ctx.target().chain().v2()&&s.authorization()!=null&&auth.owner()!=null){
                                var group=new FuturesAuditNonceFold.Group(ctx.target().chain().chainId(),ctx.target().chain().engine(),auth.owner());
                                boolean success=o.state().equals("CONFIRMED");
                                boolean user=s.authorization().intentV2()!=null;
                                var event=success?receipt.getLogs().getLast():null;
                                nonceTransitions.add(new FuturesAuditNonceFold.Transition(group,mined.getNumber().longValueExact(),tx.getTransactionIndex().longValueExact(),
                                        success?event.getLogIndex().longValueExact():null,mined.getHash(),s.hash(),user?FuturesAuditNonceFold.Kind.USER_INTENT:FuturesAuditNonceFold.Kind.AUTOMATIC,
                                        success?FuturesAuditNonceFold.Outcome.SUCCESS:FuturesAuditNonceFold.Outcome.FAILED,
                                        success&&user?s.authorization().intentV2().nonce():null,true));
                            }
                            verified++;
                        }
                    }catch(FuturesLedger.Failure conflict){verdict="MISMATCH";why="AUTHORIZATION_RECEIPT_OR_DB_CONFLICT";}
                    catch(Bounded bounded){throw bounded;}catch(Exception unavailable){verdict="INCONCLUSIVE";why="HISTORICAL_OR_RPC_EVIDENCE_UNAVAILABLE";targetComplete=false;}
                    items.add(new Item(o.id(),p.id(),p.userId(),o.action().name(),o.state(),verdict,why,evidence==null?null:evidence.stored().hash(),evidence==null?null:evidence.block(),evidence==null?null:evidence.blockHash()));
                }
                budget.use(1);var logs=block.number()==base.block().number()?List.<Log>of():ctx.rpc().auditLogs(BigInteger.valueOf(base.block().number()+1),BigInteger.valueOf(block.number()));
                var actualEvents=new HashSet<String>();var cancelTransactions=new HashSet<String>();var observedEvents=new HashSet<String>();
                for(var log:logs){
                    if(log.getTopics()==null||log.getTopics().isEmpty())throw new FuturesAuditSnapshot.Unavailable();
                    budget.transaction(ctx.target().chain().chainId(),log.getTransactionHash());
                    budget.use(1);if(log.isRemoved()||!same(ctx.rpc().block(log.getBlockNumber()).getHash(),log.getBlockHash()))throw new FuturesAuditSnapshot.Unavailable();
                    // Duplicate provider rows are ambiguous evidence, not a complete set after silent deduplication.
                    if(!observedEvents.add(logIdentity(log)))throw new FuturesAuditSnapshot.Unavailable();
                    if(ctx.target().chain().v2()&&same(log.getAddress(),ctx.target().chain().engine())&&log.getTopics().get(0).equalsIgnoreCase(CANCEL))cancelTransactions.add(lower(log.getTransactionHash()));
                    actualEvents.add(eventKey(log));
                }
                var verifiedCancellations=new HashSet<String>();
                var cancellationEventKeys=new HashSet<String>();
                int cancelSuccess=0,cancelFailed=0,nonceGroups=0;
                var actualTx=new HashSet<String>();var observedTransactions=new HashSet<String>();
                for(long n=base.block().number()+1;n<=block.number();n++){
                    budget.use(2);var number=BigInteger.valueOf(n);var canonical=ctx.rpc().block(number);
                    if(canonical==null||!number.equals(canonical.getNumber())||canonical.getHash()==null)throw new FuturesAuditSnapshot.Unavailable();
                    for(var tx:ctx.rpc().auditTransactions(number)){
                        if(!number.equals(tx.getBlockNumber())||!same(canonical.getHash(),tx.getBlockHash()))throw new FuturesAuditSnapshot.Unavailable();
                        budget.transaction(ctx.target().chain().chainId(),tx.getHash());
                        if(!observedTransactions.add(lower(tx.getHash())))throw new FuturesAuditSnapshot.Unavailable();
                        actualTx.add(lower(tx.getHash()));
                        boolean cancelCandidate=ctx.target().chain().v2()&&same(tx.getTo(),ctx.target().chain().engine())&&tx.getInput()!=null
                                &&tx.getInput().length()>=10&&tx.getInput().regionMatches(true,0,CANCEL_SELECTOR,0,10);
                        if(cancelCandidate){
                            cancelTransactions.add(lower(tx.getHash()));
                            budget.use(1);var receipt=ctx.rpc().receipt(tx.getHash()).orElse(null);
                            var verification=FuturesAuditNonceCancellationVerifier.verify(ctx.target(),tx,receipt,canonical);
                            if(verification.verdict()==FuturesAuditNonceCancellationVerifier.Verdict.INCONCLUSIVE){targetComplete=false;targetReason="CANCEL_EVIDENCE_UNAVAILABLE";}
                            else if(verification.verdict()==FuturesAuditNonceCancellationVerifier.Verdict.MISMATCH){verifiedCancellations.add(lower(tx.getHash()));checks.add(check(ctx.target().key()+":CANCEL:"+lower(tx.getHash()),false));}
                            else{
                                var cancellation=verification.cancellation();budget.use(1);
                                if(!ctx.rpc().eoa(cancellation.owner(),number)){targetComplete=false;targetReason="CANCEL_EOA_EVIDENCE_REQUIRED";continue;}
                                knownTx.add(lower(tx.getHash()));verifiedCancellations.add(lower(tx.getHash()));
                                if(cancellation.successful())cancelSuccess++;else cancelFailed++;
                                receipt.getLogs().forEach(l->{expectedEvents.add(eventKey(l));cancellationEventKeys.add(eventKey(l));});
                                var group=new FuturesAuditNonceFold.Group(cancellation.chainId(),cancellation.controller(),cancellation.owner());
                                nonceTransitions.add(new FuturesAuditNonceFold.Transition(group,n,cancellation.transactionIndex().longValueExact(),
                                        cancellation.successful()?cancellation.logIndex().longValueExact():null,cancellation.blockHash(),cancellation.transactionHash(),FuturesAuditNonceFold.Kind.CANCEL,
                                        cancellation.successful()?FuturesAuditNonceFold.Outcome.SUCCESS:FuturesAuditNonceFold.Outcome.FAILED,
                                        cancellation.successful()?cancellation.expectedNonce():null,true));
                            }
                        }
                    }
                }
                if(!verifiedCancellations.containsAll(cancelTransactions)){targetComplete=false;targetReason="CANCEL_COVERAGE_REQUIRED";}
                if(ctx.target().chain().v2()&&!nonceTransitions.isEmpty()&&actualEvents.equals(expectedEvents)&&actualTx.equals(knownTx)){
                    var windows=new ArrayList<FuturesAuditNonceFold.Window>();
                    for(var group:nonceTransitions.stream().map(FuturesAuditNonceFold.Transition::group).distinct().toList()){
                        budget.use(2);windows.add(new FuturesAuditNonceFold.Window(group,base.block().number(),block.number(),
                                ctx.rpc().userNonce(group.owner(),BigInteger.valueOf(base.block().number())),ctx.rpc().userNonce(group.owner(),BigInteger.valueOf(block.number()))));
                    }
                    nonceGroups=windows.size();
                    var folded=FuturesAuditNonceFold.fold(windows,nonceTransitions,targetComplete&&actualEvents.equals(expectedEvents)&&actualTx.equals(knownTx));
                    if(folded.verdict()==FuturesAuditNonceFold.Verdict.INCONCLUSIVE){targetComplete=false;if(targetReason.equals("EXACT_LEDGER_CHAIN_MATCH"))targetReason="NONCE_COVERAGE_REQUIRED";}
                    else checks.add(check(ctx.target().key()+":USER_NONCE_CONTINUITY",folded.verdict()==FuturesAuditNonceFold.Verdict.MATCH));
                }
                checks.add(check(ctx.target().key()+":EVENT_COVERAGE",actualEvents.equals(expectedEvents)));
                checks.add(check(ctx.target().key()+":TRANSACTION_COVERAGE",actualTx.equals(knownTx)));
                for(var p:snapshot.positions())if(route(snapshot,p).filter(ctx::equals).isPresent()){
                    budget.use(1);checks.add(check(ctx.target().key()+":POSITION:"+p.id(),FuturesAuditVerifier.current(p,ctx.rpc().position(p.id(),BigInteger.valueOf(block.number())))));
                    if(ctx.target().chain().authorized()){
                        budget.use(1);String owner=ctx.rpc().positionOwner(p.id(),BigInteger.valueOf(block.number()));
                        String expected=p.version()==0?"0x"+"0".repeat(40):snapshot.positionAuthorizations().get(p.id()).owner();
                        checks.add(check(ctx.target().key()+":OWNER:"+p.id(),same(owner,expected)));
                    }
                }
                if(!targetComplete&&targetReason.equals("EXACT_LEDGER_CHAIN_MATCH"))targetReason="PARTIAL_EVIDENCE";
                var observed=target(ctx,base,block,items,verified,logs.size(),(int)actualEvents.stream().filter(expectedEvents::contains).filter(e->!cancellationEventKeys.contains(e)).count(),targetComplete,targetReason);
                observed=new TargetResult(observed.targetKey(),observed.route(),observed.baseline(),observed.block(),observed.status(),observed.reason(),observed.coverage(),observed.items(),new CancellationCoverage(cancelTransactions.size(),cancelSuccess,cancelFailed,nonceGroups,targetComplete));
                if(targetComplete&&checks.stream().anyMatch(c->c.name().startsWith(ctx.target().key()+":")&&!c.matches()))observed=new TargetResult(observed.targetKey(),observed.route(),observed.baseline(),observed.block(),"MISMATCH","LEDGER_CHAIN_OR_COVERAGE_CONFLICT",observed.coverage(),observed.items(),observed.cancellationCoverage());
                results.add(observed);
            }
            if(!stable(cut,heads,budget)){reason="CUT_CHANGED";results=provisional(results,"CUT_CHANGED");}
            else if(unknown)reason="UNREGISTERED_OR_UNROUTABLE_DEPLOYMENT";
            else if(results.stream().anyMatch(r->!r.coverage().complete()))reason=results.stream().anyMatch(r->r.reason().equals("CANCEL_COVERAGE_REQUIRED"))?"CANCEL_COVERAGE_REQUIRED":"PARTIAL_TARGET_COVERAGE";
            else{complete=true;reason="EXACT_LEDGER_CHAIN_MATCH";}
        }catch(Bounded|FuturesAuditSnapshot.Limit|ReserveStore.CutLimitException bounded){reason="BOUNDED_READ_LIMIT";results=provisional(results,reason);}
        catch(Exception unavailable){reason="DEPENDENCY_OR_HISTORICAL_EVIDENCE_UNAVAILABLE";results=provisional(results,reason);}
        return result(actor,cut,checks,results,complete,reason);
    }
    private Optional<Context> route(FuturesAuditSnapshot s,Position p){
        var a=s.positionAuthorizations().get(p.id());
        var found=contexts.stream().filter(c->a==null?!c.target().chain().authorized():c.target().chain().equals(a.chain())).toList();
        return found.size()==1?Optional.of(found.get(0)):Optional.empty();
    }
    private static boolean spotUnsettled(FuturesAuditStore.Cut c){return c.spot().orders().stream().anyMatch(o->Set.of("REQUESTED","PENDING_ONCHAIN").contains(o.status()))||c.spot().transactions().stream().anyMatch(t->!Set.of("CONFIRMED","FAILED").contains(t.status()));}
    private Block head(Context ctx,Budget budget)throws IOException{budget.use(2);var n=ctx.rpc().height();var b=ctx.rpc().block(n);if(b==null||!n.equals(b.getNumber())||b.getHash()==null)throw new FuturesAuditSnapshot.Unavailable();return new Block(n.longValueExact(),b.getHash());}
    private void verifyHashes(Context ctx,Block block,Budget budget)throws IOException{
        budget.use(ctx.target().chain().authorized()?2:1);var hashes=ctx.rpc().deploymentCodeHashes(BigInteger.valueOf(block.number()));
        if(!same(hashes.get("controller"),ctx.target().controllerCodeHash())||!same(hashes.get("rules"),ctx.target().rulesCodeHash()))throw new FuturesAuditSnapshot.Unavailable();
    }
    private boolean stable(FuturesAuditStore.Cut cut,Map<Context,Block> heads,Budget budget)throws IOException{
        if(!store.fingerprint(cut).equals(store.fingerprint(store.authorizedCut())))return false;
        for(var entry:heads.entrySet()){budget.use(1);if(!head(entry.getKey(),budget).equals(entry.getValue())||!same(entry.getKey().rpc().block(BigInteger.valueOf(entry.getValue().number())).getHash(),entry.getValue().hash()))return false;}
        return true;
    }
    private static TargetResult target(Context ctx,Baseline base,Block block,List<Item> items,int verified,int scanned,int verifiedEvents,boolean complete,String reason){
        String status=!complete?"INCONCLUSIVE":items.stream().anyMatch(i->i.verdict().equals("MISMATCH"))?"MISMATCH":"MATCH";
        return new TargetResult(ctx.target().key(),ctx.target().route().name(),base,block,status,reason,new Coverage(items.size(),(int)items.stream().filter(i->i.txHash()!=null).count(),verified,base==null?0:base.block().number()+1,block==null?0:block.number(),scanned,verifiedEvents,complete),items);
    }
    private static ArrayList<TargetResult> provisional(List<TargetResult> source,String reason){
        var out=new ArrayList<TargetResult>();for(var r:source){var c=r.coverage();var x=r.cancellationCoverage();out.add(new TargetResult(r.targetKey(),r.route(),r.baseline(),r.block(),"INCONCLUSIVE",reason,new Coverage(c.operations(),c.transactions(),c.verifiedTransactions(),c.fromBlock(),c.toBlock(),c.scannedEvents(),c.verifiedEvents(),false),r.items().stream().map(i->new Item(i.operationId(),i.positionId(),i.userId(),i.action(),i.dbState(),"INCONCLUSIVE",reason,i.txHash(),i.blockNumber(),i.blockHash())).toList(),new CancellationCoverage(x.observedTransactions(),x.verifiedSuccess(),x.verifiedFailed(),x.nonceGroups(),false)));}return out;
    }
    private static Observation result(long actor,FuturesAuditStore.Cut cut,List<Check> checks,List<TargetResult> targets,boolean complete,String reason){
        long mismatches=checks.stream().filter(c->!c.matches()).count();boolean conflict=mismatches>0||targets.stream().anyMatch(t->t.status().equals("MISMATCH"));
        return new Observation(!complete?"INCONCLUSIVE":conflict?"MISMATCH":"MATCH",complete&&conflict?"LEDGER_CHAIN_OR_COVERAGE_CONFLICT":reason,Instant.now(),actor,cut==null?null:cut.spot().at(),complete,checks.size(),mismatches,checks.stream().sorted(Comparator.comparing(Check::matches)).limit(128).toList(),targets,LIMITATION);
    }
    private static Check check(String name,boolean ok){return new Check(name,"1",ok?"1":"0",ok?"0":"-1",ok);}
    private static boolean same(String a,String b){return a!=null&&b!=null&&a.equalsIgnoreCase(b);}
    private static String lower(String value){if(value==null)throw new FuturesAuditSnapshot.Unavailable();return value.toLowerCase(Locale.ROOT);}
    private static String eventKey(Log l){return Hash.sha3String(lower(l.getAddress())+"|"+lower(l.getBlockHash())+"|"+lower(l.getTransactionHash())+"|"+l.getTransactionIndexRaw()+"|"+l.getLogIndexRaw()+"|"+l.getTopics()+"|"+l.getData());}
    private static String logIdentity(Log l){return lower(l.getBlockHash())+"|"+lower(l.getTransactionHash())+"|"+l.getLogIndexRaw();}
}
