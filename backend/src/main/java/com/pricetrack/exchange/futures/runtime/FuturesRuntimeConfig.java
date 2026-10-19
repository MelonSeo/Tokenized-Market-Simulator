package com.pricetrack.exchange.futures.runtime;

import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.market.MarketPriceService;
import java.math.BigInteger;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.http.HttpService;

/** Explicit OFF by default. Core-only test fixtures do not start workers, import keys, seed assets or connect to RPC. */
@Configuration
@ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled"},havingValue="true")
public class FuturesRuntimeConfig {
    @Bean FuturesMarketGate futuresMarketGate(MarketPriceService market) { return new FuturesMarketGate(market,Clock.systemUTC()); }
    @Bean FuturesIntentStore futuresIntentStore(DataSource ds,FuturesLedger ledger) { return new FuturesIntentStore(ds,ledger); }
    /** Keys are deliberately not separate public beans and never included in exception messages. */
    private Credentials key(Environment e,String name) {
        String raw=e.getProperty(name,"");
        if(!raw.matches("(?:0x)?[0-9a-fA-F]{64}"))throw new IllegalStateException("FUTURES_KEYS_REQUIRED");
        try { var c=Credentials.create(raw);if(c.getEcKeyPair().getPrivateKey().signum()==0)throw new IllegalArgumentException();return c; }
        catch(RuntimeException invalid) { throw new IllegalStateException("FUTURES_KEYS_INVALID"); }
    }
    @Bean FuturesChainIdentity futuresChainIdentity(Environment e) {
        var executor=key(e,"app.futures.executor-private-key");var signer=key(e,"app.futures.signer-private-key");
        for(String name:new String[]{"app.blockchain.operator-private-key","app.blockchain.price-report.signer-private-key"}) {
            String raw=e.getProperty(name,"");if(raw.isBlank())continue;
            var existing=key(e,name);
            if(existing.getAddress().equalsIgnoreCase(executor.getAddress()) || existing.getAddress().equalsIgnoreCase(signer.getAddress()))
                throw new IllegalStateException("FUTURES_KEYS_MUST_BE_DEDICATED");
        }
        return new FuturesChainIdentity(new BigInteger(e.getRequiredProperty("app.futures.chain-id")),e.getRequiredProperty("app.futures.engine-address"),executor.getAddress(),signer.getAddress());
    }
    @Bean(destroyMethod="close") FuturesRpcGateway futuresRpcGateway(Environment e,FuturesChainIdentity chain) throws java.io.IOException {
        // Dedicated timeouts bound worker shutdown/RPC stalls, without altering the existing spot HTTP client.
        var client=new okhttp3.OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(3)).build();
        var rpc=new FuturesRpcGateway(new HttpService(e.getRequiredProperty("app.futures.rpc-url"),client,false),chain,e.getRequiredProperty("app.futures.code-hash"));
        try {rpc.verifyDeployment(chain);return rpc;}catch(Exception failure){rpc.close();throw new IllegalStateException("FUTURES_DEPLOYMENT_UNAVAILABLE");}
    }
    @Bean FuturesTransactionSender futuresTransactionSender(Environment e,FuturesChainIdentity chain,FuturesTransactionStore store,FuturesRpcGateway rpc) {
        return new FuturesTransactionSender(chain,key(e,"app.futures.executor-private-key"),store,rpc);
    }
    @Bean FuturesReconciler futuresReconciler(Environment e,FuturesChainIdentity chain,FuturesRpcGateway rpc,FuturesTransactionStore store,FuturesTransactionSender sender,FuturesLedger ledger) {
        return new FuturesReconciler(chain,rpc,store,sender,ledger,e.getProperty("app.futures.confirmations",Integer.class,1));
    }
    @Bean FuturesExecution futuresExecution(Environment e,FuturesLedger ledger,FuturesIntentStore intents,FuturesMarketGate market,FuturesChainIdentity chain,FuturesTransactionStore store,FuturesTransactionSender sender) {
        return new FuturesExecution(ledger,intents,market,chain,key(e,"app.futures.signer-private-key"),store,sender);
    }
    @Bean(initMethod="start",destroyMethod="close") FuturesWorker futuresWorker(FuturesLedger ledger,FuturesIntentStore intents,FuturesMarketGate market,FuturesRpcGateway rpc,FuturesReconciler reconciler,FuturesExecution execution,FuturesTransactionStore store) {
        return new FuturesWorker(ledger,intents,market,rpc,reconciler,execution,store);
    }
    @Bean FuturesPriceListener futuresPriceListener(FuturesWorker worker) { return new FuturesPriceListener(worker); }
    @Bean FuturesService futuresService(FuturesIntentStore intents,FuturesMarketGate market,FuturesWorker worker,FuturesLedger ledger) { return new FuturesService(intents,market,worker,ledger); }
    @Bean com.pricetrack.exchange.futures.read.FuturesReadService futuresReadService(DataSource ds,FuturesLedger ledger,FuturesMarketGate market) { return new com.pricetrack.exchange.futures.read.FuturesReadService(ds,ledger,market); }
}
