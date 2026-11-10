package com.pricetrack.exchange.ai.provider;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@RestController @ConditionalOnProperty(name="app.ai.enabled",havingValue="true")
public class ProviderBudgetController {
    private final PgProviderBudget budget;
    public ProviderBudgetController(PgProviderBudget budget){this.budget=budget;}
    @PostMapping("/api/ai/provider-budget/initialize") public java.util.Map<String,String> initialize(@AuthenticationPrincipal AuthenticatedUser user){
        if(user==null||user.role()!=UserRole.ADMIN)throw new org.springframework.security.access.AccessDeniedException("ADMIN required");
        budget.initialize();return java.util.Map.of("status","INITIALIZED_NOT_PAID_CALL_AUTHORIZATION");
    }
    @ExceptionHandler(com.pricetrack.exchange.ai.AiFailure.class) public org.springframework.http.ResponseEntity<java.util.Map<String,String>> failure(com.pricetrack.exchange.ai.AiFailure e){return org.springframework.http.ResponseEntity.status(503).body(java.util.Map.of("code",e.code()));}
}
