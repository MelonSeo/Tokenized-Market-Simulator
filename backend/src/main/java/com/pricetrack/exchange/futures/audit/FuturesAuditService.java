package com.pricetrack.exchange.futures.audit;

import static com.pricetrack.exchange.futures.audit.FuturesAuditModels.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.reserve.*;
import com.pricetrack.exchange.reserve.ReserveModels.Check;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Dedicated bounded observation worker. Never submits/reconciles a transaction or repairs any ledger row. */
public final class FuturesAuditService implements AutoCloseable {
    public static final int MAX_RPC=512,MAX_TRANSACTIONS=50,MAX_BLOCKS=250;
    private static final String LIMITATION="DB-custodied simulated collateral; not ERC-20 escrow, ownership proof or a future payout guarantee. Checks shown up to128; full counts are separate.";
    private final FuturesAuditStore store;private final FuturesChainIdentity identity;private final FuturesRpcGateway rpc;
    private final String executionId,codeHash;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"futures-audit");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static class Bounded extends RuntimeException {}
    private static class Budget {int count;final long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);void use(int n){if(Thread.currentThread().isInterrupted()||System.nanoTime()>until||(count+=n)>MAX_RPC)throw new Bounded();}}
    public FuturesAuditService(FuturesAuditStore store,FuturesChainIdentity identity,FuturesRpcGateway rpc,String executionId,String codeHash){this.store=store;this.identity=identity;this.rpc=rpc;this.executionId=executionId;this.codeHash=codeHash;}
    private <T>T execute(Supplier<T> action){
        Future<T> task;try{task=worker.submit(action::get);}catch(RejectedExecutionException busy){throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"FUTURES_AUDIT_BUSY");}
        try{return task.get(40,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(TimeoutException e){throw unavailable();}catch(ExecutionException e){if(e.getCause()instanceof ResponseStatusException known)throw known;throw unavailable();}
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"FUTURES_AUDIT_UNAVAILABLE");}
    public Baseline baseline(long actor){return execute(()->{
        if(executionId.isBlank()||store.baseline().isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"FUTURES_BASELINE_NOT_CREATABLE");
        try {
            var budget=new Budget();budget.use(6);rpc.verifyDeployment(identity);var block=head(budget);var cut=store.cut();
            if(!cut.futures().empty()||cut.spot().futures().checks().stream().anyMatch(c->!c.matches()))throw new ResponseStatusException(HttpStatus.CONFLICT,"FRESH_FUTURES_LEDGER_REQUIRED");
            // Provenance starts only if this engine has no pre-baseline execution, including failed calls.
            if(block.number()>=MAX_BLOCKS)throw new Bounded();
            budget.use(1);if(!rpc.logs(BigInteger.ZERO,BigInteger.valueOf(block.number())).isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"ENGINE_HISTORY_EXISTS");
            for(long n=0;n<=block.number();n++){budget.use(1);if(!rpc.engineTransactions(BigInteger.valueOf(n)).isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"ENGINE_HISTORY_EXISTS");}
            if(!stable(cut,block,budget))throw new ResponseStatusException(HttpStatus.CONFLICT,"FUTURES_BASELINE_CUT_CHANGED");
            return store.create(new Baseline(UUID.randomUUID().toString(),executionId,identity,codeHash,FuturesLedger.POLICY_HASH,cut.futures().initial().toString(),block,cut.spot().at(),store.fingerprint(cut),actor));
        }catch(ResponseStatusException known){throw known;}catch(Exception unavailable){throw unavailable();}
    });}
    public Result run(long actor){return execute(()->observe(actor));}
    public List<Result> history(){return store.history();}
    public Result detail(String id){try{UUID.fromString(id);}catch(Exception invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_AUDIT_ID");}return store.result(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"FUTURES_AUDIT_NOT_FOUND"));}
    private Block head(Budget b)throws java.io.IOException {b.use(2);var n=rpc.height();var block=rpc.block(n);return new Block(n.longValueExact(),block.getHash());}
    private boolean stable(FuturesAuditStore.Cut first,Block block,Budget b)throws java.io.IOException {
        var second=store.cut();b.use(1);return store.fingerprint(first).equals(store.fingerprint(second))&&head(b).equals(block)&&rpc.block(BigInteger.valueOf(block.number())).getHash().equalsIgnoreCase(block.hash());
    }
    private Result observe(long actor){
        var budget=new Budget();Baseline base=null;Block block=null;FuturesAuditStore.Cut cut=null;
        var checks=new ArrayList<Check>();var items=new ArrayList<Item>();int verified=0,scannedEvents=0,verifiedEvents=0;boolean complete=false;
        String status="INCONCLUSIVE",reason="BASELINE_REQUIRED";
        try {
            base=store.baseline().orElse(null);
            if(base==null)return save(actor,base,cut,block,checks,items,0,0,0,false,status,reason);
            budget.use(6);rpc.verifyDeployment(identity);block=head(budget);cut=store.cut();
            budget.use(1);boolean baselineOk=base.executionId().equals(executionId)&&base.chain().equals(identity)&&base.codeHash().equalsIgnoreCase(codeHash)&&base.policyHash().equals(cut.futures().policy())
                &&base.initialHouse().equals(cut.futures().initial().toString())&&rpc.block(BigInteger.valueOf(base.block().number())).getHash().equalsIgnoreCase(base.block().hash());
            if(!baselineOk)reason="BASELINE_ENVIRONMENT_CONFLICT";
            else if(cut.spot().orders().stream().anyMatch(o->o.mode().equals("UNKNOWN")))reason="SPOT_EXECUTION_UNVERIFIABLE";
            else if(cut.futures().unsettled()||cut.spot().orders().stream().anyMatch(o->Set.of("REQUESTED","PENDING_ONCHAIN").contains(o.status()))
                ||cut.spot().transactions().stream().anyMatch(t->!Set.of("CONFIRMED","FAILED").contains(t.status())))reason="UNSETTLED_OR_REVIEW";
            else {
                checks.addAll(ReserveCalculator.walletChecks(cut.spot()));
                if(cut.futures().transactions().size()>MAX_TRANSACTIONS||block.number()<base.block().number()||block.number()-base.block().number()>MAX_BLOCKS)throw new Bounded();
                var expectedEvents=new HashSet<String>();var knownTransactions=new HashSet<String>();boolean unavailable=false;
                for(var evidence:cut.futures().transactions()) {
                    var s=evidence.stored();knownTransactions.add(s.hash().toLowerCase(Locale.ROOT));
                    var op=cut.futures().operations().stream().filter(o->o.id().equals(s.operationId())).findFirst().orElse(null);
                    var p=op==null?null:cut.futures().positions().stream().filter(v->v.id().equals(op.positionId())).findFirst().orElse(null);
                    String verdict="MATCH",why="CANONICAL_RECEIPT_EVENT";
                    try {
                        if(op==null||p==null||!s.chainId().equals(identity.chainId())||!s.engine().equalsIgnoreCase(identity.engine())||!s.sender().equalsIgnoreCase(identity.executor())
                            ||!s.report().equals(FuturesReport.from(op,p,identity.executor()))||!FuturesReportCodec.signedBy(s.report(),identity.chainId(),identity.engine(),s.signature(),identity.priceSigner()))throw new FuturesLedger.Failure("AUDIT_LINK_CONFLICT");
                        FuturesTransactionStore.validateRaw(identity,s.report(),s.signature(),s.raw(),s.hash(),s.nonce());
                        budget.use(2);var receipt=rpc.receipt(s.hash()).orElseThrow(FuturesAuditSnapshot.Unavailable::new);var tx=rpc.transaction(s.hash()).orElseThrow(FuturesAuditSnapshot.Unavailable::new);
                        if(evidence.block()==null||evidence.block()>block.number()||!receipt.getBlockNumber().equals(BigInteger.valueOf(evidence.block())))throw new FuturesAuditSnapshot.Unavailable();
                        budget.use(1);var mined=rpc.block(receipt.getBlockNumber());
                        if(!mined.getHash().equalsIgnoreCase(receipt.getBlockHash()))throw new FuturesAuditSnapshot.Unavailable();
                        if(!Objects.equals(evidence.blockHash(),receipt.getBlockHash())||!s.status().equals(op.state()))throw new FuturesLedger.Failure("AUDIT_FINAL_STATE_CONFLICT");
                        FuturesReceiptVerifier.transaction(s,receipt,tx,mined);
                        if(op.state().equals("REVERTED")) {
                            if(!"0x0".equalsIgnoreCase(receipt.getStatus())||receipt.getLogs()==null||!receipt.getLogs().isEmpty())throw new FuturesLedger.Failure("AUDIT_REVERTED_CONFLICT");
                            why="CANONICAL_REVERTED_CAUSE_UNDETERMINED";
                        }else if(op.state().equals("CONFIRMED")) {
                            if(mined.getNumber().signum()==0)throw new FuturesAuditSnapshot.Unavailable();
                            budget.use(2);String prior=rpc.position(p.id(),mined.getNumber().subtract(BigInteger.ONE)),after=rpc.position(p.id(),mined.getNumber());
                            if(!FuturesAuditVerifier.words(after).get(7).equals(BigInteger.valueOf(op.expectedVersion()+1)))throw new FuturesAuditVerifier.HistoricalCutUnavailable();
                            var before=FuturesAuditVerifier.before(p,op,prior);FuturesReceiptVerifier.success(s,before,receipt,tx,mined,after);
                            receipt.getLogs().forEach(l->expectedEvents.add(eventKey(l)));
                        }else throw new FuturesAuditSnapshot.Unavailable();
                        verified++;
                    }catch(FuturesLedger.Failure conflict){verdict="MISMATCH";why="SIGNED_RECEIPT_EVENT_OR_DB_CONFLICT";}
                    catch(Bounded limit){throw limit;}catch(Exception unknown){verdict="INCONCLUSIVE";why="HISTORICAL_OR_RPC_EVIDENCE_UNAVAILABLE";unavailable=true;}
                    items.add(new Item(s.operationId(),op==null?null:op.positionId(),op==null?0:op.userId(),op==null?null:op.action().name(),op==null?null:op.state(),verdict,why,s.hash(),evidence.block(),evidence.blockHash()));
                }
                // Reverse scan both successful events and status0/no-event transactions, pinned to the same cut.
                budget.use(1);var logs=block.number()==base.block().number()?List.<org.web3j.protocol.core.methods.response.Log>of():rpc.logs(BigInteger.valueOf(base.block().number()+1),BigInteger.valueOf(block.number()));
                scannedEvents=logs.size();var actualEvents=new HashSet<String>();
                for(var log:logs){budget.use(1);if(log.isRemoved()||!rpc.block(log.getBlockNumber()).getHash().equalsIgnoreCase(log.getBlockHash()))throw new FuturesAuditSnapshot.Unavailable();actualEvents.add(eventKey(log));}
                verifiedEvents=(int)actualEvents.stream().filter(expectedEvents::contains).count();
                checks.add(check("FUTURES_EVENT_COVERAGE",actualEvents.equals(expectedEvents)));
                var chainTransactions=new HashSet<String>();
                for(long n=base.block().number()+1;n<=block.number();n++){budget.use(1);for(var tx:rpc.engineTransactions(BigInteger.valueOf(n)))chainTransactions.add(tx.getHash().toLowerCase(Locale.ROOT));}
                checks.add(check("FUTURES_TRANSACTION_COVERAGE",knownTransactions.equals(chainTransactions)));
                for(var p:cut.futures().positions()){budget.use(1);checks.add(check("FUTURES_CHAIN_POSITION_"+p.id(),FuturesAuditVerifier.current(p,rpc.position(p.id(),BigInteger.valueOf(block.number())))));}
                complete=!unavailable;
                // Never publish a mismatch from a moving cut or upgrade missing evidence to MATCH.
                if(!stable(cut,block,budget)){complete=false;reason="CUT_CHANGED";}
                else if(!complete)reason="PARTIAL_EVIDENCE";
                else {status=checks.stream().allMatch(Check::matches)&&items.stream().allMatch(i->i.verdict().equals("MATCH"))?"MATCH":"MISMATCH";reason=status.equals("MATCH")?"EXACT_LEDGER_CHAIN_MATCH":"LEDGER_CHAIN_OR_COVERAGE_CONFLICT";}
            }
        }catch(Bounded|FuturesAuditSnapshot.Limit|ReserveStore.CutLimitException bounded){reason="BOUNDED_READ_LIMIT";complete=false;}
        catch(Exception unavailable){reason="DEPENDENCY_OR_HISTORICAL_EVIDENCE_UNAVAILABLE";complete=false;}
        if(reason.equals("CUT_CHANGED")) {
            var provisional=new ArrayList<Item>();for(var i:items)provisional.add(new Item(i.operationId(),i.positionId(),i.userId(),i.action(),i.dbState(),"INCONCLUSIVE","CUT_CHANGED",i.txHash(),i.blockNumber(),i.blockHash()));items=provisional;
        }
        return save(actor,base,cut,block,checks,items,verified,scannedEvents,verifiedEvents,complete,status,reason);
    }
    private Result save(long actor,Baseline base,FuturesAuditStore.Cut cut,Block block,List<Check> checks,List<Item> items,int verified,int scannedEvents,int verifiedEvents,boolean complete,String status,String reason){
        var coverage=new Coverage(cut==null?0:cut.futures().operations().size(),cut==null?0:cut.futures().transactions().size(),verified,base==null?0:base.block().number()+1,block==null?0:block.number(),scannedEvents,verifiedEvents,complete);
        // Only safe DTOs enter append-only history, never Cut/Stored/report/signature.
        return store.save(new Result(UUID.randomUUID().toString(),status,reason,Instant.now(),actor,base,cut==null?null:cut.spot().at(),block,coverage,checks.size(),checks.stream().filter(c->!c.matches()).count(),
            checks.stream().sorted(Comparator.comparing(Check::matches)).limit(128).toList(),List.copyOf(items),LIMITATION));
    }
    private static Check check(String key,boolean ok){return new Check(key,"1",ok?"1":"0",ok?"0":"-1",ok);}
    private static String eventKey(org.web3j.protocol.core.methods.response.Log l){return org.web3j.crypto.Hash.sha3String(l.getAddress()+"|"+l.getBlockHash()+"|"+l.getTransactionHash()+"|"+l.getTransactionIndexRaw()+"|"+l.getLogIndexRaw()+"|"+l.getTopics()+"|"+l.getData());}
    @Override public void close(){worker.shutdownNow();}
}
