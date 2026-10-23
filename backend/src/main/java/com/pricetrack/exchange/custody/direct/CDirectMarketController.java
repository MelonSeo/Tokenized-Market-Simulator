package com.pricetrack.exchange.custody.direct;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.common.exception.InvalidTokenException;
import com.pricetrack.exchange.market.MarketPriceService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Full-app reference read only. Execution pricing/signing remains in the C direct service. */
@RestController
@ConditionalOnProperty(name = "app.c-futures.direct.enabled", havingValue = "true")
@Conditional(CDirectMarketController.NotDemo.class)
public final class CDirectMarketController {
    private final MarketPriceService market;

    public CDirectMarketController(MarketPriceService market) { this.market = market; }

    @GetMapping("/api/c-futures/direct/market")
    public Map<String, String> market(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null || user.userId() == null || user.userId() <= 0) throw new InvalidTokenException();
        var snapshot = market.current();
        return Map.of("symbol", snapshot.symbol(), "price", snapshot.price().toPlainString(),
                "provider", snapshot.provider(), "observedAt", snapshot.observedAt().toString(),
                "marketStatus", snapshot.marketStatus().name(), "priceStatus", snapshot.priceStatus().name());
    }

    /** DemoController already owns this route; unknown demo values also fail closed. */
    static final class NotDemo implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return "false".equals(context.getEnvironment().getProperty("app.c-demo.enabled", "false"));
        }
    }
}
