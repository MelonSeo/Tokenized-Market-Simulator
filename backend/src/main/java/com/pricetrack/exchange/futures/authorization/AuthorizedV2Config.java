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

@Configuration @ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled","app.futures.user-authorization-v2.enabled"},havingValue="true")
public class AuthorizedV2Config {
    static final String PREFIX="app.futures.user-authorization-v2.";
    public static void recheckBeforeSigning(FuturesChainIdentity c,FuturesMarketGate market,FuturesTransactionStore tx,FuturesRpcGateway rpc,String id){
        var a=tx.authorization(id);if(a==null||!a.chain().equals(c))throw new FuturesLedger.Failure("AUTHORIZATION_IDENTITY_CONFLICT");
        if(a.intentV2()!=null){UserIntentV2Codec.time(a.intentV2(),market.now().getEpochSecond());try{if(!rpc.eoa(a.owner())||!rpc.userNonce(a.owner()).equals(a.intentV2().nonce()))throw new FuturesLedger.Failure("USER_NONCE_OR_EOA_CHANGED");}catch(java.io.IOException x){throw FuturesDiagnostics.unavailable(FuturesDiagnostics.Stage.PRE_SIGN_RECHECK,x);}}
    }
    @Bean AuthorizedV2Store authorizedV2Store(DataSource ds,FuturesLedger l,AuthorizedFuturesStore s,ObjectMapper j){return new AuthorizedV2Store(ds,l,s,j);}
    @Bean AuthorizedV2RevisionStore authorizedV2RevisionStore(DataSource ds,FuturesLedger l,AuthorizedFuturesStore s,ObjectMapper j,FuturesMarketGate m){return new AuthorizedV2RevisionStore(ds,l,s,j,m);}
    public static final class V2Runtime implements AutoCloseable {
        final AuthorizedFuturesConfig.Runtime core;final AuthorizedFuturesService proof;final AuthorizedV2Service service;
        V2Runtime(AuthorizedFuturesConfig.Runtime core,AuthorizedFuturesService proof,AuthorizedV2Service service){this.core=core;this.proof=proof;this.service=service;}
        public void start(){core.start();}public void close(){core.close();}
        public FuturesChainIdentity auditIdentity(){return core.auditIdentity();}
        public FuturesRpcGateway auditRpc(){return core.auditRpc();}
    }
    private Credentials key(Environment e,String name){try{String raw=e.getRequiredProperty(name);if(!raw.matches("(?:0x)?[0-9a-fA-F]{64}"))throw new IllegalArgumentException();var k=Credentials.create(raw);if(k.getEcKeyPair().getPrivateKey().signum()==0)throw new IllegalArgumentException();return k;}catch(Exception x){throw new IllegalStateException("AUTHORIZED_V2_KEYS_REQUIRED");}}
    @Bean(initMethod="start",destroyMethod="close") V2Runtime authorizedV2Runtime(Environment e,DataSource ds,FuturesLedger ledger,FuturesMarketGate market,FuturesTransactionStore tx,AuthorizedFuturesStore common,AuthorizedV2Store store,AuthorizedV2RevisionStore revisions){
        var executor=key(e,PREFIX+"executor-private-key");var signer=key(e,PREFIX+"signer-private-key");
        for(String name:new String[]{"app.futures.executor-private-key","app.futures.signer-private-key","app.futures.user-authorization.executor-private-key","app.futures.user-authorization.signer-private-key","app.blockchain.operator-private-key","app.blockchain.price-report.signer-private-key"}){
            if(e.getProperty(name,"").isBlank())continue;var existing=key(e,name);if(existing.getAddress().equals(executor.getAddress())||existing.getAddress().equals(signer.getAddress()))throw new IllegalStateException("AUTHORIZED_V2_KEYS_MUST_BE_DEDICATED");
        }
        var c=new FuturesChainIdentity(new BigInteger(e.getRequiredProperty(PREFIX+"chain-id")),e.getRequiredProperty(PREFIX+"controller-address"),executor.getAddress(),signer.getAddress(),e.getRequiredProperty(PREFIX+"rules-address"),"2");
        var http=FuturesRpcHttp.client();
        var rpc=new FuturesRpcGateway(new HttpService(e.getRequiredProperty(PREFIX+"rpc-url"),http,false),c,e.getRequiredProperty(PREFIX+"controller-code-hash"),e.getRequiredProperty(PREFIX+"rules-code-hash"));
        try{
            rpc.verifyDeployment(c);var intents=new AuthorizedV2IntentStore(ds,ledger,store,c);var sender=new FuturesTransactionSender(c,executor,tx,rpc);
            var reconciler=new FuturesReconciler(c,rpc,tx,sender,ledger,e.getProperty(PREFIX+"confirmations",Integer.class,1));
            var repricing=new AuthorizedV2Repricing(revisions,tx,market,c,signer,id->recheckBeforeSigning(c,market,tx,rpc,id));
            var execution=new FuturesExecution(ledger,intents,market,c,signer,tx,sender,id->recheckBeforeSigning(c,market,tx,rpc,id),repricing::reprice);
            var worker=new FuturesWorker(ledger,intents,market,rpc,reconciler,execution,tx);
            var core=new AuthorizedFuturesConfig.Runtime(c,rpc,signer,worker);
            var access=new AuthorizedFuturesService.ChainAccess(){public void verify(){try{FuturesDiagnostics.io(FuturesDiagnostics.Stage.DEPLOYMENT,()->{rpc.verifyDeployment(c);return null;});}catch(java.io.IOException x){throw FuturesDiagnostics.unavailable(FuturesDiagnostics.Stage.DEPLOYMENT,x);}}public boolean eoa(String owner){try{return FuturesDiagnostics.io(FuturesDiagnostics.Stage.EOA,()->rpc.eoa(owner));}catch(java.io.IOException x){throw FuturesDiagnostics.unavailable(FuturesDiagnostics.Stage.EOA,x);}}public BigInteger nonce(String owner){try{return FuturesDiagnostics.io(FuturesDiagnostics.Stage.USER_NONCE,()->rpc.userNonce(owner));}catch(java.io.IOException x){throw FuturesDiagnostics.unavailable(FuturesDiagnostics.Stage.USER_NONCE,x);}}};
            // Reuse ONLY the proof/link methods. No V1 trading API/service bean is activated here.
            var proof=new AuthorizedFuturesService(common,ledger,market,c,signer,access,worker::signal,e.getRequiredProperty(PREFIX+"wallet-origin"));
            return new V2Runtime(core,proof,new AuthorizedV2Service(store,common,ledger,market,c,signer,access,worker::signal));
        }catch(Exception x){rpc.close();throw new IllegalStateException("AUTHORIZED_V2_DEPLOYMENT_UNAVAILABLE");}
    }
}
