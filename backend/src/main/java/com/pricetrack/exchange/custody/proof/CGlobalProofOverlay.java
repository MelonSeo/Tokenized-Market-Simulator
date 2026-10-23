package com.pricetrack.exchange.custody.proof;

import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventModels;
import com.pricetrack.exchange.custody.model.CCustodyModels;
import com.pricetrack.exchange.custody.projection.CCustodyReducer;
import com.pricetrack.exchange.custody.token.CTokenLedgerModels;
import com.pricetrack.exchange.custody.token.CTokenLedgerReducer;
import java.math.BigInteger;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.CharBuffer;
import java.security.*;
import java.util.*;

/** Memory-only same-block transcript. Never exposes or persists an internally aligned Vault State. */
public final class CGlobalProofOverlay {
    public static final int MAX_STEPS=128,MAX_PENDING_TOKEN_GAP=64,MAX_RETAINED_BYTES=8*1024*1024;
    public enum Decision { VERIFIED_IN_SCOPE, INSUFFICIENT, UNSUPPORTED, REJECTED }
    public record Root(String sourceIdentity,String symbolHash,BigInteger blockTimestamp,CCustodyModels.State vault,CTokenLedgerModels.State token) {}
    public sealed interface Witness permits TokenWitness,TradingWitness,VaultWitness,ControlWitness {}
    public record TokenWitness(CTokenTransactionProofModels.AllowanceObservation allowance) implements Witness {}
    public record TradingWitness(CAuthorizationModels.Prestate prestate,CAuthorizationModels.CodeObservation ownerCode,
        CRulesTransitionModels.PrestateKeys rulesKeys,Boolean operationUsed,String activePosition) implements Witness {}
    public record VaultWitness(CVaultTransactionProofModels.AllowanceObservation allowance) implements Witness {}
    public record ControlWitness(CControllerControlProofModels.NonceObservation nonce,CAuthorizationModels.CodeObservation ownerCode) implements Witness {}
    public record Step(CReceiptEnvelopeModels.Input envelope,Witness witness) {
        @Override public String toString(){return "CGlobalOverlayStep[raw/witness redacted]";}
    }
    public record Point(BigInteger blockNumber,String blockHash,BigInteger transactionIndex) {}
    /** financialSteps/financialPoint mean C-domain observations, including control/noop/reverted;
     * they are NOT counts or points of successful financial executions. */
    public record Summary(String sourceIdentity,String rootHash,String transcriptHash,int steps,int tokenSteps,int financialSteps,
        int pendingTokenGap,Point globalPoint,Point financialPoint) {
        public boolean completeCoverage(){return false;}
        public boolean globalCoverage(){return false;}
        public boolean canonicalVerified(){return false;}
        public boolean persistentRestartVerified(){return false;}
        public String verificationScope(){return "MEMORY_ONLY_PREVALIDATED_SAME_BLOCK_OVERLAY; no canonical/scanner completeness, actual code/RPC, PG/storage/restart or full audit proof";}
    }
    /** API-opaque, deliberately no State/transcript/Verified getter, callback or serialization hook. */
    public static final class Checkpoint {
        private final Root root;private final String rootHash;private final List<Step> transcript;
        private Checkpoint(Root root,String rootHash,List<Step> transcript){this.root=root;this.rootHash=rootHash;this.transcript=List.copyOf(transcript);}
        @Override public String toString(){return "CGlobalProofCheckpoint[opaque, memory-only]";}
    }
    public record Result(Decision decision,String reason,Summary summary,Checkpoint checkpoint) {
        @Override public String toString(){return "CGlobalOverlayResult[decision="+decision+", reason="+reason+", opaque evidence]";}
    }
    private record Work(CCustodyModels.State financial,CTokenLedgerModels.State token,int pending,int tokenSteps,int financialSteps) {}
    private record AllowanceKey(String owner,String spender) {}
    private record AllowanceUse(AllowanceKey key,BigInteger observed,BigInteger amount) {}
    private static final class Stop extends RuntimeException {
        final Decision decision;final String code;
        Stop(Decision d,String c){super(c,null,false,false);decision=d;code=c;}
    }
    private final CReceiptEnvelopeVerifier envelopes=new CReceiptEnvelopeVerifier();
    private final CTokenTransactionProofVerifier tokenProof=new CTokenTransactionProofVerifier();
    private final CTransactionProofVerifier tradingProof=new CTransactionProofVerifier();
    private final CVaultTransactionProofVerifier vaultProof=new CVaultTransactionProofVerifier();
    private final CControllerControlProofVerifier controlProof=new CControllerControlProofVerifier();
    private final CCustodyReducer vaults=new CCustodyReducer(new CCustodyReducer.Limits(2048,128,4096,2048,2048));
    private final CTokenLedgerReducer tokens=new CTokenLedgerReducer(new CTokenLedgerReducer.Limits(2048,128,4096,4096));

