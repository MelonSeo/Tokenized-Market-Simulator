package com.pricetrack.exchange.custody.proof;

import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventModels;
import java.math.BigInteger;
import java.util.List;

/** Untrusted transport DTOs and bounded linked output. Not canonical chain/deployment evidence. */
public final class CReceiptEnvelopeModels {
    private CReceiptEnvelopeModels() {}
    public static final String SCOPE="PREVALIDATED_BLOCK_DEPLOYMENT_CONTEXT_ONLY; envelope/ABI linkage, "
        +"not owner/Oracle authorization, EOA code, economics, canonicality or full audit proof";
    public enum Decision { LINKED_IN_SCOPE, INSUFFICIENT, UNSUPPORTED, REJECTED }
    public record Deployment(BigInteger chainId,String token,String vault,String controller,String rules) {}
    public record BlockContext(BigInteger number,String hash,BigInteger timestamp) {}
    /** Input lists are untrusted; verifier takes bounded snapshots before crypto. */
    public record RawLog(String transactionHash,BigInteger blockNumber,String blockHash,
        BigInteger transactionIndex,BigInteger logIndex,Boolean removed,String emitter,List<String> topics,String data) {}
    public record Receipt(String transactionHash,BigInteger blockNumber,String blockHash,
        BigInteger transactionIndex,String from,String to,Integer status,List<RawLog> logs) {}
    public record Input(String rawSignedTransaction,String expectedTransactionHash,Deployment deployment,
        BlockContext block,Receipt receipt) {
        @Override public String toString() {return "CReceiptEnvelopeInput[raw and transport payload redacted]";}
    }
    public record ReceiptMetadata(String transactionHash,BigInteger blockNumber,String blockHash,
        BigInteger transactionIndex,String from,String to,int status) {}
    public record DecodedLog(BigInteger logIndex,String emitter,CAbiEventModels.EmitterKind kind,
        CAbiEventModels.Event event) {}
    public record Linked(CSignedTransactionVerifier.Proof transaction,CAbiModels.Call call,
        BlockContext block,ReceiptMetadata receipt,List<DecodedLog> logs) {
        public Linked {logs=List.copyOf(logs);}
        @Override public String toString() {return "CReceiptEnvelopeLinked[hash="+receipt.transactionHash()
            +", status="+receipt.status()+", logCount="+logs.size()+", call and raw redacted]";}
    }
    public record Result(Decision decision,String reason,Linked linked) {
        public String verificationScope() {return SCOPE;}
        public boolean completeCoverage() {return false;}
    }
}
