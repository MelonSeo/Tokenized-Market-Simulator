package com.pricetrack.exchange.custody.api;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.custody.auth.*;
import com.pricetrack.exchange.custody.runtime.*;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** JWT-owned read/proof routes only. No trading, funding, admin or arbitrary RPC endpoints. */
@RestController
@RequestMapping("/api/c-futures")
@ConditionalOnProperty(name="app.c-futures.enabled",havingValue="true")
public final class CCustodyController {
    public record WalletRequest(String wallet) {}
    public record LinkRequest(String challengeId,String signature) {}
    private final CCustodyRpcReader reads;private final CWalletProofService proof;
    @Value("${app.c-futures.direct.enabled:false}") private boolean directEnabled;
    public CCustodyController(CCustodyRpcReader reads,CWalletProofService proof){this.reads=reads;this.proof=proof;}
    @GetMapping("/config") public CCustodyRpcReader.Observation<Map<String,String>> config(@AuthenticationPrincipal AuthenticatedUser u){
        uid(u);var observed=reads.config();var config=new LinkedHashMap<>(observed.value());
        config.put("readOnly",Boolean.toString(!directEnabled));
        return new CCustodyRpcReader.Observation<>(observed.deploymentId(),observed.block(),observed.observedAt(),Map.copyOf(config));
    }
    @PostMapping("/wallet/challenges") public CWalletProofService.ChallengeView challenge(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader(value="Origin",required=false)String origin,@RequestBody WalletRequest r){long id=uid(u);if(r==null)throw invalid();return proof.challenge(id,r.wallet(),origin);}
    @PostMapping("/wallet/link") public CWalletLinkStore.Link link(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader(value="Origin",required=false)String origin,@RequestBody LinkRequest r){long id=uid(u);if(r==null)throw invalid();return proof.link(id,r.challengeId(),r.signature(),origin);}
    @GetMapping("/wallet") public CWalletLinkStore.Link wallet(@AuthenticationPrincipal AuthenticatedUser u){return proof.wallet(uid(u));}
    @GetMapping("/account") public CCustodyRpcReader.Observation<CCustodyRpcReader.Account> account(@AuthenticationPrincipal AuthenticatedUser u){return reads.account(proof.wallet(uid(u)).wallet());}
    @GetMapping("/positions/{id}") public CCustodyRpcReader.Observation<CCustodyRpcReader.PositionView> position(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id){return reads.position(proof.wallet(uid(u)).wallet(),id);}
    @GetMapping("/claims/{id}") public CCustodyRpcReader.Observation<CCustodyRpcReader.ClaimView> claim(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id){return reads.claim(proof.wallet(uid(u)).wallet(),id);}
    @ExceptionHandler(CCustodyFailure.class) public ResponseEntity<Map<String,String>> failure(CCustodyFailure f){return ResponseEntity.status(f.status()).body(Map.of("code",f.code(),"scope","C_READ_WALLET_ONLY"));}
    private static long uid(AuthenticatedUser user){if(user==null||user.userId()==null||user.userId()<=0)throw new CCustodyFailure("C_AUTHENTICATION_REQUIRED",401);return user.userId();}
    private static CCustodyFailure invalid(){return new CCustodyFailure("C_INVALID_REQUEST",400);}
}
