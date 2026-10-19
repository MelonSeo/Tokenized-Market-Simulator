package com.pricetrack.exchange.futures.api;

import com.pricetrack.exchange.futures.read.FuturesReadService;
import com.pricetrack.exchange.futures.runtime.FuturesWorker;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** ADMIN observe only. No repair, unlock, arbitrary price, liquidation or house-refill endpoints. */
@RestController @RequestMapping("/api/admin/futures")
@ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled"},havingValue="true")
public class FuturesAdminController {
    private final FuturesReadService reads;private final FuturesWorker worker;
    public FuturesAdminController(FuturesReadService reads,FuturesWorker worker) {this.reads=reads;this.worker=worker;}
    @GetMapping("/positions") public List<FuturesReadService.PositionView> positions(@RequestParam long userId) {return reads.adminPositions(userId);}
    @GetMapping("/operations/{id}") public FuturesReadService.OperationView operation(@RequestParam long userId,@PathVariable String id) {return reads.operation(userId,id);}
    @GetMapping("/house") public Map<String,Object> house() {return reads.house();}
    @GetMapping("/risk") public Map<String,Object> risk() {return Map.of("workerFailures",worker.failureCount(),"graceSeconds",60,"onchainAutomaticExecution",false,"serverWorkerExecution",true);}
}
