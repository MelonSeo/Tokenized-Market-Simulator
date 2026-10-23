package com.pricetrack.exchange.futures.chain;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.*;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.websocket.events.Notification;
import org.web3j.crypto.Hash;
import io.reactivex.Flowable;
import static com.pricetrack.exchange.futures.chain.FuturesDiagnostics.Stage.*;

/** Transparent sync RPC instrumentation: no retries, caching or altered deadlines/results. */
final class ObservedFuturesRpc implements Web3jService {
    private final Web3jService delegate;
    private final FuturesChainIdentity identity;
    ObservedFuturesRpc(Web3jService delegate,FuturesChainIdentity identity){this.delegate=delegate;this.identity=identity;}
    @Override public <T extends Response>T send(Request r,Class<T> type)throws IOException{return FuturesDiagnostics.io(stage(r),()->delegate.send(r,type));}
    private FuturesDiagnostics.Stage stage(Request r){
        return switch(r.getMethod()){
            case "eth_chainId"->CHAIN_ID;
            case "eth_blockNumber"->BLOCK_HEIGHT;
            case "eth_getCode"->{Object to=r.getParams().isEmpty()?null:r.getParams().get(0);yield identity.engine().equals(to)?CONTROLLER_CODE:identity.rules()!=null&&identity.rules().equals(to)?RULES_CODE:EOA;}
            case "eth_getTransactionCount"->RELAYER_NONCE;
            case "eth_gasPrice"->GAS_PRICE;case "eth_estimateGas"->ESTIMATE;case "eth_sendRawTransaction"->BROADCAST;
            case "eth_call"->{
                Object first=r.getParams().isEmpty()?null:r.getParams().get(0);
                if(!(first instanceof Transaction t)||t.getData()==null||t.getData().length()<10)yield RPC_CALL;
                String selector=t.getData().substring(0,10);
                if(selector.equals(sel("executor()")))yield identity.engine().equalsIgnoreCase(t.getTo())?CONTROLLER_EXECUTOR:RULES_EXECUTOR;
                if(selector.equals(sel("rules()")))yield CONTROLLER_RULES;
                if(selector.equals(sel("MAX_USER_TTL()")))yield MAX_USER_TTL;
                if(selector.equals(sel("priceSigner()")))yield PRICE_SIGNER;
                if(selector.equals(sel("supportedSymbolHash()")))yield SYMBOL;
                if(selector.equals(sel("policyHash()")))yield POLICY;
                if(selector.equals(sel("nonces(address)")))yield USER_NONCE;
                yield RPC_CALL;
            }
            default->RPC_READ;
        };
    }
    private static String sel(String text){return Hash.sha3String(text).substring(0,10);}
    // Gateway currently uses synchronous send exclusively. Other interface behavior remains untouched.
    @Override public <T extends Response>CompletableFuture<T> sendAsync(Request r,Class<T> t){return delegate.sendAsync(r,t);}
    @Override public BatchResponse sendBatch(BatchRequest r)throws IOException{return delegate.sendBatch(r);}
    @Override public CompletableFuture<BatchResponse> sendBatchAsync(BatchRequest r){return delegate.sendBatchAsync(r);}
    @Override public <T extends Notification<?>>Flowable<T> subscribe(Request r,String u,Class<T> t){return delegate.subscribe(r,u,t);}
    @Override public void close()throws IOException{delegate.close();}
}
