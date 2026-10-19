package com.pricetrack.exchange.futures.api;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.futures.audit.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** ADMIN-only observations. No repair, margin transfer, execution or baseline reset endpoint. */
@RestController @RequestMapping("/api/admin/futures/audits")
@ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled"},havingValue="true")
public class FuturesAuditController {
    private final FuturesAuditService audits;private final FuturesAuditStore store;
    public FuturesAuditController(FuturesAuditService audits,FuturesAuditStore store){this.audits=audits;this.store=store;}
    @PostMapping("/baseline") public ResponseEntity<FuturesAuditModels.Baseline> baseline(@AuthenticationPrincipal AuthenticatedUser actor){return ResponseEntity.status(HttpStatus.CREATED).body(audits.baseline(actor.userId()));}
    @GetMapping("/baseline") public ResponseEntity<FuturesAuditModels.Baseline> baseline(){return store.baseline().map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
    @PostMapping public FuturesAuditModels.Result run(@AuthenticationPrincipal AuthenticatedUser actor){return audits.run(actor.userId());}
    @GetMapping public List<FuturesAuditModels.Result> history(){return audits.history();}
    @GetMapping("/{id}") public FuturesAuditModels.Result detail(@PathVariable String id){return audits.detail(id);}
}
