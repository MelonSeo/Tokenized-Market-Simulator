package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CReceiptEnvelopeModels.*;
import com.pricetrack.exchange.custody.abi.CAbiCodec;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventCodec;
import com.pricetrack.exchange.custody.abi.CAbiEventModels;
import java.math.BigInteger;
import java.util.*;

/** Internal raw recovery + exact call/event ABI + Receipt metadata linkage only.
 * No RPC, signature authorization, financial/event sequence validation, or canonicality claim. */
public final class CReceiptEnvelopeVerifier {
    public static final int MAX_LOGS=128,MAX_LOG_DATA_BYTES=64*1024;
    private static final BigInteger UINT_MAX=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    private static final String ZERO_ADDRESS="0x"+"0".repeat(40);
    private final CSignedTransactionVerifier transactions=new CSignedTransactionVerifier();
    private final CAbiCodec calls=new CAbiCodec();
    private final CAbiEventCodec events=new CAbiEventCodec();
    private record Prepared(Deployment deployment,BlockContext block,ReceiptMetadata receipt,
        List<RawLog> logs,String raw,String expectedHash) {}
    private static final class Problem extends RuntimeException {
        final Decision decision;final String code;
        Problem(Decision decision,String code){super(code,null,false,false);this.decision=decision;this.code=code;}
    }

    public Result verify(Input input) {
        try {
            Prepared p=prepare(input); // Size and missing-metadata checks precede expensive crypto.
            CSignedTransactionVerifier.Proof proof;
            try {proof=transactions.verify(p.raw(),p.expectedHash(),p.deployment().chainId());}
            catch(CSignedTransactionVerifier.Failure failure) {
                Decision decision=switch(failure.kind()) {
                    case UNSUPPORTED_INPUT -> Decision.UNSUPPORTED;
                    case RESOURCE_LIMIT -> Decision.INSUFFICIENT;
                    default -> Decision.REJECTED;
                };
                throw problem(decision,"SIGNED_ENVELOPE_"+failure.kind().name());
            }
            if(proof.contractCreation())throw problem(Decision.UNSUPPORTED,"CONTRACT_CREATION");
            ReceiptMetadata receipt=p.receipt();
            reject(proof.transactionHash().equals(receipt.transactionHash()),"TX_HASH_CONFLICT");
            reject(proof.sender().equals(receipt.from()),"TX_FROM_CONFLICT");
            if(receipt.to()==null)throw problem(Decision.INSUFFICIENT,"RECEIPT_TO_MISSING");
            reject(proof.to().equals(receipt.to()),"TX_TO_CONFLICT");
            CAbiModels.Target target=callTarget(p.deployment(),proof.to());
            CAbiModels.Call call;
            try {call=calls.decode(target,proof.calldata());}
            catch(IllegalArgumentException failure) {
                if("C_ABI_SELECTOR".equals(failure.getMessage()))throw problem(Decision.UNSUPPORTED,"CALL_SELECTOR");
                if("C_ABI_BYTES_LIMIT".equals(failure.getMessage())||proof.calldata().length()>2+2*CAbiCodec.MAX_CALLDATA_BYTES)
                    throw problem(Decision.INSUFFICIENT,"CALL_ABI_LIMIT");
                // This codec deliberately accepts a canonical ABI subset. Solidity may accept
                // noncanonical offsets/trailing bytes; a subset failure is not an EVM conflict.
                throw problem(Decision.UNSUPPORTED,"CALL_ABI_SUBSET");
            }
            // Apply nonpayable semantics only after identifying a supported C call.
            // Unknown targets/selectors may be payable; reverted value-bearing calls
            // remain valid observations without inferring their remote revert cause.
            if(receipt.status()==1)reject(proof.value().signum()==0,"NONPAYABLE_VALUE");
            if(receipt.status()==0) {
                reject(p.logs().isEmpty(),"REVERTED_LOGS");
                return linked(p,proof,call,List.of(),"REVERTED_NO_LOGS_NO_CAUSE_INFERENCE");
            }
            List<DecodedLog> decoded=new ArrayList<>();BigInteger previous=null;
            for(RawLog log:p.logs()) {
                reject(log.transactionHash().equals(receipt.transactionHash()),"LOG_TX_HASH_CONFLICT");
                reject(log.blockHash().equals(receipt.blockHash())&&log.blockNumber().equals(receipt.blockNumber()),"LOG_BLOCK_CONFLICT");
                reject(log.transactionIndex().equals(receipt.transactionIndex()),"LOG_TX_INDEX_CONFLICT");
                reject(!log.removed(),"LOG_REMOVED");
                reject(previous==null||log.logIndex().compareTo(previous)>0,"LOG_INDEX_ORDER");previous=log.logIndex();
                CAbiEventModels.EmitterKind kind=eventKind(p.deployment(),log.emitter());
                CAbiEventModels.Log abi;
                try {abi=new CAbiEventModels.Log(log.emitter(),log.topics(),log.data());}
                catch(IllegalArgumentException malformed){throw problem(Decision.REJECTED,"EVENT_ABI");}
                CAbiEventModels.Event event;
                try {event=events.decode(kind,abi);}
                catch(IllegalArgumentException malformed) {
                    if("C_ABI_EVENT_TOPIC0".equals(malformed.getMessage()))throw problem(Decision.UNSUPPORTED,"EVENT_TOPIC");
                    throw problem(Decision.REJECTED,"EVENT_ABI");
                }
                decoded.add(new DecodedLog(log.logIndex(),log.emitter(),kind,event));
            }
            return linked(p,proof,call,decoded,"LINKED_METADATA_AND_ABI_ONLY");
        } catch(Problem problem) {return new Result(problem.decision,"C_ENV_"+problem.code,null);}
        catch(IndexOutOfBoundsException|ConcurrentModificationException changed) {
            return new Result(Decision.INSUFFICIENT,"C_ENV_INPUT_CHANGED",null);
        }
    }

