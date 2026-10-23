package com.pricetrack.exchange.futures.chain;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import org.slf4j.LoggerFactory;

/** Fixed-vocabulary diagnostics. Never logs exception messages, payloads, addresses or signatures. */
public final class FuturesDiagnostics {
    private FuturesDiagnostics() {}
    public enum Stage { DRAFT, SUBMIT, NONCE_READ, WORKER_EXECUTION, DEPLOYMENT, EOA, USER_NONCE,
        MARKET_CAPTURE, MARKET_RECHECK, REPORT_SIGN, PREPARE_COMMIT, PRE_SIGN_RECHECK, RAW_SIGN, SIGNED_COMMIT, ENCODE_VERIFY,
        CHAIN_ID, BLOCK_HEIGHT, CONTROLLER_CODE, RULES_CODE, CONTRACT_CODE, CONTROLLER_EXECUTOR, CONTROLLER_RULES, MAX_USER_TTL,
        RULES_EXECUTOR, PRICE_SIGNER, SYMBOL, POLICY, RPC_CALL, RPC_READ, RPC_BATCH,
        RELAYER_NONCE, GAS_PRICE, ESTIMATE, BROADCAST, SENDER, RECOVERY }
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(FuturesDiagnostics.class);
    private static final ThreadLocal<String> TRACE=new ThreadLocal<>();
    private static final ThreadLocal<Stage> STAGE=new ThreadLocal<>();
    record Context(String trace,String stage){}
    /** Immutable, fixed-vocabulary snapshot; safe across OkHttp callback threads. */
    static Context context(){return new Context(TRACE.get()==null?"NONE":TRACE.get(),STAGE.get()==null?"NONE":STAGE.get().name());}
    private static final Set<String> SAFE=Set.of("FUTURES_DEPLOYMENT_CONFLICT","FUTURES_CHAIN_MISMATCH","FUTURES_CODE_MISMATCH",
        "AUTHORIZED_DEPLOYMENT_CONFLICT","AUTHORIZED_VERSION_CONFLICT","FUTURES_IMMUTABLE_MISMATCH","FUTURES_CALL_UNAVAILABLE",
        "INVALID_ADDRESS_WORD","FUTURES_RPC_RESPONSE_UNAVAILABLE","PRICE_CHANGED","MARKET_CONNECTION_CHANGED","MARKET_UNAVAILABLE",
        "USER_NONCE_OR_EOA_CHANGED","FUTURES_RPC_UNAVAILABLE","AUTHORIZED_REPORT_CONFLICT");
    @FunctionalInterface public interface Io<T>{T get()throws IOException;}
    public static <T>T io(Stage stage,Io<T> task)throws IOException {
        boolean root=TRACE.get()==null;if(root)TRACE.set(UUID.randomUUID().toString());Stage previous=STAGE.get();STAGE.set(stage);long start=System.nanoTime();
        try{T result=task.get();record(stage,start,"OK","NONE",!root);return result;}
        catch(IOException|RuntimeException x){record(stage,start,"FAIL",category(x),true);throw x;}
        finally{if(previous==null)STAGE.remove();else STAGE.set(previous);if(root)TRACE.remove();}
    }
    public static <T>T work(Stage stage,Supplier<T> task){
        boolean root=TRACE.get()==null;if(root)TRACE.set(UUID.randomUUID().toString());Stage previous=STAGE.get();STAGE.set(stage);long start=System.nanoTime();
        try{T result=task.get();record(stage,start,"OK","NONE",true);return result;}
        catch(RuntimeException x){record(stage,start,"FAIL",category(x),true);throw x;}
        finally{if(previous==null)STAGE.remove();else STAGE.set(previous);if(root)TRACE.remove();}
    }
    public static Failure unavailable(Stage stage,IOException cause){
        String old=TRACE.get();if(old==null)TRACE.set(UUID.randomUUID().toString());
        try{record(stage,System.nanoTime(),"FAIL",category(cause),true);}finally{if(old==null)TRACE.remove();}
        var failure=new Failure("FUTURES_RPC_UNAVAILABLE");failure.initCause(cause);return failure;
    }
    private static String category(Throwable x){
        if(x instanceof java.io.InterruptedIOException)return "TRANSPORT_TIMEOUT";
        if(x instanceof java.net.ConnectException)return "CONNECT_FAILURE";
        if(x instanceof java.net.SocketException)return "SOCKET_FAILURE";
        if(x.getMessage()!=null&&SAFE.contains(x.getMessage()))return x.getMessage();
        return x instanceof IOException?"RPC_IO_FAILURE":"INTERNAL_FAILURE";
    }
    private static void record(Stage stage,long start,String outcome,String code,boolean visible){
        long elapsed=Math.max(0,(System.nanoTime()-start)/1_000_000);
        if(outcome.equals("FAIL"))LOG.warn("futures_stage trace={} stage={} outcome={} code={} elapsedMs={}",TRACE.get(),stage,outcome,code,elapsed);
        else if(visible)LOG.info("futures_stage trace={} stage={} outcome={} code={} elapsedMs={}",TRACE.get(),stage,outcome,code,elapsed);
        else LOG.debug("futures_stage trace={} stage={} outcome={} code={} elapsedMs={}",TRACE.get(),stage,outcome,code,elapsed);
    }
}
