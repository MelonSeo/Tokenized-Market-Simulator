package com.pricetrack.exchange.custody.direct;

import java.nio.file.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;

/** Explicit local-demo activation, internal signer only; no private-key property. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.c-futures.direct.enabled",havingValue="true")
public class CDirectOracleConfig {
    @Bean(destroyMethod="close") CDirectOracleClient cDirectOracleClient(@Value("${app.c-futures.direct.oracle-token-file}")String file){
        try{return new CDirectOracleClient(Files.readString(Path.of(file)).trim());}
        catch(Exception ignored){throw new IllegalArgumentException("C_DIRECT_ORACLE_CONFIG");}
    }
}
