package com.pricetrack.exchange.futures.audit;

import java.math.BigInteger;
import java.util.*;

/**
 * Pure nonce continuity only. The caller must first authenticate canonical transaction/receipt/event,
 * ABI, signature, sender, owner, domain and historical nonce anchors, and establish full range coverage.
 * A MATCH here is NOT a canonical/authorization/financial audit verdict. No RPC or state mutation occurs.
 */
public final class FuturesAuditNonceFold {
    public static final int MAX_TRANSITIONS=50,MAX_GROUPS=50;
    private static final BigInteger MAX_UINT256=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    public enum Kind { USER_INTENT, CANCEL, AUTOMATIC }
    public enum Outcome { SUCCESS, FAILED }
    public enum Verdict { MATCH, MISMATCH, INCONCLUSIVE }
    public enum Reason {
        NONCE_SEQUENCE_MATCH,NONCE_SEQUENCE_CONFLICT,INPUT_REQUIRED,BOUNDED_INPUT_LIMIT,
        MALFORMED_WINDOW,NONCE_ANCHOR_REQUIRED,DUPLICATE_WINDOW,UNDECLARED_GROUP,
        OUTSIDE_WINDOW,TRANSITION_EVIDENCE_REQUIRED,MALFORMED_TRANSITION,UNSUPPORTED_KIND,
        DUPLICATE_EVIDENCE,CANONICAL_ORDER_CONFLICT,COVERAGE_REQUIRED,
        NONCE_REUSE,NONCE_GAP,NONCE_OVERFLOW,FINAL_NONCE_CONFLICT
    }
    public record Group(BigInteger chainId,String controller,String owner) {
        public Group {
            if(chainId==null||chainId.signum()<=0||chainId.bitLength()>256)throw new IllegalArgumentException("INVALID_NONCE_GROUP");
            controller=address(controller);owner=address(owner);
        }
    }
    /** baseline is exclusive, cut is inclusive; nonce values are historical state AFTER those blocks. */
    public record Window(Group group,long baselineBlock,long cutBlock,BigInteger baselineNonce,BigInteger cutNonce) {}
    /**
     * One already-validated logical action, not every receipt log. USER_INTENT/CANCEL success carries
     * its consumed nonce and event logIndex. AUTOMATIC success carries no user nonce. FAILED has no
     * event/logIndex/consumed nonce and contributes zero even if the EOA transaction nonce advanced.
     * evidenceComplete is a caller assertion, never cryptographic proof supplied by this class.
     */
    public record Transition(Group group,long block,long transactionIndex,Long logIndex,String blockHash,String txHash,
            Kind kind,Outcome outcome,BigInteger eventNonce,boolean evidenceComplete) {}
    public record GroupSummary(Group group,long baselineBlock,long cutBlock,BigInteger baselineNonce,BigInteger cutNonce,
            BigInteger calculatedNonce,int transitions,int foldedTransitions,int successfulIntents,int successfulCancellations,
            int nonAdvancing,Verdict verdict,Reason reason) {}
    /** Public read-only summary; deliberately has no raw transaction, signature or calldata fields. */
    public record Result(Verdict verdict,Reason reason,boolean nonceCoverageComplete,List<GroupSummary> groups) {
        public Result{groups=List.copyOf(groups);}
    }
    private FuturesAuditNonceFold(){}

