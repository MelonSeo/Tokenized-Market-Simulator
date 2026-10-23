package com.pricetrack.exchange.futures.authorization;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.Action;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.read.FuturesReadService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/futures/authorized")
@ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled","app.futures.user-authorization.enabled"},havingValue="true")
public class AuthorizedFuturesController {
    private final AuthorizedFuturesService service;private final AuthorizedFuturesStore store;private final FuturesReadService reads;
    public AuthorizedFuturesController(AuthorizedFuturesService service,AuthorizedFuturesStore store,FuturesReadService reads){this.service=service;this.store=store;this.reads=reads;}
    public record WalletRequest(@NotBlank @Pattern(regexp="0x[0-9a-fA-F]{40}") String wallet) {}
    public record LinkRequest(@NotBlank @Size(max=36) String challengeId,@NotBlank @Pattern(regexp="0x[0-9a-fA-F]{130}") String signature) { @Override public String toString(){return "LinkRequest[redacted]";} }
    public record DraftRequest(@NotNull Action action,@Size(max=36) String positionId,@Min(0) long expectedVersion,Side side,int leverage,@Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,18})?") String marginAmount) {}
    public record SubmitRequest(@NotBlank @Pattern(regexp="0x[0-9a-fA-F]{130}") String signature){@Override public String toString(){return "SubmitRequest[redacted]";}}
    @PostMapping("/wallet/challenges") public AuthorizedFuturesStore.Challenge challenge(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader("Origin") String origin,@Valid @RequestBody WalletRequest r){return service.challenge(u.userId(),r.wallet(),origin);}
    @PostMapping("/wallet/link") public Map<String,String> link(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader("Origin") String origin,@Valid @RequestBody LinkRequest r){service.link(u.userId(),r.challengeId(),r.signature(),origin);return Map.of("wallet",store.wallet(u.userId()));}
    @GetMapping("/wallet") public Map<String,String> wallet(@AuthenticationPrincipal AuthenticatedUser u){return Map.of("wallet",store.wallet(u.userId()));}
    @PostMapping("/drafts") public AuthorizedFuturesService.DraftView draft(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DraftRequest r){return service.draft(u.userId(),key,new AuthorizedFuturesService.Request(r.action(),r.positionId(),r.expectedVersion(),r.side(),r.leverage(),r.marginAmount()==null?BigInteger.ZERO:TokenUnits.toWei(new BigDecimal(r.marginAmount()))));}
    @PostMapping("/drafts/{id}/submit") public ResponseEntity<FuturesReadService.OperationView> submit(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody SubmitRequest r){var op=service.submit(u.userId(),id,key,r.signature());return ResponseEntity.accepted().body(reads.operation(u.userId(),op.id()));}
    @GetMapping("/positions/{id}") public Map<String,Object> position(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id){var a=store.position(id,u.userId());if(a.chain().v2())throw new com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure("FUTURES_CONTRACT_VERSION_CONFLICT");return Map.of("version","USER_AUTHORIZED_V1","owner",a.owner(),"controller",a.chain().engine(),"rules",a.chain().rules(),"position",reads.position(u.userId(),id));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_AUTHORIZED_REQUEST"));}
}
