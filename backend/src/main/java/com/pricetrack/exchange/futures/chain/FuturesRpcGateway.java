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
    private final String rulesCodeHash;
    public FuturesRpcGateway(Web3jService service,FuturesChainIdentity chain,String codeHash) {
        this(service,chain,codeHash,null);
    }
    public FuturesRpcGateway(Web3jService service,FuturesChainIdentity chain,String codeHash,String rulesCodeHash) {
        if(codeHash==null || !codeHash.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("ENGINE_CODE_HASH_REQUIRED");
        if(chain.authorized()&&(rulesCodeHash==null||!rulesCodeHash.matches("0x[0-9a-fA-F]{64}")))throw new IllegalArgumentException("RULES_CODE_HASH_REQUIRED");
        this.service=new ObservedFuturesRpc(service,chain); this.rpc=Web3j.build(this.service); this.chain=chain; this.codeHash=codeHash;this.rulesCodeHash=rulesCodeHash;
    }
    @Override public void verifyDeployment(FuturesChainIdentity expected)throws IOException {
        if(!expected.equals(chain)) throw new IOException("FUTURES_DEPLOYMENT_CONFLICT");
        var id=rpc.ethChainId().send(); check(id);
        if(!id.getChainId().equals(chain.chainId())) throw new IOException("FUTURES_CHAIN_MISMATCH");
        var code=rpc.ethGetCode(chain.engine(),DefaultBlockParameterName.LATEST).send(); check(code);
        if(code.getCode()==null || code.getCode().equals("0x") || !Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code.getCode()))).equalsIgnoreCase(codeHash)) throw new IOException("FUTURES_CODE_MISMATCH");
        if(chain.authorized()){
            var inner=rpc.ethGetCode(chain.rules(),DefaultBlockParameterName.LATEST).send();check(inner);
            if(!Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(inner.getCode()))).equalsIgnoreCase(rulesCodeHash)
                ||!wordAddress(call(selector("executor()"),DefaultBlockParameterName.LATEST)).equalsIgnoreCase(chain.executor())
                ||!wordAddress(call(selector("rules()"),DefaultBlockParameterName.LATEST)).equalsIgnoreCase(chain.rules()))throw new IOException("AUTHORIZED_DEPLOYMENT_CONFLICT");
        }
        if(chain.v2()&&!Numeric.toBigInt(call(selector("MAX_USER_TTL()"),DefaultBlockParameterName.LATEST)).equals(BigInteger.valueOf(120)))throw new IOException("AUTHORIZED_VERSION_CONFLICT");
        if(!wordAddress(callAt(chain.priceContract(),selector("executor()"),DefaultBlockParameterName.LATEST)).equalsIgnoreCase(chain.reportExecutor())
            || !wordAddress(callAt(chain.priceContract(),selector("priceSigner()"),DefaultBlockParameterName.LATEST)).equalsIgnoreCase(chain.priceSigner())
            || !callAt(chain.priceContract(),selector("supportedSymbolHash()"),DefaultBlockParameterName.LATEST).equalsIgnoreCase(FuturesReport.SYMBOL_HASH)
            || !callAt(chain.priceContract(),selector("policyHash()"),DefaultBlockParameterName.LATEST).equalsIgnoreCase(FuturesLedger.POLICY_HASH)) throw new IOException("FUTURES_IMMUTABLE_MISMATCH");
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
    /** Bounded read-only reverse audit includes reverted transactions that emitted no events. */
    public java.util.List<org.web3j.protocol.core.methods.response.Transaction> engineTransactions(BigInteger number)throws IOException {
        var response=rpc.ethGetBlockByNumber(DefaultBlockParameter.valueOf(number),true).send();check(response);
        if(response.getBlock()==null || response.getBlock().getTransactions().size()>2000)throw new IOException("AUDIT_BLOCK_UNAVAILABLE");
        var found=new java.util.ArrayList<org.web3j.protocol.core.methods.response.Transaction>();
        for(var item:response.getBlock().getTransactions()) {
            if(!(item.get() instanceof org.web3j.protocol.core.methods.response.Transaction tx))throw new IOException("AUDIT_BLOCK_TRANSACTION_UNAVAILABLE");
            if(chain.engine().equalsIgnoreCase(tx.getTo()))found.add(tx);
        }
        return java.util.List.copyOf(found);
    }
    /** Audit-only union of controller and rules destinations, including reverted/no-event calls. */
    public java.util.List<org.web3j.protocol.core.methods.response.Transaction> auditTransactions(BigInteger number)throws IOException {
        var response=rpc.ethGetBlockByNumber(DefaultBlockParameter.valueOf(number),true).send();check(response);
        if(response.getBlock()==null||response.getBlock().getTransactions().size()>2000)throw new IOException("AUDIT_BLOCK_UNAVAILABLE");
        var found=new java.util.ArrayList<org.web3j.protocol.core.methods.response.Transaction>();
        for(var item:response.getBlock().getTransactions()){
            if(!(item.get() instanceof org.web3j.protocol.core.methods.response.Transaction tx))throw new IOException("AUDIT_BLOCK_TRANSACTION_UNAVAILABLE");
            if(chain.engine().equalsIgnoreCase(tx.getTo())||chain.priceContract().equalsIgnoreCase(tx.getTo()))found.add(tx);
        }
        return java.util.List.copyOf(found);
    }
    /** Exact-block bytecode commitments. Keys are controller/rules even in the legacy single-contract case. */
    public java.util.Map<String,String> deploymentCodeHashes(BigInteger block)throws IOException {
        String controller=codeHashAt(chain.engine(),block);
        String rules=chain.authorized()?codeHashAt(chain.rules(),block):controller;
        return java.util.Map.of("controller",controller,"rules",rules);
    }
    private String codeHashAt(String address,BigInteger block)throws IOException{
        var code=rpc.ethGetCode(address,DefaultBlockParameter.valueOf(block)).send();check(code);
        if(code.getCode()==null||!code.getCode().matches("0x(?:[0-9a-fA-F]{2})+") )throw new IOException("AUDIT_CODE_UNAVAILABLE");
        return Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code.getCode())));
    }
    public BigInteger height()throws IOException { var b=rpc.ethBlockNumber().send(); check(b); return b.getBlockNumber(); }
    /** Candidate block time is a preflight hint only; the mined receipt remains authoritative. */
    public long pendingTimestamp()throws IOException {
        var b=rpc.ethGetBlockByNumber(DefaultBlockParameterName.PENDING,false).send();check(b);
        try { long value=b.getBlock().getTimestamp().longValueExact();if(value<0)throw new ArithmeticException();return value; }
        catch(RuntimeException invalid) { throw new IOException("PENDING_BLOCK_TIME_UNAVAILABLE"); }
    }
    public String position(String positionId,BigInteger block)throws IOException {
        return callAt(chain.priceContract(),selector("getPosition(bytes32)")+Numeric.cleanHexPrefix(Hash.sha3String(positionId)),DefaultBlockParameter.valueOf(block));
    }
    public String destination(){return chain.engine();}
    public boolean authorized(){return chain.authorized();}
    public boolean v2(){return chain.v2();}
    public String positionOwner(String id,BigInteger block)throws IOException {if(!chain.authorized())throw new IOException("USER_AUTHORIZATION_MODE_REQUIRED");return wordAddress(call(selector("positionOwner(bytes32)")+Numeric.cleanHexPrefix(Hash.sha3String(id)),DefaultBlockParameter.valueOf(block)));}
    public BigInteger userNonce(String owner)throws IOException {return Numeric.toBigInt(call(selector("nonces(address)")+"0".repeat(24)+owner.substring(2),DefaultBlockParameterName.LATEST));}
    /** Audit anchor: no latest fallback and no short/oversized ABI word acceptance. */
    public BigInteger userNonce(String owner,BigInteger block)throws IOException {
        if(!chain.v2()||owner==null||!owner.matches("0x[0-9a-fA-F]{40}")||block==null||block.signum()<0)throw new IOException("AUDIT_NONCE_ARGUMENT_REQUIRED");
        String value=call(selector("nonces(address)")+"0".repeat(24)+owner.substring(2),DefaultBlockParameter.valueOf(block));
        if(!value.matches("0x[0-9a-fA-F]{64}"))throw new IOException("AUDIT_NONCE_WORD_REQUIRED");
        return Numeric.toBigInt(value);
    }
    public boolean eoa(String owner,BigInteger block)throws IOException {
        if(owner==null||!owner.matches("0x[0-9a-fA-F]{40}")||block==null||block.signum()<0)throw new IOException("AUDIT_EOA_ARGUMENT_REQUIRED");
        var code=rpc.ethGetCode(owner,DefaultBlockParameter.valueOf(block)).send();check(code);return "0x".equals(code.getCode());
    }
    public boolean eoa(String owner)throws IOException {var c=rpc.ethGetCode(owner,DefaultBlockParameterName.LATEST).send();check(c);return "0x".equals(c.getCode());}
    /** Read-only explicit-block log scan. No provider-error text escapes this boundary. */
    public java.util.List<Log> logs(BigInteger from,BigInteger to)throws IOException {
        if(from.signum()<0 || to.compareTo(from)<0 || to.subtract(from).compareTo(BigInteger.valueOf(249))>0)throw new IOException("AUDIT_LOG_RANGE_LIMIT");
        var filter=new org.web3j.protocol.core.methods.request.EthFilter(DefaultBlockParameter.valueOf(from),DefaultBlockParameter.valueOf(to),chain.engine());
        filter.addOptionalTopics(FuturesReceiptVerifier.APPLIED,FuturesReceiptVerifier.CONSUMED);
        var response=rpc.ethGetLogs(filter).send();check(response);
        if(response.getLogs().size()>2000)throw new IOException("AUDIT_LOG_RESULT_LIMIT");
        var result=new java.util.ArrayList<Log>();
        for(var item:response.getLogs())if(item.get() instanceof Log log)result.add(log);else throw new IOException("AUDIT_LOG_UNAVAILABLE");
        return java.util.List.copyOf(result);
    }
    /** No topic filtering: unexpected contract events must not disappear from reverse coverage. */
    public java.util.List<Log> auditLogs(BigInteger from,BigInteger to)throws IOException {
        if(from.signum()<0||to.compareTo(from)<0||to.subtract(from).compareTo(BigInteger.valueOf(249))>0)throw new IOException("AUDIT_LOG_RANGE_LIMIT");
        java.util.List<String> addresses=chain.authorized()?java.util.List.of(chain.engine(),chain.rules()):java.util.List.of(chain.engine());
        var filter=new org.web3j.protocol.core.methods.request.EthFilter(DefaultBlockParameter.valueOf(from),DefaultBlockParameter.valueOf(to),addresses);
        var response=rpc.ethGetLogs(filter).send();check(response);
        if(response.getLogs().size()>2000)throw new IOException("AUDIT_LOG_RESULT_LIMIT");
        var result=new java.util.ArrayList<Log>();
        for(var item:response.getLogs())if(item.get() instanceof Log log)result.add(log);else throw new IOException("AUDIT_LOG_UNAVAILABLE");
        return java.util.List.copyOf(result);
    }
    private String call(String data,DefaultBlockParameter block)throws IOException {
        return callAt(chain.engine(),data,block);
    }
    private String callAt(String to,String data,DefaultBlockParameter block)throws IOException {
        var value=rpc.ethCall(Transaction.createEthCallTransaction(chain.executor(),to,data),block).send(); check(value);
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
