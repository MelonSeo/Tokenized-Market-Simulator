package com.pricetrack.exchange.futures.authorization;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.futures.read.FuturesReadService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/futures/authorized-v2")
@ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled","app.futures.user-authorization-v2.enabled"},havingValue="true")
public class AuthorizedV2Controller {
    private final AuthorizedV2Config.V2Runtime runtime;private final AuthorizedFuturesStore common;private final FuturesReadService reads;
    public AuthorizedV2Controller(AuthorizedV2Config.V2Runtime r,AuthorizedFuturesStore c,FuturesReadService reads){runtime=r;common=c;this.reads=reads;}
    @GetMapping("/config") public Map<String,Object> config(){var c=runtime.core.chain;return Map.of("enabled",true,"chainId",c.chainId().toString(),"controller",c.engine(),"rules",c.rules(),"relayer",c.executor(),"symbolHash",com.pricetrack.exchange.futures.chain.FuturesReport.SYMBOL_HASH,"policyHash",com.pricetrack.exchange.futures.ledger.FuturesLedger.POLICY_HASH,"maxUserTtlSeconds","120","custody","POSTGRESQL_MOCK_COLLATERAL","auditCoverage","INCONCLUSIVE");}
    @PostMapping("/wallet/challenges") public AuthorizedFuturesStore.Challenge challenge(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader("Origin")String origin,@Valid @RequestBody AuthorizedFuturesController.WalletRequest r){return runtime.proof.challenge(u.userId(),r.wallet(),origin);}
    @PostMapping("/wallet/link") public Map<String,String> link(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader("Origin")String origin,@Valid @RequestBody AuthorizedFuturesController.LinkRequest r){runtime.proof.link(u.userId(),r.challengeId(),r.signature(),origin);return Map.of("wallet",common.wallet(u.userId()));}
    @GetMapping("/wallet") public Map<String,String> wallet(@AuthenticationPrincipal AuthenticatedUser u){return Map.of("wallet",common.wallet(u.userId()));}
    @GetMapping("/nonce") public Map<String,Object> nonce(@AuthenticationPrincipal AuthenticatedUser u){return runtime.service.nonce(u.userId());}
    @PostMapping("/drafts") public AuthorizedV2Service.DraftView draft(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody AuthorizedV2Service.Request r){return runtime.service.draft(u.userId(),key,r);}
    @PostMapping("/drafts/{id}/submit") public ResponseEntity<FuturesReadService.OperationView> submit(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody AuthorizedFuturesController.SubmitRequest r){var op=runtime.service.submit(u.userId(),id,key,r.signature());return ResponseEntity.accepted().body(reads.operation(u.userId(),op.id()));}
    @GetMapping("/positions/{id}") public Map<String,Object> position(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id){var a=common.position(id,u.userId());if(!a.chain().equals(runtime.core.chain))throw new com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure("FUTURES_CONTRACT_VERSION_CONFLICT");var p=reads.position(u.userId(),id);return Map.of("version","USER_AUTHORIZED_V2","owner",a.owner(),"controller",a.chain().engine(),"rules",a.chain().rules(),"positionVersion",Long.toString(p.version()),"callEpoch",Long.toString(p.callEpoch()),"position",p);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_AUTHORIZED_V2_REQUEST"));}
}
