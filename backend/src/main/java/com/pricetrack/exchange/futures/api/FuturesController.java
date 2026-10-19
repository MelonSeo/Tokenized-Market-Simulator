package com.pricetrack.exchange.futures.api;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.Action;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.futures.read.FuturesReadService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/futures")
@ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled"},havingValue="true")
public class FuturesController {
    private final FuturesService service;private final FuturesReadService reads;
    public FuturesController(FuturesService service,FuturesReadService reads) { this.service=service;this.reads=reads; }
    public record PreviewRequest(@NotNull Action action,Side side,@Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,18})?") String marginAmount,int leverage,String positionId,long expectedVersion) {}
    public record OpenRequest(@NotBlank String previewId) {}
    public record CloseRequest(@NotBlank String previewId,@Min(0) long expectedVersion) {}
    public record MarginRequest(@NotBlank @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,18})?") String amount,@Min(0) long expectedVersion) {}
    @PostMapping("/previews") public Map<String,Object> preview(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody PreviewRequest r) {
        BigInteger margin=BigInteger.ZERO;
        if(r.action()==Action.OPEN) {
            if(r.marginAmount()==null)throw new com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure("INVALID_MARGIN");
            margin=TokenUnits.toWei(new BigDecimal(r.marginAmount()));
        }
        var p=service.preview(user.userId(),r.action(),r.positionId(),r.expectedVersion(),r.side(),r.leverage(),margin);
        var response=new LinkedHashMap<String,Object>();response.put("previewId",p.id());response.put("action",p.action());response.put("price",FuturesReadService.price(p.mark().price()));response.put("expiresAt",p.expiresAt());
        response.put("margin",FuturesReadService.amount(p.margin()));response.put("side",p.side());response.put("leverage",p.leverage());
        response.put("notice","DB 격리증거금 모의 선물 · 수수료/펀딩0 · 유한 손익 풀, 지급 보증 없음 · 서명 가격은 최대30초 내 체인 검증 가격이며 최신 화면 가격과 다를 수 있습니다.");return response;
    }
    @PostMapping("/positions") public ResponseEntity<FuturesReadService.OperationView> open(@AuthenticationPrincipal AuthenticatedUser u,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OpenRequest r) {
        var op=service.consume(u.userId(),r.previewId(),key,Action.OPEN,null,0);return ResponseEntity.accepted().body(reads.operation(u.userId(),op.id()));
    }
    @GetMapping("/positions") public List<FuturesReadService.PositionView> positions(@AuthenticationPrincipal AuthenticatedUser u) {return reads.positions(u.userId());}
    @GetMapping("/positions/{id}") public FuturesReadService.PositionView position(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id) {return reads.position(u.userId(),id);}
    @GetMapping("/operations/{id}") public FuturesReadService.OperationView operation(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id) {return reads.operation(u.userId(),id);}
    @PostMapping("/positions/{id}/margin") public ResponseEntity<FuturesReadService.OperationView> margin(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MarginRequest r) {
        var op=service.margin(u.userId(),id,key,r.expectedVersion(),TokenUnits.toWei(new BigDecimal(r.amount())));return ResponseEntity.accepted().body(reads.operation(u.userId(),op.id()));
    }
    @PostMapping("/positions/{id}/close") public ResponseEntity<FuturesReadService.OperationView> close(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CloseRequest r) {
        var op=service.consume(u.userId(),r.previewId(),key,Action.CLOSE,id,r.expectedVersion());return ResponseEntity.accepted().body(reads.operation(u.userId(),op.id()));
    }
}
