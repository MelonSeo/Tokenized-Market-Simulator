package com.pricetrack.exchange.futures.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.runtime.*;
import java.math.BigInteger;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.http.HttpService;

/** Additive opt-in runtime. Never changes/reuses the legacy sender's nonce admission domain. */
@Configuration
@ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled","app.futures.user-authorization.enabled"},havingValue="true")
public class AuthorizedFuturesConfig {
    private Credentials key(Environment e,String name){try{String raw=e.getRequiredProperty(name);if(!raw.matches("(?:0x)?[0-9a-fA-F]{64}"))throw new IllegalArgumentException();var c=Credentials.create(raw);if(c.getEcKeyPair().getPrivateKey().signum()==0)throw new IllegalArgumentException();return c;}catch(Exception invalid){throw new IllegalStateException("AUTHORIZED_KEYS_REQUIRED");}}
    public static final class Runtime implements AutoCloseable {
        final FuturesChainIdentity chain;final FuturesRpcGateway rpc;final Credentials signer;final FuturesWorker worker;
        Runtime(FuturesChainIdentity c,FuturesRpcGateway r,Credentials s,FuturesWorker w){chain=c;rpc=r;signer=s;worker=w;}
        public void start(){worker.start();}
        public FuturesChainIdentity auditIdentity(){return chain;}
        public FuturesRpcGateway auditRpc(){return rpc;}
        @Override public void close(){worker.close();rpc.close();}
    }
    @Bean(initMethod="start",destroyMethod="close") Runtime authorizedFuturesRuntime(Environment e,DataSource ds,FuturesLedger ledger,FuturesMarketGate market,FuturesTransactionStore store,AuthorizedFuturesStore authorized,FuturesChainIdentity legacy){
        String prefix="app.futures.user-authorization.";
        var executor=key(e,prefix+"executor-private-key");var signer=key(e,prefix+"signer-private-key");
        if(executor.getAddress().equalsIgnoreCase(legacy.executor())||executor.getAddress().equalsIgnoreCase(legacy.priceSigner()))throw new IllegalStateException("AUTHORIZED_RELAYER_MUST_BE_DEDICATED");
        for(String name:new String[]{"app.blockchain.operator-private-key","app.blockchain.price-report.signer-private-key"}){
            if(e.getProperty(name,"").isBlank())continue;var old=key(e,name);
            if(old.getAddress().equalsIgnoreCase(executor.getAddress())||old.getAddress().equalsIgnoreCase(signer.getAddress()))throw new IllegalStateException("AUTHORIZED_KEYS_MUST_BE_DEDICATED");
        }
        var chain=new FuturesChainIdentity(new BigInteger(e.getRequiredProperty(prefix+"chain-id")),e.getRequiredProperty(prefix+"controller-address"),executor.getAddress(),signer.getAddress(),e.getRequiredProperty(prefix+"rules-address"));
        var http=new okhttp3.OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(3)).build();
        var rpc=new FuturesRpcGateway(new HttpService(e.getRequiredProperty(prefix+"rpc-url"),http,false),chain,e.getRequiredProperty(prefix+"controller-code-hash"),e.getRequiredProperty(prefix+"rules-code-hash"));
        try{
            rpc.verifyDeployment(chain);
            var intents=new AuthorizedIntentStore(ds,ledger,authorized);
            var sender=new FuturesTransactionSender(chain,executor,store,rpc);
            var reconciler=new FuturesReconciler(chain,rpc,store,sender,ledger,e.getProperty(prefix+"confirmations",Integer.class,1));
            var execution=new FuturesExecution(ledger,intents,market,chain,signer,store,sender,id->{
                var a=store.authorization(id);if(a!=null&&a.approval()!=null)try{if(!rpc.eoa(a.owner())||!rpc.userNonce(a.owner()).equals(a.approval().nonce()))throw new FuturesLedger.Failure("USER_NONCE_OR_EOA_CHANGED");}catch(java.io.IOException unavailable){throw new FuturesLedger.Failure("FUTURES_RPC_UNAVAILABLE");}
            });
            return new Runtime(chain,rpc,signer,new FuturesWorker(ledger,intents,market,rpc,reconciler,execution,store));
        }catch(Exception invalid){rpc.close();throw new IllegalStateException("AUTHORIZED_DEPLOYMENT_UNAVAILABLE");}
    }
    @Bean AuthorizedFuturesService authorizedFuturesService(AuthorizedFuturesStore store,FuturesLedger ledger,FuturesMarketGate market,Runtime runtime,Environment e){
        var access=new AuthorizedFuturesService.ChainAccess(){
            public void verify(){try{runtime.rpc.verifyDeployment(runtime.chain);}catch(java.io.IOException x){throw new FuturesLedger.Failure("FUTURES_RPC_UNAVAILABLE");}}
            public boolean eoa(String owner){try{return runtime.rpc.eoa(owner);}catch(java.io.IOException x){throw new FuturesLedger.Failure("FUTURES_RPC_UNAVAILABLE");}}
            public BigInteger nonce(String owner){try{return runtime.rpc.userNonce(owner);}catch(java.io.IOException x){throw new FuturesLedger.Failure("FUTURES_RPC_UNAVAILABLE");}}
        };
        return new AuthorizedFuturesService(store,ledger,market,runtime.chain,runtime.signer,access,runtime.worker::signal,e.getRequiredProperty("app.futures.user-authorization.wallet-origin"));
    }
}
