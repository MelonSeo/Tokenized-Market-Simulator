package com.pricetrack.exchange.custody.direct;

import com.pricetrack.exchange.custody.authorization.*;
import com.pricetrack.exchange.custody.auth.CWalletProofService;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.market.MarketPriceService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Explicit opt-in, no schema installation, private user/relayer key or worker. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.c-futures.direct.enabled",havingValue="true")
public class CDirectRuntimeConfig {
    @Bean CConsentStore cDirectConsentStore(DataSource ds,CCustodyIdentity i,@Qualifier("cCustodyClock")Clock c){return new CConsentStore(ds,i,c);}
    @Bean CDirectExecutionStore cDirectExecutionStore(DataSource ds,CCustodyIdentity i,@Qualifier("cCustodyClock")Clock c){return new CDirectExecutionStore(ds,i,c);}
    @Bean CReportCandidateBuilder cDirectReportCandidateBuilder(CCustodyRpcReader r,MarketPriceService m,@Qualifier("cCustodyClock")Clock c){return new CReportCandidateBuilder(r,m,c,"mSEC");}
    @Bean CDirectService cDirectService(CConsentStore d,CDirectExecutionStore x,CWalletProofService p,CCustodyRpcReader r,CReportCandidateBuilder b,@Qualifier("cCustodyClock")Clock c,CDirectOraclePort o){return new CDirectService(d,x,p,r,b,c,o);}
}