    public Result verify(Root root,List<Step> transcript){return execute(root,null,transcript);}
    public Result append(Root root,Checkpoint checkpoint,Step next){
        if(checkpoint==null)return failure(Decision.INSUFFICIENT,"CHECKPOINT_MISSING");
        return execute(root,checkpoint,Collections.singletonList(next));
    }
    private Result execute(Root root,Checkpoint previous,List<Step> additions){
        try{
            need(root!=null&&root.sourceIdentity()!=null&&root.symbolHash()!=null&&root.blockTimestamp()!=null&&root.vault()!=null&&root.token()!=null,"ROOT");
            check(root.sourceIdentity().matches("[0-9a-f]{64}"),"SOURCE_IDENTITY");CAbiModels.hash(root.symbolHash());CAbiModels.uint(root.blockTimestamp());
            validateRoot(root); // Validate typed keys/replay/limits before canonical map-key allocation.
            byte[] rootBytes=canonical(root,MAX_RETAINED_BYTES);String rootHash=sha(rootBytes);
            if(previous!=null)check(previous.rootHash.equals(rootHash),"CHECKPOINT_ROOT_OR_SOURCE");
            need(additions!=null,"TRANSCRIPT");int oldCount=previous==null?0:previous.transcript.size();
            if(additions.size()>MAX_STEPS-oldCount)stop(Decision.INSUFFICIENT,"STEP_LIMIT");int count=oldCount+additions.size();
            int retained=rootBytes.length;List<Step> transcript=new ArrayList<>(count);
            if(previous!=null)for(Step step:previous.transcript){reserve(retained,step);Step copy=snapshot(step);retained=reserve(retained,copy);transcript.add(copy);}
            int additionCount=additions.size();
            if(additionCount>MAX_STEPS-oldCount)stop(Decision.INSUFFICIENT,"INPUT_CHANGED");
            for(int i=0;i<additionCount;i++){Step step=additions.get(i);reserve(retained,step);Step copy=snapshot(step);retained=reserve(retained,copy);transcript.add(copy);}
            if(additions.size()!=additionCount)stop(Decision.INSUFFICIENT,"INPUT_CHANGED");
            Work w=new Work(root.vault(),root.token(),0,0,0);Set<String> hashes=new HashSet<>();
            Map<String,CRulesTransitionModels.Position> knownRules=new HashMap<>();
            Map<AllowanceKey,BigInteger> knownAllowances=new HashMap<>();
            root.token().observations().values().forEach(t->hashes.add(t.hash()));
            var deployment=root.vault().deployment();
            BigInteger lastBlockLogIndex=rootBlockLogMaximum(root);
            for(Step step:transcript){
                var envelope=envelopes.verify(step.envelope());
                if(envelope.decision()!=CReceiptEnvelopeModels.Decision.LINKED_IN_SCOPE)stop(Decision.valueOf(envelope.decision().name()),"ENVELOPE_"+envelope.reason());
                var linked=envelope.linked();var receipt=linked.receipt();var ed=step.envelope().deployment();
                check(deployment.chainId().equals(ed.chainId())&&deployment.token().equals(CAbiModels.address(ed.token()))&&deployment.vault().equals(CAbiModels.address(ed.vault()))
                    &&deployment.controller().equals(CAbiModels.address(ed.controller()))&&deployment.rules().equals(CAbiModels.address(ed.rules())),"STEP_DEPLOYMENT");
                if(!receipt.blockNumber().equals(BigInteger.valueOf(root.vault().cursor().number()))||!receipt.blockHash().equals(root.vault().cursor().hash()))stop(Decision.UNSUPPORTED,"BLOCK_SCOPE");
                check(linked.block().timestamp().equals(root.blockTimestamp()),"SAME_BLOCK_TIMESTAMP");
                BigInteger expectedIndex=w.token().lastTransactionIndex().add(BigInteger.ONE);
                if(receipt.transactionIndex().compareTo(expectedIndex)>0)stop(Decision.INSUFFICIENT,"TRANSACTION_GAP");
                check(receipt.transactionIndex().equals(expectedIndex),"TRANSACTION_ORDER");check(hashes.add(linked.transaction().transactionHash()),"DUPLICATE_RAW_HASH");
                // Lower projections see different subsets. Enforce block-global order on full logs here.
                if(!linked.logs().isEmpty()){
                    check(linked.logs().getFirst().logIndex().compareTo(lastBlockLogIndex)>0,"GLOBAL_LOG_ORDER");
                    lastBlockLogIndex=linked.logs().getLast().logIndex();
                }
                var point=new CAuthorizationModels.ObservationPoint(receipt.blockNumber(),receipt.blockHash(),receipt.transactionHash(),receipt.transactionIndex());
                CAbiModels.Call call=linked.call();
                boolean successful=receipt.status()==1;
                AllowanceUse use=successful?allowanceUse(call,step.witness(),linked.transaction().sender(),deployment.vault()):null;
                if(use!=null&&use.observed()!=null&&knownAllowances.containsKey(use.key()))check(knownAllowances.get(use.key()).equals(use.observed()),"ALLOWANCE_TRANSCRIPT_CONFLICT");
                if(step.witness() instanceof TokenWitness witness){
                    check(call instanceof CAbiModels.Transfer||call instanceof CAbiModels.Approve||call instanceof CAbiModels.TransferFrom,"WITNESS_KIND");
                    if(w.pending()>=MAX_PENDING_TOKEN_GAP)stop(Decision.INSUFFICIENT,"PENDING_GAP_LIMIT");
                    var r=tokenProof.verify(new CTokenTransactionProofModels.Input(step.envelope(),new CTokenTransactionProofModels.Context(point,w.token(),root.token().cursor().parentHash(),witness.allowance())));
                    if(r.decision()!=CTokenTransactionProofModels.Decision.VERIFIED_IN_SCOPE)stop(Decision.valueOf(r.decision().name()),"TOKEN_"+r.reason());
                    check(r.verified().declaredVault().equals(deployment.vault())&&r.verified().vaultAssetEffect()==CTokenTransactionProofModels.VaultAssetEffect.ZERO_DELTA,"TOKEN_VAULT_BINDING");
                    w=new Work(w.financial(),r.verified().token(),w.pending()+1,w.tokenSteps()+1,w.financialSteps());
                }else{
                    CCustodyModels.State cut=alignedView(w.financial(),w.token());
                    CCustodyModels.State v;CTokenLedgerModels.State t;
                    if(step.witness() instanceof TradingWitness witness){
                        check(call instanceof CAbiModels.Direct||call instanceof CAbiModels.Relayed||call instanceof CAbiModels.Risk,"WITNESS_KIND");
                        CAbiModels.Report report=call instanceof CAbiModels.Direct a?a.report():call instanceof CAbiModels.Relayed a?a.report():((CAbiModels.Risk)call).report();
                        if(successful&&knownRules.containsKey(report.positionId())){
                            need(witness.prestate()!=null&&witness.prestate().rulesPosition()!=null,"RULES_WITNESS");
                            check(knownRules.get(report.positionId()).equals(witness.prestate().rulesPosition()),"RULES_TRANSCRIPT_CONFLICT");
                        }
                        var r=tradingProof.verify(new CTransactionProofModels.Input(step.envelope(),new CTransactionProofModels.Context(point,root.symbolHash(),witness.prestate(),witness.ownerCode(),witness.rulesKeys(),witness.operationUsed(),witness.activePosition(),cut,w.token(),root.token().cursor().parentHash())));
                        if(r.decision()!=CTransactionProofModels.Decision.VERIFIED_IN_SCOPE)stop(Decision.valueOf(r.decision().name()),"TRADING_"+r.reason());v=r.verified().vault();t=r.verified().token();
                        if(successful){need(r.verified().rules()!=null,"RULES_RESULT");knownRules.put(report.positionId(),r.verified().rules().after());}
                    }else if(step.witness() instanceof VaultWitness witness){
                        check(call instanceof CAbiModels.VaultAmount||call instanceof CAbiModels.ProcessClaim,"WITNESS_KIND");
                        var r=vaultProof.verify(new CVaultTransactionProofModels.Input(step.envelope(),new CVaultTransactionProofModels.Context(point,cut,w.token(),root.token().cursor().parentHash(),witness.allowance())));
                        if(r.decision()!=CVaultTransactionProofModels.Decision.VERIFIED_IN_SCOPE)stop(Decision.valueOf(r.decision().name()),"VAULT_"+r.reason());v=r.verified().vault();t=r.verified().token();
                    }else if(step.witness() instanceof ControlWitness witness){
                        check(call instanceof CAbiModels.Cancel||call instanceof CAbiModels.Pause,"WITNESS_KIND");
                        var r=controlProof.verify(new CControllerControlProofModels.Input(step.envelope(),new CControllerControlProofModels.Context(point,cut,w.token(),root.token().cursor().parentHash(),witness.nonce(),witness.ownerCode())));
                        if(r.decision()!=CControllerControlProofModels.Decision.VERIFIED_IN_SCOPE)stop(Decision.valueOf(r.decision().name()),"CONTROL_"+r.reason());v=r.verified().vault();t=r.verified().token();
                    }else{stop(Decision.INSUFFICIENT,"WITNESS_MISSING");return null;}
                    w=new Work(v,t,0,w.tokenSteps(),w.financialSteps()+1);
                }
                if(successful){
                    if(call instanceof CAbiModels.Approve approval)knownAllowances.put(new AllowanceKey(linked.transaction().sender(),approval.spender()),approval.amount());
                    if(use!=null){need(use.observed()!=null,"ALLOWANCE_WITNESS");knownAllowances.put(use.key(),use.observed().equals(CAbiModels.UINT_MAX)?use.observed():use.observed().subtract(use.amount()));}
                }
            }
            String transcriptHash=sha(canonical(transcript,MAX_RETAINED_BYTES));
            Summary summary=new Summary(root.sourceIdentity(),rootHash,transcriptHash,transcript.size(),w.tokenSteps(),w.financialSteps(),w.pending(),
                new Point(w.token().cursor().number(),w.token().cursor().hash(),w.token().lastTransactionIndex()),
                new Point(BigInteger.valueOf(w.financial().cursor().number()),w.financial().cursor().hash(),BigInteger.valueOf(w.financial().lastTransactionIndex())));
            return new Result(Decision.VERIFIED_IN_SCOPE,"C_OVERLAY_MEMORY_SAME_BLOCK_ONLY",summary,new Checkpoint(root,rootHash,transcript));
        }catch(Stop stop){return failure(stop.decision,stop.code);}
        catch(IndexOutOfBoundsException|ConcurrentModificationException changed){return failure(Decision.INSUFFICIENT,"INPUT_CHANGED");}
        catch(NullPointerException missing){return failure(Decision.INSUFFICIENT,"INCOMPLETE_CONTEXT");}
        catch(ClassCastException malformed){return failure(Decision.REJECTED,"CONTEXT_TYPE");}
        catch(IllegalArgumentException|ArithmeticException invalid){return failure(Decision.REJECTED,"CONTEXT_OR_REPLAY");}
    }
    private void validateRoot(Root r){
        var v=r.vault();var t=r.token();need(v.deployment()!=null&&t.deployment()!=null&&v.cursor()!=null&&t.cursor()!=null&&t.lastTransactionIndex()!=null,"ROOT_CONTEXT");
        var d=v.deployment();var td=t.deployment();
        var declared=new CReceiptEnvelopeModels.Deployment(d.chainId(),d.token(),d.vault(),d.controller(),d.rules());
        Set<String> addresses=new HashSet<>(List.of(declared.token(),declared.vault(),declared.controller(),declared.rules()));check(addresses.size()==4,"ROOT_CONTRACTS");
        check(d.chainId().equals(td.chainId())&&d.genesisHash().equals(td.genesisHash())&&d.token().equals(td.token()),"ROOT_DEPLOYMENT");
        need(BigInteger.valueOf(v.cursor().number()).equals(t.cursor().number())&&v.cursor().hash().equals(t.cursor().hash())&&v.cursor().parentHash().equals(t.cursor().parentHash())
            &&BigInteger.valueOf(v.lastTransactionIndex()).equals(t.lastTransactionIndex()),"ROOT_PROJECTION_POINT");
        vaults.validateState(v);need(t.minted()&&!t.observations().isEmpty(),"ROOT_MINT");
        tokens.apply(t,t.observations().values().iterator().next());check(t.balanceOf(d.vault()).equals(v.actualAssets()),"ROOT_ASSETS");
    }
    private static BigInteger rootBlockLogMaximum(Root root){
        BigInteger maximum=BigInteger.valueOf(-1);var block=root.vault().cursor();
        for(var observation:root.vault().observations().values())
            if(observation.block().number()==block.number()&&observation.block().hash().equals(block.hash()))
                for(var log:observation.logs())maximum=maximum.max(BigInteger.valueOf(log.index()));
        BigInteger number=BigInteger.valueOf(block.number());
        for(var observation:root.token().observations().values())
            if(observation.block().number().equals(number)&&observation.block().hash().equals(block.hash()))
                for(var log:observation.logs())maximum=maximum.max(log.index());
        return maximum;
    }
    private static CCustodyModels.State alignedView(CCustodyModels.State v,CTokenLedgerModels.State t){
        // Private only: financial maps, numbers and original observations are copied unchanged.
        int index;try{index=t.lastTransactionIndex().intValueExact();}catch(ArithmeticException range){stop(Decision.UNSUPPORTED,"CUT_RANGE");return null;}
        return new CCustodyModels.State(v.deployment(),v.cursor(),index,v.balances(),v.positions(),v.claims(),v.nonces(),v.usedOperations(),v.totalFree(),v.totalLocked(),v.house(),v.actualAssets(),v.totalUnpaid(),v.head(),v.next(),v.paused(),v.observations());
    }
    private static AllowanceUse allowanceUse(CAbiModels.Call call,Witness witness,String sender,String vault){
        if(call instanceof CAbiModels.TransferFrom transfer&&witness instanceof TokenWitness token){
            return new AllowanceUse(new AllowanceKey(transfer.from(),sender),token.allowance()==null?null:token.allowance().value(),transfer.amount());
        }
        if(call instanceof CAbiModels.VaultAmount amount&&amount.action()!=CAbiModels.VaultAction.WITHDRAW&&witness instanceof VaultWitness cash){
            return new AllowanceUse(new AllowanceKey(sender,vault),cash.allowance()==null?null:cash.allowance().value(),amount.amount());
        }
        return null;
    }
    private static int reserve(int used,Step step){
        need(step!=null&&step.envelope()!=null&&step.witness()!=null,"STEP");
        var e=step.envelope();var receipt=e.receipt();need(e.rawSignedTransaction()!=null&&receipt!=null&&receipt.logs()!=null,"ENVELOPE_INPUT");
        if(e.rawSignedTransaction().length()>2+2*CSignedTransactionVerifier.MAX_RAW_BYTES||receipt.logs().size()>128)stop(Decision.INSUFFICIENT,"ENVELOPE_LIMIT");
        long bytes=(long)e.rawSignedTransaction().length()*2;int data=0;
        for(int i=0;i<receipt.logs().size();i++){
            var log=receipt.logs().get(i);need(log!=null&&log.topics()!=null&&log.data()!=null,"LOG_INPUT");
            if(log.topics().size()>4||log.data().length()>2+2*(CReceiptEnvelopeVerifier.MAX_LOG_DATA_BYTES-data))stop(Decision.INSUFFICIENT,"LOG_LIMIT");
            data+=Math.max(0,(log.data().length()-2)/2);bytes+=(long)log.data().length()*2;
            for(String topic:log.topics()){need(topic!=null,"TOPIC");if(topic.length()>66)stop(Decision.INSUFFICIENT,"TOPIC_LIMIT");bytes+=(long)topic.length()*2;}
            // The rest of each fixed-shape log metadata is bounded by the existing envelope.
            bytes+=1024;
            if(bytes+used>MAX_RETAINED_BYTES)stop(Decision.INSUFFICIENT,"RETAINED_LIMIT");
        }
        bytes+=canonical(step.witness(),MAX_RETAINED_BYTES-used).length+4096L;
        if(bytes+used>MAX_RETAINED_BYTES)stop(Decision.INSUFFICIENT,"RETAINED_LIMIT");return Math.toIntExact(bytes+used);
    }
    private static Step snapshot(Step step){
        var e=step.envelope();var receipt=e.receipt();int count=receipt.logs().size();if(count>128)stop(Decision.INSUFFICIENT,"INPUT_CHANGED");List<CReceiptEnvelopeModels.RawLog> logs=new ArrayList<>(count);int data=0;
        for(int i=0;i<count;i++){var l=receipt.logs().get(i);need(l!=null&&l.topics()!=null&&l.data()!=null,"LOG_INPUT");int topics=l.topics().size();if(topics>4||l.data().length()>2+2*(CReceiptEnvelopeVerifier.MAX_LOG_DATA_BYTES-data))stop(Decision.INSUFFICIENT,"INPUT_CHANGED");data+=Math.max(0,(l.data().length()-2)/2);List<String> copied=new ArrayList<>(topics);for(int k=0;k<topics;k++){String topic=l.topics().get(k);need(topic!=null,"TOPIC");if(topic.length()>66)stop(Decision.INSUFFICIENT,"INPUT_CHANGED");copied.add(topic);}
            if(l.topics().size()!=topics)stop(Decision.INSUFFICIENT,"INPUT_CHANGED");
            logs.add(new CReceiptEnvelopeModels.RawLog(l.transactionHash(),l.blockNumber(),l.blockHash(),l.transactionIndex(),l.logIndex(),l.removed(),l.emitter(),List.copyOf(copied),l.data()));}
        if(receipt.logs().size()!=count)stop(Decision.INSUFFICIENT,"INPUT_CHANGED");
        var r=new CReceiptEnvelopeModels.Receipt(receipt.transactionHash(),receipt.blockNumber(),receipt.blockHash(),receipt.transactionIndex(),receipt.from(),receipt.to(),receipt.status(),List.copyOf(logs));
        return new Step(new CReceiptEnvelopeModels.Input(e.rawSignedTransaction(),e.expectedTransactionHash(),e.deployment(),e.block(),r),step.witness());
    }
    private static byte[] canonical(Object value,int limit){
        if(limit<0)stop(Decision.INSUFFICIENT,"RETAINED_LIMIT");
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(new OutputStream(){int count;public void write(int b){if(++count>limit)stop(Decision.INSUFFICIENT,"RETAINED_LIMIT");bytes.write(b);}public void write(byte[] b,int off,int len){if(len>limit-count)stop(Decision.INSUFFICIENT,"RETAINED_LIMIT");count+=len;bytes.write(b,off,len);}});write(out,value,0,limit);out.flush();return bytes.toByteArray();}
        catch(IOException|ReflectiveOperationException invalid){stop(Decision.REJECTED,"CANONICAL_FRAME");return null;}
    }
    private static void write(DataOutputStream out,Object value,int depth,int limit)throws IOException,ReflectiveOperationException{
        if(depth>32)stop(Decision.INSUFFICIENT,"FRAME_DEPTH");
        if(value==null){out.writeByte(0);return;}
        if(value instanceof String s){
            out.writeByte(1);if(s.length()>limit/4)stop(Decision.INSUFFICIENT,"FRAME_STRING");
            // Never let malformed UTF-16 be silently replaced and alias another retained witness.
            var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s));
            out.writeInt(encoded.remaining());
            if(encoded.hasArray())out.write(encoded.array(),encoded.arrayOffset()+encoded.position(),encoded.remaining());
            else while(encoded.hasRemaining())out.writeByte(encoded.get());
        }
        else if(value instanceof BigInteger n){out.writeByte(2);if(n.bitLength()>511)stop(Decision.REJECTED,"FRAME_INTEGER");byte[] b=n.toByteArray();out.writeInt(b.length);out.write(b);}
        else if(value instanceof Integer||value instanceof Long){out.writeByte(3);out.writeUTF(value.getClass().getName());out.writeLong(((Number)value).longValue());}
        else if(value instanceof Boolean b){out.writeByte(4);out.writeBoolean(b);}
        else if(value instanceof Enum<?> e){if(!Set.of(CCustodyModels.class,CTokenLedgerModels.class,CRulesTransitionModels.class).contains(e.getDeclaringClass().getEnclosingClass()))stop(Decision.UNSUPPORTED,"FRAME_ENUM");out.writeByte(5);out.writeUTF(e.getDeclaringClass().getName());out.writeUTF(e.name());}
        else if(value instanceof List<?> list){if(list.size()>4096)stop(Decision.INSUFFICIENT,"FRAME_COUNT");out.writeByte(6);out.writeInt(list.size());for(Object item:list)write(out,item,depth+1,limit);}
        else if(value instanceof Map<?,?> map){if(map.size()>4096)stop(Decision.INSUFFICIENT,"FRAME_COUNT");out.writeByte(7);out.writeInt(map.size());List<Map.Entry<byte[],Object>> sorted=new ArrayList<>();int keyBytes=0;for(var entry:map.entrySet()){int budget=Math.min(32768,limit-keyBytes);if(budget<=0)stop(Decision.INSUFFICIENT,"FRAME_KEYS");byte[] key=canonical(entry.getKey(),budget);keyBytes=Math.addExact(keyBytes,key.length);sorted.add(new AbstractMap.SimpleImmutableEntry<>(key,entry.getValue()));}sorted.sort((a,b)->Arrays.compareUnsigned(a.getKey(),b.getKey()));for(var entry:sorted){out.writeInt(entry.getKey().length);out.write(entry.getKey());write(out,entry.getValue(),depth+1,limit);}}
        else if(value instanceof Record record){Class<?> owner=record.getClass().getEnclosingClass();if(!Set.of(CGlobalProofOverlay.class,CCustodyModels.class,CTokenLedgerModels.class,CAbiEventModels.class,CReceiptEnvelopeModels.class,
                CAuthorizationModels.class,CRulesTransitionModels.class,CTokenTransactionProofModels.class,CVaultTransactionProofModels.class,CControllerControlProofModels.class).contains(owner))stop(Decision.UNSUPPORTED,"FRAME_RECORD");
            out.writeByte(8);out.writeUTF(record.getClass().getName());for(RecordComponent field:record.getClass().getRecordComponents())write(out,field.getAccessor().invoke(record),depth+1,limit);
        }else stop(Decision.UNSUPPORTED,"FRAME_TYPE");
    }
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA256 unavailable");}}
    private static Result failure(Decision d,String code){return new Result(d,"C_OVERLAY_"+code,null,null);}
    private static void need(boolean value,String code){if(!value)stop(Decision.INSUFFICIENT,code);}
    private static void check(boolean value,String code){if(!value)stop(Decision.REJECTED,code);}
    private static void stop(Decision d,String code){throw new Stop(d,code);}
}
