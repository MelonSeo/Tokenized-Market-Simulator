package com.pricetrack.exchange.custody.direct;

import com.pricetrack.exchange.custody.runtime.CCustodyFailure;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.junit.jupiter.api.Assertions.*;

/** Direct controller contract only; JWT filter and HTTP transport require the separate live Gate. */
public class CDirectControllerTest {
    @Test void everyRouteRejectsMissingJwtPrincipalBeforeServiceOrStorage(){var api=new CDirectController(null);for(Runnable r:List.<Runnable>of(()->api.draft(null,"example-key",CDirectServiceTest.request()),()->api.intent(null,CDirectServiceTest.h(1)),()->api.execution(null,CDirectServiceTest.h(1)),()->api.execute(null,CDirectServiceTest.h(1))))assertEquals(401,assertThrows(CCustodyFailure.class,r::run).status());}
    @Test void featureIsExplicitOptInAndRequestNumbersAreStrings(){var flag=CDirectController.class.getAnnotation(ConditionalOnProperty.class);assertEquals("app.c-futures.direct.enabled",flag.name()[0]);assertEquals("true",flag.havingValue());assertFalse(flag.matchIfMissing());assertArrayEquals(new String[]{"/api/c-futures/direct"},CDirectController.class.getAnnotation(RequestMapping.class).value());for(var field:CDirectModels.Request.class.getRecordComponents())assertEquals(String.class,field.getType());var runtime=CDirectRuntimeConfig.class.getAnnotation(ConditionalOnProperty.class);assertFalse(runtime.matchIfMissing());}
    @Test void failureDtosHaveFixedSafeCodesAndNoProviderExceptionDetails(){var api=new CDirectController(null);var failure=api.failure(new CCustodyFailure("C_DIRECT_COMMIT_UNKNOWN",503));assertEquals(503,failure.getStatusCode().value());assertEquals(Set.of("code","scope"),failure.getBody().keySet());assertEquals("C_DIRECT_COMMIT_UNKNOWN",failure.getBody().get("code"));assertEquals("C_DIRECT_PRICE_CHANGED",api.market(new FuturesLedger.Failure("PRICE_CHANGED")).getBody().get("code"));assertEquals("C_DIRECT_MARKET_UNAVAILABLE",api.market(new FuturesLedger.Failure("private provider detail")).getBody().get("code"));}
}
