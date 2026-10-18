package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.*;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;

/** Dedicated Web3j instance is owned by futures configuration, never registered as the spot Web3j bean. */
public final class FuturesRpcGateway implements FuturesTransactionSender.Transport, AutoCloseable {
    private final Web3j rpc;
    private final Web3jService service;
    private final FuturesChainIdentity chain;
    private final String codeHash;
    public FuturesRpcGateway(Web3jService service,FuturesChainIdentity chain,String codeHash) {
        if(codeHash==null || !codeHash.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("ENGINE_CODE_HASH_REQUIRED");
        this.service=service; this.rpc=Web3j.build(service); this.chain=chain; this.codeHash=codeHash;
    }
    @Override public void verifyDeployment(FuturesChainIdentity expected)throws IOException {
        if(!expected.equals(chain)) throw new IOException("FUTURES_DEPLOYMENT_CONFLICT");
        var id=rpc.ethChainId().send(); check(id);
        if(!id.getChainId().equals(chain.chainId())) throw new IOException("FUTURES_CHAIN_MISMATCH");
        var code=rpc.ethGetCode(chain.engine(),DefaultBlockParameterName.LATEST).send(); check(code);
        if(code.getCode()==null || code.getCode().equals("0x") || !Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code.getCode()))).equalsIgnoreCase(codeHash)) throw new IOException("FUTURES_CODE_MISMATCH");
        if(!wordAddress(call(selector("executor()"),DefaultBlockParameterName.LATEST)).equalsIgnoreCase(chain.executor())
            || !wordAddress(call(selector("priceSigner()"),DefaultBlockParameterName.LATEST)).equalsIgnoreCase(chain.priceSigner())
            || !call(selector("supportedSymbolHash()"),DefaultBlockParameterName.LATEST).equalsIgnoreCase(FuturesReport.SYMBOL_HASH)
            || !call(selector("policyHash()"),DefaultBlockParameterName.LATEST).equalsIgnoreCase(FuturesLedger.POLICY_HASH)) throw new IOException("FUTURES_IMMUTABLE_MISMATCH");
    }
    @Override public BigInteger pendingNonce(String sender)throws IOException {
        var n=rpc.ethGetTransactionCount(sender,DefaultBlockParameterName.PENDING).send(); check(n); return n.getTransactionCount();
    }
    @Override public BigInteger gasPrice()throws IOException { var p=rpc.ethGasPrice().send(); check(p); return p.getGasPrice(); }
    @Override public BigInteger estimate(String sender,BigInteger nonce,BigInteger gas,String engine,String data)throws IOException {
        var call=Transaction.createFunctionCallTransaction(sender,nonce,gas,null,engine,BigInteger.ZERO,data);
        // A future transaction executes in a NEW block, not the timestamp of the last mined block.
        // No timestamp override or fallback: nodes that reject the pending tag fail closed.
        var g=new Request<Object,EthEstimateGas>("eth_estimateGas",java.util.List.of(call,"pending"),service,EthEstimateGas.class).send();
        if(g.hasError()) throw new PrecheckFailure(precheckCode(g.getError().getData()));
        check(g);return g.getAmountUsed();
    }
    @Override public String broadcast(String raw)throws IOException { var sent=rpc.ethSendRawTransaction(raw).send(); check(sent); return sent.getTransactionHash(); }
    @Override public boolean known(String hash)throws IOException { return transaction(hash).isPresent(); }
    public Optional<org.web3j.protocol.core.methods.response.Transaction> transaction(String hash)throws IOException {
        var t=rpc.ethGetTransactionByHash(hash).send(); checkOptional(t); return t.getTransaction();
    }
    public Optional<TransactionReceipt> receipt(String hash)throws IOException { var r=rpc.ethGetTransactionReceipt(hash).send(); checkOptional(r); return r.getTransactionReceipt(); }
    public EthBlock.Block block(BigInteger number)throws IOException {
        var b=rpc.ethGetBlockByNumber(DefaultBlockParameter.valueOf(number),false).send(); check(b);
        if(b.getBlock()==null) throw new IOException("CANONICAL_BLOCK_UNAVAILABLE"); return b.getBlock();
    }
    public BigInteger height()throws IOException { var b=rpc.ethBlockNumber().send(); check(b); return b.getBlockNumber(); }
    /** Candidate block time is a preflight hint only; the mined receipt remains authoritative. */
    public long pendingTimestamp()throws IOException {
        var b=rpc.ethGetBlockByNumber(DefaultBlockParameterName.PENDING,false).send();check(b);
        try { long value=b.getBlock().getTimestamp().longValueExact();if(value<0)throw new ArithmeticException();return value; }
        catch(RuntimeException invalid) { throw new IOException("PENDING_BLOCK_TIME_UNAVAILABLE"); }
    }
    public String position(String positionId,BigInteger block)throws IOException {
        return call(selector("getPosition(bytes32)")+Numeric.cleanHexPrefix(Hash.sha3String(positionId)),DefaultBlockParameter.valueOf(block));
    }
    private String call(String data,DefaultBlockParameter block)throws IOException {
        var value=rpc.ethCall(Transaction.createEthCallTransaction(chain.executor(),chain.engine(),data),block).send(); check(value);
        if(value.isReverted() || value.getValue()==null || !value.getValue().matches("0x[0-9a-fA-F]+")) throw new IOException("FUTURES_CALL_UNAVAILABLE");
        return value.getValue();
    }
    private static String selector(String s) { return Hash.sha3String(s).substring(0,10); }
    private static String wordAddress(String s)throws IOException {
        if(!s.matches("0x0{24}[0-9a-fA-F]{40}")) throw new IOException("INVALID_ADDRESS_WORD"); return "0x"+s.substring(26);
    }
    private static void check(Response<?> response)throws IOException {
        if(response==null || response.hasError() || response.getResult()==null) throw new IOException("FUTURES_RPC_RESPONSE_UNAVAILABLE");
    }
    private static void checkOptional(Response<?> response)throws IOException {
        if(response==null || response.hasError()) throw new IOException("FUTURES_RPC_RESPONSE_UNAVAILABLE");
    }
    public static final class PrecheckFailure extends IOException {
        public final String code;
        PrecheckFailure(String code) { super(code);this.code=code; }
    }
    public static String precheckCode(String data) {
        // web3j may expose error.data as a JSON-encoded string. Never log/return that original value.
        if(data!=null && data.length()<=4096 && data.startsWith("\"")) try {
            var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(data);
            data=node.isTextual()?node.textValue():null;
        } catch(Exception malformed) { data=null; }
        if(data!=null && data.length()<=4096 && data.matches("0x[0-9a-fA-F]{8,}")) for(String name:java.util.List.of("GraceNotOver","RiskConditionNotMet","InvalidReport","Unauthorized","StaleVersion","InvalidState","AlreadyUsed"))
            if(data.substring(0,10).equalsIgnoreCase(Hash.sha3String(name+"()").substring(0,10)))return "FUTURES_"+name.replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase(java.util.Locale.ROOT);
        return "FUTURES_ESTIMATE_REJECTED";
    }
    @Override public void close() { rpc.shutdown(); }
}