    private static Result linked(Prepared p,CSignedTransactionVerifier.Proof proof,CAbiModels.Call call,
        List<DecodedLog> logs,String code) {
        return new Result(Decision.LINKED_IN_SCOPE,"C_ENV_"+code,new Linked(proof,call,p.block(),p.receipt(),logs));
    }
    private Prepared prepare(Input input) {
        missing(input!=null&&input.deployment()!=null&&input.block()!=null&&input.receipt()!=null,"INPUT_MISSING");
        missing(input.rawSignedTransaction()!=null&&input.expectedTransactionHash()!=null,"RAW_IDENTITY_MISSING");
        if(input.rawSignedTransaction().length()>2+2*CSignedTransactionVerifier.MAX_RAW_BYTES)throw problem(Decision.INSUFFICIENT,"RAW_LIMIT");
        Deployment d=input.deployment();
        BigInteger chain=number(d.chainId(),"CHAIN_ID");
        if(chain.signum()==0)throw problem(Decision.INSUFFICIENT,"DEPLOYMENT_CONTEXT");
        String token=addr(d.token(),"TOKEN"),vault=addr(d.vault(),"VAULT"),controller=addr(d.controller(),"CONTROLLER"),rules=addr(d.rules(),"RULES");
        List<String> addresses=List.of(token,vault,controller,rules);
        if(addresses.contains(ZERO_ADDRESS)||new HashSet<>(addresses).size()!=4)throw problem(Decision.INSUFFICIENT,"DEPLOYMENT_CONTEXT");
        Deployment deployment=new Deployment(chain,token,vault,controller,rules);
        BlockContext block=new BlockContext(number(input.block().number(),"BLOCK_NUMBER"),hash(input.block().hash(),"BLOCK_HASH"),number(input.block().timestamp(),"BLOCK_TIME"));
        Receipt r=input.receipt();missing(r.status()!=null&&r.logs()!=null,"RECEIPT_MISSING");
        reject(r.status()==0||r.status()==1,"RECEIPT_STATUS");
        ReceiptMetadata receipt=new ReceiptMetadata(hash(r.transactionHash(),"RECEIPT_TX_HASH"),number(r.blockNumber(),"RECEIPT_BLOCK_NUMBER"),
            hash(r.blockHash(),"RECEIPT_BLOCK_HASH"),number(r.transactionIndex(),"RECEIPT_TX_INDEX"),addr(r.from(),"RECEIPT_FROM"),
            r.to()==null?null:addr(r.to(),"RECEIPT_TO"),r.status());
        reject(receipt.blockHash().equals(block.hash())&&receipt.blockNumber().equals(block.number()),"RECEIPT_BLOCK_CONFLICT");
        int count=r.logs().size();if(count>MAX_LOGS)throw problem(Decision.INSUFFICIENT,"LOG_COUNT_LIMIT");
        List<RawLog> logs=new ArrayList<>(count);int dataBytes=0;
        for(int n=0;n<count;n++) {
            RawLog log;
            try {log=r.logs().get(n);}catch(IndexOutOfBoundsException changed){throw problem(Decision.INSUFFICIENT,"INPUT_CHANGED");}
            missing(log!=null&&log.topics()!=null&&log.data()!=null&&log.removed()!=null,"LOG_MISSING");
            int topicCount=log.topics().size();
            if(topicCount>4)throw problem(Decision.INSUFFICIENT,"LOG_TOPIC_LIMIT");
            reject(topicCount>0,"LOG_TOPICS");
            // Check aggregate length before regex/decoding/copying potentially oversized transport data.
            if(log.data().length()>2+2*(MAX_LOG_DATA_BYTES-dataBytes))throw problem(Decision.INSUFFICIENT,"LOG_DATA_LIMIT");
            reject(log.data().startsWith("0x")&&(log.data().length()&1)==0&&log.data().matches("0x[0-9a-fA-F]*"),"LOG_DATA_HEX");
            dataBytes+=(log.data().length()-2)/2;
            List<String> topics=new ArrayList<>(topicCount);
            for(int k=0;k<topicCount;k++)topics.add(hash(log.topics().get(k),"LOG_TOPIC"));
            logs.add(new RawLog(hash(log.transactionHash(),"LOG_TX_HASH"),number(log.blockNumber(),"LOG_BLOCK_NUMBER"),
                hash(log.blockHash(),"LOG_BLOCK_HASH"),number(log.transactionIndex(),"LOG_TX_INDEX"),number(log.logIndex(),"LOG_INDEX"),
                log.removed(),addr(log.emitter(),"LOG_EMITTER"),List.copyOf(topics),log.data().toLowerCase(Locale.ROOT)));
        }
        if(r.logs().size()!=count)throw problem(Decision.INSUFFICIENT,"INPUT_CHANGED");
        return new Prepared(deployment,block,receipt,List.copyOf(logs),input.rawSignedTransaction(),hash(input.expectedTransactionHash(),"EXPECTED_TX_HASH"));
    }
    private static CAbiModels.Target callTarget(Deployment deployment,String target) {
        if(target.equals(deployment.controller()))return CAbiModels.Target.CONTROLLER;
        if(target.equals(deployment.vault()))return CAbiModels.Target.VAULT;
        if(target.equals(deployment.token()))return CAbiModels.Target.TOKEN;
        throw problem(Decision.UNSUPPORTED,"CALL_TARGET");
    }
    private static CAbiEventModels.EmitterKind eventKind(Deployment d,String address) {
        if(address.equals(d.token()))return CAbiEventModels.EmitterKind.TOKEN;
        if(address.equals(d.vault()))return CAbiEventModels.EmitterKind.VAULT;
        if(address.equals(d.controller()))return CAbiEventModels.EmitterKind.CONTROLLER;
        if(address.equals(d.rules()))return CAbiEventModels.EmitterKind.RULES;
        throw problem(Decision.UNSUPPORTED,"EVENT_EMITTER");
    }
    private static BigInteger number(BigInteger value,String field) {
        missing(value!=null,field+"_MISSING");reject(value.signum()>=0&&value.compareTo(UINT_MAX)<=0,field+"_FORMAT");return value;
    }
    private static String hash(String value,String field) {
        missing(value!=null,field+"_MISSING");reject(value.matches("0x[0-9a-fA-F]{64}"),field+"_FORMAT");return value.toLowerCase(Locale.ROOT);
    }
    private static String addr(String value,String field) {
        missing(value!=null,field+"_MISSING");reject(value.matches("0x[0-9a-fA-F]{40}"),field+"_FORMAT");return value.toLowerCase(Locale.ROOT);
    }
    private static void reject(boolean condition,String code){if(!condition)throw problem(Decision.REJECTED,code);}
    private static void missing(boolean condition,String code){if(!condition)throw problem(Decision.INSUFFICIENT,code);}
    private static Problem problem(Decision decision,String code){return new Problem(decision,code);}
}