    public static Result fold(List<Window> windows,List<Transition> transitions,boolean coverageComplete){
        if(windows==null||transitions==null||windows.isEmpty())return unknown(Reason.INPUT_REQUIRED);
        if(windows.size()>MAX_GROUPS||transitions.size()>MAX_TRANSITIONS)return unknown(Reason.BOUNDED_INPUT_LIMIT);
        // Snapshot caller collections; records/BigInteger/String fields are immutable.
        List<Window> anchors=new ArrayList<>(windows);List<Transition> actions=new ArrayList<>(transitions);
        var declared=new HashMap<Group,Window>();
        for(var w:anchors){
            if(w==null||w.group()==null||w.baselineBlock()<0||w.cutBlock()<w.baselineBlock())return unknown(Reason.MALFORMED_WINDOW);
            if(w.baselineNonce()==null||w.cutNonce()==null)return unknown(Reason.NONCE_ANCHOR_REQUIRED);
            if(!uint(w.baselineNonce())||!uint(w.cutNonce()))return unknown(Reason.MALFORMED_WINDOW);
            if(declared.putIfAbsent(w.group(),w)!=null)return unknown(Reason.DUPLICATE_WINDOW);
        }
        var identities=new HashSet<String>();var blockSlots=new HashMap<String,String>();
        var transactionSlots=new HashMap<String,String>();var transactionLocations=new HashMap<String,String>();
        var transactionOutcomes=new HashMap<String,Outcome>();
        var positionSlots=new HashSet<String>();var physicalLogs=new HashMap<String,String>();
        for(var t:actions){
            if(t==null||t.group()==null)return unknown(Reason.MALFORMED_TRANSITION);
            var window=declared.get(t.group());if(window==null)return unknown(Reason.UNDECLARED_GROUP);
            if(t.block()<=window.baselineBlock()||t.block()>window.cutBlock())return unknown(Reason.OUTSIDE_WINDOW);
            if(!t.evidenceComplete())return unknown(Reason.TRANSITION_EVIDENCE_REQUIRED);
            if(t.kind()==null)return unknown(Reason.UNSUPPORTED_KIND);
            if(t.outcome()==null||t.block()<0||t.transactionIndex()<0||!hash(t.blockHash())||!hash(t.txHash()))return unknown(Reason.MALFORMED_TRANSITION);
            boolean success=t.outcome()==Outcome.SUCCESS,advancing=success&&t.kind()!=Kind.AUTOMATIC;
            if(success?(t.logIndex()==null||t.logIndex()<0):t.logIndex()!=null)return unknown(Reason.MALFORMED_TRANSITION);
            if(advancing?(t.eventNonce()==null||!uint(t.eventNonce())):t.eventNonce()!=null)return unknown(Reason.MALFORMED_TRANSITION);
            String chain=t.group().chainId().toString(),block=lower(t.blockHash()),tx=lower(t.txHash());
            if(conflict(blockSlots,chain+":"+t.block(),block)
                    ||conflict(transactionSlots,chain+":"+block+":"+t.transactionIndex(),tx))return unknown(Reason.CANONICAL_ORDER_CONFLICT);
            String txKey=chain+":"+tx;
            if(conflict(transactionLocations,txKey,t.block()+":"+block+":"+t.transactionIndex()))return unknown(Reason.CANONICAL_ORDER_CONFLICT);
            var previousOutcome=transactionOutcomes.putIfAbsent(txKey,t.outcome());
            if(previousOutcome!=null&&previousOutcome!=t.outcome())return unknown(Reason.CANONICAL_ORDER_CONFLICT);
            String log=t.logIndex()==null?"NO_EVENT":t.logIndex().toString();
            if(!identities.add(chain+":"+tx+":"+log))return unknown(Reason.DUPLICATE_EVIDENCE);
            if(success&&conflict(physicalLogs,chain+":"+block+":"+log,tx+":"+t.transactionIndex()))return unknown(Reason.CANONICAL_ORDER_CONFLICT);
            // Also reject conflicting hashes at the same group/order coordinates; never silently deduplicate.
            if(!positionSlots.add(t.group()+":"+t.block()+":"+t.transactionIndex()+":"+log))return unknown(Reason.CANONICAL_ORDER_CONFLICT);
        }
        // EVM logIndex is block-global, not transaction-local. Across successful logical actions,
        // canonical transaction order must therefore also imply strictly increasing log indices.
        var orderedLogs=actions.stream().filter(t->t.outcome()==Outcome.SUCCESS).sorted(Comparator
                .comparing((Transition t)->t.group().chainId()).thenComparingLong(Transition::block)
                .thenComparingLong(Transition::transactionIndex).thenComparingLong(Transition::logIndex)).toList();
        Transition prior=null;
        for(var t:orderedLogs){
            if(prior!=null&&prior.group().chainId().equals(t.group().chainId())&&prior.block()==t.block()
                    &&prior.logIndex()>=t.logIndex())return unknown(Reason.CANONICAL_ORDER_CONFLICT);
            prior=t;
        }
        if(!coverageComplete)return unknown(Reason.COVERAGE_REQUIRED);
        anchors.sort(Comparator.comparing((Window w)->w.group().chainId()).thenComparing(w->w.group().controller()).thenComparing(w->w.group().owner()));
        var summaries=new ArrayList<GroupSummary>();
        for(var w:anchors){
            var sorted=actions.stream().filter(t->t.group().equals(w.group())).sorted(Comparator.comparingLong(Transition::block)
                    .thenComparingLong(Transition::transactionIndex).thenComparingLong(t->t.logIndex()==null?-1:t.logIndex())).toList();
            BigInteger current=w.baselineNonce();int folded=0,intents=0,cancellations=0,nonAdvancing=0;
            Reason reason=Reason.NONCE_SEQUENCE_MATCH;
            for(var t:sorted){
                folded++;
                if(t.outcome()==Outcome.FAILED||t.kind()==Kind.AUTOMATIC){nonAdvancing++;continue;}
                int order=t.eventNonce().compareTo(current);
                if(order!=0){reason=order<0?Reason.NONCE_REUSE:Reason.NONCE_GAP;break;}
                if(current.equals(MAX_UINT256)){reason=Reason.NONCE_OVERFLOW;break;}
                current=current.add(BigInteger.ONE);if(t.kind()==Kind.USER_INTENT)intents++;else cancellations++;
            }
            if(reason==Reason.NONCE_SEQUENCE_MATCH&&!current.equals(w.cutNonce()))reason=Reason.FINAL_NONCE_CONFLICT;
            summaries.add(new GroupSummary(w.group(),w.baselineBlock(),w.cutBlock(),w.baselineNonce(),w.cutNonce(),current,sorted.size(),folded,
                    intents,cancellations,nonAdvancing,reason==Reason.NONCE_SEQUENCE_MATCH?Verdict.MATCH:Verdict.MISMATCH,reason));
        }
        boolean matches=summaries.stream().allMatch(g->g.verdict()==Verdict.MATCH);
        return new Result(matches?Verdict.MATCH:Verdict.MISMATCH,matches?Reason.NONCE_SEQUENCE_MATCH:Reason.NONCE_SEQUENCE_CONFLICT,true,summaries);
    }
    private static boolean conflict(Map<String,String> values,String key,String value){var prior=values.putIfAbsent(key,value);return prior!=null&&!prior.equals(value);}
    private static Result unknown(Reason reason){return new Result(Verdict.INCONCLUSIVE,reason,false,List.of());}
    private static boolean uint(BigInteger n){return n!=null&&n.signum()>=0&&n.bitLength()<=256;}
    private static boolean hash(String s){return s!=null&&s.matches("0x[0-9a-fA-F]{64}");}
    private static String lower(String s){return s.toLowerCase(Locale.ROOT);}
    private static String address(String value){
        if(value==null||!value.matches("0x[0-9a-fA-F]{40}")||value.equals("0x"+"0".repeat(40)))throw new IllegalArgumentException("INVALID_NONCE_GROUP");
        return lower(value);
    }
}
