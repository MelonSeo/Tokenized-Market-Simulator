package com.pricetrack.exchange.futures.api;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.futures.audit.*;
import com.pricetrack.exchange.user.UserRole;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Separate opt-in ADMIN route. Does not expose execution, repair or baseline overwrite endpoints. */
@RestController @RequestMapping("/api/admin/futures/authorized-audits")
@ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled","app.futures.audit.authorized-enabled"},havingValue="true")
public class FuturesAuthorizedAuditController {
    public record BaselineRequest(String targetKey){}
    private final FuturesAuthorizedAuditService service;
    public FuturesAuthorizedAuditController(FuturesAuthorizedAuditService service){this.service=service;}
    @GetMapping("/targets") public FuturesAuthorizedAuditService.Targets targets(@AuthenticationPrincipal AuthenticatedUser actor){admin(actor);return service.targets();}
    @GetMapping("/baselines") public List<FuturesAuditDeployment.Envelope> baselines(@AuthenticationPrincipal AuthenticatedUser actor){admin(actor);return service.baselines();}
    @PostMapping("/baselines") public ResponseEntity<FuturesAuditDeployment.Envelope> baseline(@AuthenticationPrincipal AuthenticatedUser actor,@RequestBody BaselineRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(service.baseline(request==null?null:request.targetKey(),admin(actor)));}
    @PostMapping public FuturesAuthorizedAuditObserver.Observation run(@AuthenticationPrincipal AuthenticatedUser actor){return service.run(admin(actor));}
    private static long admin(AuthenticatedUser actor){
        if(actor==null||actor.userId()==null||actor.userId()<=0||actor.role()!=UserRole.ADMIN)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"AUDIT_ADMIN_REQUIRED");
        return actor.userId();
    }
}
