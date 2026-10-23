package com.pricetrack.exchange.futures.authorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
/** Common proof/link store; no workers, keys or RPC. V1/V2 services remain separately opt-in. */
@Configuration @ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled"},havingValue="true")
public class AuthorizedWalletConfig {
    @Bean AuthorizedFuturesStore authorizedFuturesStore(DataSource ds,FuturesLedger ledger,ObjectMapper mapper){return new AuthorizedFuturesStore(ds,ledger,mapper);}
}
