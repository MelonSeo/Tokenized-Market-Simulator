package com.pricetrack.exchange.custody.direct;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.custody.direct.CDirectModels.*;
import com.pricetrack.exchange.custody.runtime.CCustodyFailure;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/c-futures/direct")
@ConditionalOnProperty(name="app.c-futures.direct.enabled",havingValue="true")
public final class CDirectController {
    private final CDirectService service;
    public CDirectController(CDirectService service){this.service=service;}
    @PostMapping("/intents") public DraftView draft(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader("Idempotency-Key")String key,@RequestBody Request r){return service.draft(uid(u),key,r);}
    @GetMapping("/intents/{id}") public DraftView intent(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id){return service.findIntent(uid(u),id);}
    @PostMapping("/intents/{id}/execution") public ExecutionView execute(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id){return service.execute(uid(u),id);}
    @GetMapping("/intents/{id}/execution") public ExecutionView execution(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id){return service.findExecution(uid(u),id);}
    @ExceptionHandler(CCustodyFailure.class) public ResponseEntity<Map<String,String>> failure(CCustodyFailure f){return ResponseEntity.status(f.status()).body(Map.of("code",f.code(),"scope","OWNER_DIRECT_PREPARATION_ONLY_NO_SERVER_BROADCAST"));}
    @ExceptionHandler(com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure.class) public ResponseEntity<Map<String,String>> market(com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure f){
        String code=java.util.Set.of("PRICE_CHANGED","MARKET_CONNECTION_CHANGED","MARKET_UNAVAILABLE","INVALID_MARK_PRICE").contains(f.getMessage())?"C_DIRECT_"+f.getMessage():"C_DIRECT_MARKET_UNAVAILABLE";
        return ResponseEntity.status(code.equals("C_DIRECT_PRICE_CHANGED")?409:503).body(Map.of("code",code,"scope","FIXED_DIRECT_EXECUTION_NOT_AUTOMATICALLY_REPRICED"));
    }
    private static long uid(AuthenticatedUser u){if(u==null||u.userId()==null||u.userId()<=0)throw CDirectExecutionStore.fail("AUTHENTICATION_REQUIRED",401);return u.userId();}
}
