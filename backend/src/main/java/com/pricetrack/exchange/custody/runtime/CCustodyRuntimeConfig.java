package com.pricetrack.exchange.custody.runtime;

import com.pricetrack.exchange.custody.auth.*;
import java.math.BigInteger;
import java.net.URI;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Default OFF, keyless C read/wallet only. Never installs schema or starts a worker. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.c-futures.enabled",havingValue="true")
public class CCustodyRuntimeConfig {
    @Bean("cCustodyClock") Clock cCustodyClock(){return Clock.systemUTC();}
    @Bean CCustodyIdentity cCustodyIdentity(Environment e){
        try{return new CCustodyIdentity(new BigInteger(required(e,"chain-id")),required(e,"genesis-hash"),
            contract(e,"token"),contract(e,"controller"),contract(e,"rules"),contract(e,"vault"),required(e,"relayer"),required(e,"oracle"),required(e,"pause-admin"),required(e,"symbol-hash"),required(e,"policy-hash"),required(e,"origin"));}
        catch(RuntimeException invalid){throw new IllegalArgumentException("C_CONFIG_IDENTITY_INVALID");}
    }
    @Bean(destroyMethod="close") CCustodyRpcClient cCustodyRpcClient(Environment e){
        try{return new CCustodyRpcClient(URI.create(required(e,"rpc-uri")));}catch(RuntimeException invalid){throw new IllegalArgumentException("C_CONFIG_RPC_INVALID");}
    }
    @Bean CCustodyRpcReader cCustodyRpcReader(CCustodyIdentity i,CCustodyRpcClient rpc,@Qualifier("cCustodyClock")Clock c){return new CCustodyRpcReader(i,rpc,c);}
    @Bean CWalletLinkStore cWalletLinkStore(DataSource ds,CCustodyIdentity i,@Qualifier("cCustodyClock")Clock c){return new CWalletLinkStore(ds,i,c);}
    @Bean CWalletProofService cWalletProofService(CCustodyIdentity i,CWalletLinkStore store,CCustodyRpcReader reader,@Qualifier("cCustodyClock")Clock c){return new CWalletProofService(i,store,owner->reader.eoa(owner),c);}
    private static CCustodyIdentity.Contract contract(Environment e,String name){return new CCustodyIdentity.Contract(required(e,name),required(e,name+"-code-hash"));}
    private static String required(Environment e,String name){String v=e.getProperty("app.c-futures."+name);if(v==null||v.isBlank())throw new IllegalArgumentException("C_CONFIG_REQUIRED");return v;}
}
