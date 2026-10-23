package com.pricetrack.exchange.custody.direct;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.custody.auth.CWalletProofService;
import com.pricetrack.exchange.custody.runtime.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Owned receipt lookups only; no broadcast, automatic recovery or financial writes. */
@RestController
@RequestMapping("/api/c-futures/direct/transactions")
@ConditionalOnProperty(name="app.c-futures.direct.enabled",havingValue="true")
public final class CDirectReceiptController {
    private final CDirectReceiptReader receipts;private final CDirectService direct;private final CWalletProofService wallets;
    public CDirectReceiptController(CCustodyRpcReader reads,CCustodyRpcClient rpc,CDirectService direct,CWalletProofService wallets){this.receipts=new CDirectReceiptReader(reads,rpc);this.direct=direct;this.wallets=wallets;}
    @GetMapping("/{txHash}") public Map<String,Object> transaction(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable("txHash")String txHash,@RequestParam(value="intentId",required=false)String intentId){
        if(user==null||user.userId()==null||user.userId()<=0)throw new CCustodyFailure("C_AUTHENTICATION_REQUIRED",401);
        String owner=wallets.wallet(user.userId()).wallet();
        try{return receipts.inspect(owner,txHash,intentId==null?null:direct.findExecution(user.userId(),intentId));}
        catch(CCustodyFailure safe){throw safe;}catch(RuntimeException invalid){throw new CCustodyFailure("C_DIRECT_RECEIPT_REVIEW_REQUIRED",409);}
    }
    @ExceptionHandler(CCustodyFailure.class) ResponseEntity<Map<String,Object>> failure(CCustodyFailure f){
        return ResponseEntity.status(f.status()).body(Map.of("code",f.code(),"status","REVIEW_REQUIRED","scope",CDirectReceiptReader.SCOPE,"completeCoverage",false));
    }
}
