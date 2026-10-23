package com.pricetrack.exchange.custody.direct;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.common.exception.InvalidTokenException;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.user.UserRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CDirectMarketControllerTest {
    private final AtomicInteger reads = new AtomicInteger();
    private final MarketPriceSnapshot snapshot = new MarketPriceSnapshot("mSEC",
            new BigDecimal("9007199254740993.12345678"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            MarketStatus.UNKNOWN, PriceStatus.SIMULATED, "SIMULATED", Instant.parse("2026-10-24T00:00:00Z"));
    private final MarketPriceService prices = new MarketPriceService(() -> { reads.incrementAndGet(); return snapshot; });
    private final AuthenticatedUser user = new AuthenticatedUser(7L, "fixture-user", UserRole.USER);
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withBean(MarketPriceService.class, () -> prices)
                .withUserConfiguration(CDirectMarketController.class);
    }
    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }

    @Test void defaultOffAndUnknownDirectValuesDoNotInstallRouteOrReadProvider() {
        for (String value : new String[] {"", "false", "unknown"}) {
            var runner = context();
            if (!value.isEmpty()) runner = runner.withPropertyValues("app.c-futures.direct.enabled=" + value);
            runner.run(c -> { assertNull(c.getStartupFailure()); assertEquals(0,c.getBeansOfType(CDirectMarketController.class).size()); });
        }
        assertEquals(0, reads.get());
    }
    @Test void explicitDirectOptInAndExplicitOrMissingDemoFalseInstallExactlyOneController() {
        for (String demo : new String[] {"", "false"}) {
            var runner = context().withPropertyValues("app.c-futures.direct.enabled=true");
            if (!demo.isEmpty()) runner = runner.withPropertyValues("app.c-demo.enabled=" + demo);
            runner.run(c -> { assertNull(c.getStartupFailure()); assertEquals(1,c.getBeansOfType(CDirectMarketController.class).size()); });
        }
        assertEquals(0, reads.get());
    }
    @Test void demoTrueAndUnknownValuesExcludeProductRouteWithoutDependingOnDemoClasses() {
        for (String demo : new String[] {"true", "unknown", ""}) {
            context().withPropertyValues("app.c-futures.direct.enabled=true", "app.c-demo.enabled=" + demo)
                .run(c -> { assertNull(c.getStartupFailure()); assertEquals(0,c.getBeansOfType(CDirectMarketController.class).size()); });
        }
        assertEquals(0, reads.get());
    }
    @Test void directCallsRejectMissingAndNonpositiveUidBeforeAnyMarketRead() {
        var controller = new CDirectMarketController(prices);
        assertThrows(InvalidTokenException.class, () -> controller.market(null));
        for (Long id : new Long[] {null, 0L, -1L}) {
            assertThrows(InvalidTokenException.class, () -> controller.market(new AuthenticatedUser(id, null, UserRole.USER)));
        }
        assertEquals(0, reads.get());
    }
    @Test void exactSixFieldReferenceResponseMatchesDemoContractWithoutPriceRoundingOrInventedEvidence() {
        var response = new CDirectMarketController(prices).market(user);
        assertEquals(Map.of("symbol","mSEC","price","9007199254740993.12345678","provider","SIMULATED",
                "observedAt","2026-10-24T00:00:00Z","marketStatus","UNKNOWN","priceStatus","SIMULATED"), response);
        assertEquals(1, reads.get());
    }
    @Test void standaloneMockMvcResolvesSecurityPrincipalAndAllowsOnlyReferenceGet() throws Exception {
        // Actual JWT/filter chain and servlet container are deliberately outside this standalone test.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
        var mvc = MockMvcBuilders.standaloneSetup(new CDirectMarketController(prices))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/api/c-futures/direct/market")).andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value("9007199254740993.12345678"))
                .andExpect(jsonPath("$.provider").value("SIMULATED"))
                .andExpect(jsonPath("$.observedAt").value("2026-10-24T00:00:00Z"));
        mvc.perform(post("/api/c-futures/direct/market")).andExpect(status().isMethodNotAllowed());
        assertEquals(1, reads.get());
    }
    @Test void providerFailureIsNotReplacedWithFakeReferenceOrExecutionSuccess() {
        var failure = new IllegalStateException("fixture-provider-unavailable");
        var controller = new CDirectMarketController(new MarketPriceService(() -> { throw failure; }));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> controller.market(user)));
    }
}
