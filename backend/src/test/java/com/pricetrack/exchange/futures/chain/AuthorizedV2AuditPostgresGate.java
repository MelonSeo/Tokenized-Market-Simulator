package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.futures.audit.*;
import com.pricetrack.exchange.futures.audit.FuturesAuditDeployment.*;
import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.runtime.*;
import java.io.IOException;
import java.math.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DataAccessException;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in NEW owned PostgreSQL/Anvil only. Independent trial signer + controlled one-second provider,
 * not a user's wallet, Chrome acceptance or the product Synthetic provider. No response-loss tail.
 * The caller has already initialized the empty ledger and created normal/long/short fixture accounts.
 * This helper is NOT independently executable; the owning E2E runner supplies the isolated identity.
 */
final class AuthorizedV2AuditPostgresGate {
    private static final String AUDITS="/api/admin/futures/authorized-audits";
    private AuthorizedV2AuditPostgresGate(){}
    static void run(AuthorizedV2PostgresAnvilE2ETest h,FuturesChainIdentity chain,Credentials executor,
            Credentials signer,FuturesRpcGateway rpc)throws Exception {
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_operations",Integer.class)).isZero();
        var normal=existing(h,"normal");var longUser=existing(h,"long");var shortUser=existing(h,"short");
        var audit=h.context.getBean(FuturesAuditStore.class);
        var legacy=h.context.getBean(FuturesChainIdentity.class);var legacyRpc=h.context.getBean(FuturesRpcGateway.class);
        String execution=h.run().getFileName().toString();
        var legacyTarget=FuturesAuditDeployment.target(Route.LEGACY,execution+"-legacy",legacy,h.env.getRequiredProperty("app.futures.code-hash"),h.env.getRequiredProperty("app.futures.code-hash"),FuturesLedger.POLICY_HASH);
        String prefix="app.futures.user-authorization-v2.";
        var target=FuturesAuditDeployment.target(Route.B_V2,execution+"-v2",chain,h.env.getRequiredProperty(prefix+"controller-code-hash"),h.env.getRequiredProperty(prefix+"rules-code-hash"),FuturesLedger.POLICY_HASH);
        var configured=h.context.getBean(FuturesAuthorizedAuditConfig.TrustedTargets.class);
        assertThat(configured.registry().targets()).containsExactlyInAnyOrder(legacyTarget,target);
        var deployments=h.context.getBean(FuturesAuditDeploymentStore.class);
        var observer=h.context.getBean(FuturesAuthorizedAuditObserver.class);
        h.context.getBean(FuturesAuthorizedAuditService.class); // Require the actual bounded product service, not a helper replacement.
        assertThat(h.call(AUDITS,null,Map.of(),null).getStatusCode().value()).isEqualTo(401);
        assertThat(h.call(AUDITS,longUser.token(),Map.of(),null).getStatusCode().value()).isEqualTo(403);
        assertThat(h.call(AUDITS+"/targets",longUser.token(),null,null).getStatusCode().value()).isEqualTo(403);
        // Only this newly created trial account is promoted; JWT filter still resolves its current DB role.
        assertThat(h.jdbc.update("UPDATE users SET role='ADMIN' WHERE id=? AND role='USER'",normal.id())).isEqualTo(1);
        normal=existing(h,"normal");
        var configuredTargets=h.ok(h.call(AUDITS+"/targets",normal.token(),null,null));
        assertThat(configuredTargets.path("targets").size()).isEqualTo(2);
        var insufficient=observeHttp(h,normal);
        assertThat(insufficient.status()).isEqualTo("INCONCLUSIVE");assertThat(insufficient.complete()).isFalse();
        assertThat(insufficient.targets()).allMatch(t->t.reason().equals("BASELINE_REQUIRED"));
        h.evidence("audit-baseline-required",insufficient);
        var oldLegacyBaseline=h.jdbc.queryForList("SELECT payload FROM futures_audit_baselines WHERE id=1",String.class);
        // V2 coverage starts before its first trade. The untouched legacy target is independently
        // proven fresh from genesis and registered only after the final frozen cut below.
        // No baseline is overwritten/adopted and no V2 history is shortened.
        var base=h.json.treeToValue(h.ok(h.call(AUDITS+"/baselines",normal.token(),Map.of("targetKey",target.key()),null)),Envelope.class);
        assertThat(h.ok(h.call(AUDITS+"/baselines",normal.token(),null,null)).size()).isEqualTo(1);
        assertThat(deployments.find(legacyTarget)).isEmpty();assertThat(deployments.find(target)).contains(base);
        assertThat(h.jdbc.queryForList("SELECT payload FROM futures_audit_baselines WHERE id=1",String.class)).isEqualTo(oldLegacyBaseline);
        appendOnlyAndConcurrentRegistration(h,deployments,base);
        h.evidence("audit-v2-baseline-before-trades",base);

        // Stop only the fresh fixture's V2 worker. Manually exercise the existing execution/sender/reconciler
        // so the controlled first-estimate tick deterministically chooses revision1, without a worker race.
        h.stopV2Worker();
        startTradeScenarioInterval(h);
        var revisionStore=h.context.getBean(AuthorizedV2RevisionStore.class);
        var refresh=new AuthorizedV2Repricing(revisionStore,h.txs,h.market,chain,signer,id->AuthorizedV2Config.recheckBeforeSigning(chain,h.market,h.txs,rpc,id));
        var revisionZero=revisionTrades(h,normal,chain,executor,signer,rpc,refresh,false);
        var trades=revisionTrades(h,normal,chain,executor,signer,rpc,refresh,true);
        // This new trial-owned worker uses the unmodified product classes and real scheduler/chain time.
        // The original closed runtime worker is not restarted or replaced in the Spring bean graph.
        var intents=new AuthorizedV2IntentStore(h.source,h.ledger,h.drafts,chain);
        var sender=new FuturesTransactionSender(chain,executor,h.txs,rpc);
        var reconciler=new FuturesReconciler(chain,rpc,h.txs,sender,h.ledger,1);
        var executionService=new FuturesExecution(h.ledger,intents,h.market,chain,signer,h.txs,sender,id->AuthorizedV2Config.recheckBeforeSigning(chain,h.market,h.txs,rpc,id),refresh::reprice);
        String longPosition,shortPosition;
        try(var worker=new FuturesWorker(h.ledger,intents,h.market,rpc,reconciler,executionService,h.txs)){
            worker.start();h.scenario.price("75000");
            var longOpen=h.trade(longUser,Action.OPEN,null,"LONG",AuthorizedV2PostgresAnvilE2ETest.C,false,rpc);longPosition=longOpen.positionId();
            h.autoLiquidation(longUser,longPosition,"60000",rpc); // Real >=60 wall seconds; no warp/skip.
            h.scenario.price("75000");
            var shortOpen=h.trade(shortUser,Action.OPEN,null,"SHORT",AuthorizedV2PostgresAnvilE2ETest.C,false,rpc);shortPosition=shortOpen.positionId();
            h.autoLiquidation(shortUser,shortPosition,"90000",rpc); // Separate actual 60-second grace.
        }
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE state NOT IN ('CONFIRMED','REVERTED','REJECTED')",Integer.class)).isZero();
        assertThat(h.jdbc.queryForObject("SELECT sum(locked_amount) FROM user_balances WHERE symbol='mKRW'",BigDecimal.class)).isZero();
        assertThat(h.jdbc.queryForObject("SELECT reserved FROM futures_house WHERE id=1",BigDecimal.class)).isZero();
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE wallet_delta+house_delta<>0",Integer.class)).isZero();
        var wallets=h.jdbc.queryForObject("SELECT sum(amount) FROM user_balances WHERE symbol='mKRW'",BigDecimal.class).movePointRight(18).toBigIntegerExact();
        var house=h.jdbc.queryForObject("SELECT amount FROM futures_house WHERE id=1",BigDecimal.class).toBigIntegerExact();
        assertThat(wallets.add(house)).isEqualTo(BigInteger.valueOf(13_000_000).multiply(BigInteger.TEN.pow(18)));
        // Only after BOTH real grace periods and all settlements: freeze this NEW chain's observation cut.
        // These are explicit test-control RPCs, not product market/financial policy or clock manipulation.
        freezeOwnedChain(h);
        // The product baseline API scans this target's entire history from genesis and rejects any
        // target position/operation/activity. Only the genuinely unused LEGACY deployment starts here.
        // This avoids rescanning its long empty prefix in every combined observation; all V2 history
        // still begins at the original pre-trade baseline, with unchanged product read/call budgets.
        assertThat(deployments.find(target)).contains(base);assertThat(deployments.find(legacyTarget)).isEmpty();
        var legacyBase=h.json.treeToValue(h.ok(h.call(AUDITS+"/baselines",normal.token(),Map.of("targetKey",legacyTarget.key()),null)),Envelope.class);
        assertThat(h.ok(h.call(AUDITS+"/baselines",normal.token(),null,null)).size()).isEqualTo(2);
        assertThat(deployments.find(target)).contains(base);
        assertThat(h.jdbc.queryForList("SELECT payload FROM futures_audit_baselines WHERE id=1",String.class)).isEqualTo(oldLegacyBaseline);
        h.evidence("audit-baselines",Map.of("scope","NEW_ISOLATED_PG_ANVIL_V2_BASELINE_BEFORE_TRADES_UNUSED_LEGACY_BASELINE_AT_FINAL_FROZEN_CUT",
                "legacy",legacyBase,"v2",base,"legacyBaselineId1Untouched",true,"v2BaselineUnchanged",true,
                "legacyGenesisHistoryCheckedByProductApi",true,"appendOnlyZeroRowStatements",true,"concurrentIdenticalRegistration",true));
        var matched=observeHttp(h,normal);
        h.evidence("audit-observation-match",matched);
        assertThat(matched.complete()).isTrue();assertThat(matched.status()).describedAs("bounded multi-target audit: %s",matched.reason()).isEqualTo("MATCH");
        assertThat(matched.targets()).hasSize(2).allMatch(t->t.coverage().complete());
        var v2Result=matched.targets().stream().filter(t->t.targetKey().equals(target.key())).findFirst().orElseThrow();
        assertThat(v2Result.coverage().verifiedTransactions()).isEqualTo(12); // 3 revision0 + 3 revision1 + 2*(OPEN, START_CALL, LIQUIDATE).
        assertThat(v2Result.coverage().verifiedEvents()).isEqualTo(32); // 6*3 + 2*(3+2+2).
        assertThat(v2Result.items()).allMatch(i->i.verdict().equals("MATCH"));

        var insufficientRegistry=new FuturesAuthorizedAuditObserver(audit,deployments,List.of(new FuturesAuthorizedAuditObserver.Context(legacyTarget,legacyRpc))).observe(normal.id());
        assertThat(insufficientRegistry.status()).isEqualTo("INCONCLUSIVE");assertThat(insufficientRegistry.complete()).isFalse();
        assertThat(insufficientRegistry.reason()).isEqualTo("UNREGISTERED_OR_UNROUTABLE_DEPLOYMENT");
        h.evidence("audit-unregistered-v2-target",insufficientRegistry);
        pendingThenReject(h,normal,chain);

        // Deliberate mutable projection fault only in this owned fixture; no append-only journal mutation.
        BigDecimal exactHouse=h.jdbc.queryForObject("SELECT amount FROM futures_house WHERE id=1",BigDecimal.class);
        h.jdbc.update("UPDATE futures_house SET amount=amount+1 WHERE id=1");
        try{
            var mismatched=observeHttp(h,normal);h.evidence("audit-observation-house-mismatch",mismatched);
            assertThat(mismatched.complete()).isTrue();assertThat(mismatched.status()).isEqualTo("MISMATCH");
            assertThat(mismatched.checks()).anyMatch(c->c.name().equals("FUTURES_HOUSE")&&!c.matches());
        }finally{h.jdbc.update("UPDATE futures_house SET amount=? WHERE id=1",exactHouse);}
        var restored=observeHttp(h,normal);h.evidence("audit-observation-restored",restored);assertThat(restored.status()).isEqualTo("MATCH");assertThat(restored.complete()).isTrue();
        assertThat(deployments.find(target)).contains(base);assertThat(deployments.find(legacyTarget)).contains(legacyBase);
        assertThat(h.jdbc.queryForList("SELECT payload FROM futures_audit_baselines WHERE id=1",String.class)).isEqualTo(oldLegacyBaseline);
        var summary=new LinkedHashMap<String,Object>();summary.put("scope","REAL_NEW_PG_ANVIL_CONTROLLED_PROVIDER_EXTERNAL_TRIAL_SIGNER_NOT_BROWSER_OR_PRODUCT_SYNTHETIC");
        summary.put("target",target);summary.put("revisionZeroTrades",revisionZero);summary.put("revisionOneTrades",trades);
        summary.put("longPosition",longPosition);summary.put("shortPosition",shortPosition);summary.put("realGraceSecondsEach",60);
        summary.put("totalWalletHouseWei",wallets.add(house).toString());summary.put("locksAndHouseReservationsReleased",true);
        summary.put("verifiedTransactions",12);summary.put("verifiedEvents",32);summary.put("actualAdminHttpAndUser403",true);
        summary.put("baselineRequiredAndUnknownTargetInconclusive",true);summary.put("pendingInconclusiveThenUnsignedRelease",true);
        summary.put("v2BaselineBeforeAllTradesUnchanged",true);summary.put("unusedLegacyGenesisHistoryCheckedAndBaselineAtFinalFrozenCut",true);
        summary.put("audit","MATCH_THEN_INCONCLUSIVE_THEN_MISMATCH_THEN_RESTORED_MATCH");summary.put("frozenOnlyAfterActualGraceAndSettlement",true);h.evidence("gate",summary);
    }
    private static AuthorizedV2PostgresAnvilE2ETest.User existing(AuthorizedV2PostgresAnvilE2ETest h,String slot)throws Exception{
        String login="v2_"+slot;var token=h.ok(h.call("/api/auth/login",null,Map.of("loginId",login,"password","fixture-password"),null)).path("accessToken").asText();assertThat(token).isNotBlank();
        long id=h.jdbc.queryForObject("SELECT id FROM users WHERE login_id=?",Long.class,login);
        String owner=h.externalSign(slot,"address","");assertThat(h.jdbc.queryForObject("SELECT wallet FROM futures_wallet_links WHERE user_id=?",String.class,id)).isEqualToIgnoringCase(owner);
        return new AuthorizedV2PostgresAnvilE2ETest.User(token,id,slot,owner);
    }
    private static void appendOnlyAndConcurrentRegistration(AuthorizedV2PostgresAnvilE2ETest h,FuturesAuditDeploymentStore store,Envelope base)throws Exception{
        assertThatThrownBy(()->h.jdbc.update("UPDATE futures_audit_deployments SET actor_id=actor_id WHERE 1=0")).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(()->h.jdbc.update("DELETE FROM futures_audit_deployments WHERE 1=0")).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(()->h.jdbc.execute("TRUNCATE TABLE futures_audit_deployments")).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<Envelope> attempt=()->{gate.await();return store.register(base);};var first=pool.submit(attempt);var second=pool.submit(attempt);gate.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(base);assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(base);
        }finally{pool.shutdownNow();}
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_audit_deployments WHERE deployment_key=?",Integer.class,base.deployment().key())).isEqualTo(1);
    }
    private static List<Map<String,Object>> revisionTrades(AuthorizedV2PostgresAnvilE2ETest h,AuthorizedV2PostgresAnvilE2ETest.User user,FuturesChainIdentity chain,
            Credentials executor,Credentials signer,FuturesRpcGateway rpc,AuthorizedV2Repricing refresh,boolean reprice)throws Exception{
        Position position=null;var output=new ArrayList<Map<String,Object>>();
        for(Action action:List.of(Action.OPEN,Action.TOP_UP,Action.CLOSE)){
            h.scenario.price("75000");String key=h.key();
            var draftJson=h.ok(h.call(AuthorizedV2PostgresAnvilE2ETest.BASE+"/drafts",user.token(),h.request(action,action==Action.OPEN?null:position,"LONG",action==Action.CLOSE?BigInteger.ZERO:AuthorizedV2PostgresAnvilE2ETest.C),key));
            var draft=h.drafts.draft(user.id(),draftJson.path("id").asText(),false);
            String us=h.externalSign(user.slot(),"digest",Numeric.toHexString(UserIntentV2Codec.digest(chain,draft.intent())));
            h.ok(h.call(AuthorizedV2PostgresAnvilE2ETest.BASE+"/drafts/"+draft.id()+"/submit",user.token(),Map.of("signature",us),key));String id=draft.operation();
            var original=h.txs.fixedAuthorizedReport(id).orElseThrow();var estimates=new AtomicInteger();
            var transport=new FuturesTransactionSender.Transport(){
                public void verifyDeployment(FuturesChainIdentity c)throws IOException{rpc.verifyDeployment(c);}public BigInteger pendingNonce(String s)throws IOException{return rpc.pendingNonce(s);}public BigInteger gasPrice()throws IOException{return rpc.gasPrice();}
                public BigInteger estimate(String s,BigInteger nonce,BigInteger gas,String engine,String data)throws IOException{var value=rpc.estimate(s,nonce,gas,engine,data);if(estimates.incrementAndGet()==1&&reprice)h.scenario.price("75001");return value;}
                public boolean known(String hash)throws IOException{return rpc.known(hash);}public String broadcast(String raw)throws IOException{return rpc.broadcast(raw);}
            };
            var sender=new FuturesTransactionSender(chain,executor,h.txs,transport);
            var execution=new FuturesExecution(h.ledger,new AuthorizedV2IntentStore(h.source,h.ledger,h.drafts,chain),h.market,chain,signer,h.txs,sender,id2->AuthorizedV2Config.recheckBeforeSigning(chain,h.market,h.txs,rpc,id2),refresh::reprice);
            execution.submitPrepared(id);var saved=h.txs.find(id).orElseThrow();int revision=reprice?1:0;
            assertThat(h.txs.executionRevision(id)).isEqualTo(revision);assertThat(estimates.get()).isEqualTo(reprice?2:1);
            assertThat(saved.report().priceE8()).isEqualTo(new BigInteger(reprice?"7500100000000":"7500000000000"));assertThat(h.common.fixedReport(id).orElseThrow()).isEqualTo(original);
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_v2_execution_revisions WHERE operation_id=?",Integer.class,id)).isEqualTo(revision+1);
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='REPRICE'",Integer.class,id)).isEqualTo(revision);
            var reconciler=new FuturesReconciler(chain,rpc,h.txs,sender,h.ledger,1);
            h.await("AUDIT_REVISION1_CONFIRM",25,()->{reconciler.reconcile(id);return h.ledger.operation(id).state().equals("CONFIRMED");});
            var receipt=rpc.receipt(saved.hash()).orElseThrow();assertThat(receipt.isStatusOK()).isTrue();assertThat(receipt.getLogs()).hasSize(3);assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='CONFIRM'",Integer.class,id)).isEqualTo(1);
            assertThat(h.txs.find(id).orElseThrow().raw()).isEqualTo(saved.raw());position=h.ledger.position(draft.position(),user.id());
            if(action==Action.CLOSE){assertThat(position.status()).isEqualTo("CLOSED");assertThat(h.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,user.id())).isZero();assertThat(h.jdbc.queryForObject("SELECT reserved FROM futures_house WHERE id=1",BigDecimal.class)).isZero();}
            var confirmed=Map.<String,Object>of("action",action.name(),"operation",id,"hash",saved.hash(),"block",receipt.getBlockNumber().toString(),"blockHash",receipt.getBlockHash(),"revision",revision,"events",3,"state",h.ledger.operation(id).state());
            // Preserve each verified transaction immediately. A later action may fail before the
            // enclosing three-action summary is written; never rewrite evidence from an earlier run.
            h.evidence("audit-revision"+revision+"-"+action.name().toLowerCase(java.util.Locale.ROOT),confirmed);
            output.add(confirmed);
        }
        return List.copyOf(output);
    }
    private static FuturesAuthorizedAuditObserver.Observation observeHttp(AuthorizedV2PostgresAnvilE2ETest h,AuthorizedV2PostgresAnvilE2ETest.User admin)throws Exception{
        return h.json.treeToValue(h.ok(h.call(AUDITS,admin.token(),Map.of(),null)),FuturesAuthorizedAuditObserver.Observation.class);
    }
    private static void pendingThenReject(AuthorizedV2PostgresAnvilE2ETest h,AuthorizedV2PostgresAnvilE2ETest.User user,FuturesChainIdentity chain)throws Exception{
        h.scenario.price("75000");String id=h.prepareOnly(user,chain);var fixed=h.txs.fixedAuthorizedReport(id).orElseThrow();
        assertThat(h.ledger.operation(id).state()).isEqualTo("PREPARED");assertThat(h.txs.find(id)).isEmpty();
        var locked=h.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,user.id());
        assertThat(locked).isEqualByComparingTo("100000");
        try{
            var pending=observeHttp(h,user);assertThat(pending.status()).isEqualTo("INCONCLUSIVE");assertThat(pending.reason()).isEqualTo("UNSETTLED_OR_REVIEW");
            assertThat(pending.complete()).isFalse();assertThat(h.ledger.operation(id).state()).isEqualTo("PREPARED");
            assertThat(h.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,user.id())).isEqualByComparingTo(locked);
            h.evidence("audit-pending-inconclusive",Map.of("observation",pending,"operation",id,"unsigned",true,"lockedPreserved",true));
        }finally{
            // Explicit test abort of this NEW unsigned trial intent; no old row/append-only evidence is erased.
            h.ledger.rejectUnsignedAtReport(id,"AUDIT_FIXTURE_UNSIGNED_ABORT",fixed.getKey());
        }
        assertThat(h.ledger.operation(id).state()).isEqualTo("REJECTED");assertThat(h.txs.find(id)).isEmpty();
        assertThat(h.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,user.id())).isZero();
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='RELEASE'",Integer.class,id)).isEqualTo(1);
        var restored=observeHttp(h,user);assertThat(restored.status()).isEqualTo("MATCH");assertThat(restored.complete()).isTrue();
        h.evidence("audit-unsigned-rejected-release",Map.of("operation",id,"state","REJECTED","unsigned",true,"locksReleased",true,"observation",restored));
    }
    private static void freezeOwnedChain(AuthorizedV2PostgresAnvilE2ETest h)throws Exception{
        var client=new okhttp3.OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build();
        var service=new HttpService(h.env.getRequiredProperty("app.futures.user-authorization-v2.rpc-url"),client,false);
        try{
            for(var method:List.of("anvil_setIntervalMining","evm_setAutomine")){
                var response=new Request<Object,Response>(method,method.equals("anvil_setIntervalMining")?List.of(0):List.of(false),service,Response.class).send();
                assertThat(response.hasError()).describedAs("owned fixture mining control %s",method).isFalse();
            }
        }finally{try{service.close();}finally{client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}}
    }
    private static void startTradeScenarioInterval(AuthorizedV2PostgresAnvilE2ETest h)throws Exception{
        var metadata=h.json.readTree(h.run().resolve("run.json").toFile());
        assertThat(metadata.path("initialMiningMode").asText()).isEqualTo("AUTOMINE");
        assertThat(metadata.path("tradeScenarioIntervalSeconds").asInt()).isEqualTo(2);
        var client=new okhttp3.OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).retryOnConnectionFailure(false).build();
        var service=new HttpService(h.env.getRequiredProperty("app.futures.user-authorization-v2.rpc-url"),client,false);
        long began=System.nanoTime();
        try{
            BigInteger before=intervalHeight(service);
            // Turn off the initial automine explicitly, then use real periodic blocks for ALL trades.
            // No timestamp RPC/warp and no change to the contract's 60-second grace or read budgets.
            var automatic=new Request<Object,Response>("evm_setAutomine",List.of(false),service,Response.class).send();
            assertThat(automatic.hasError()).describedAs("owned fixture disable initial automine").isFalse();
            var interval=new Request<Object,Response>("anvil_setIntervalMining",List.of(2),service,Response.class).send();
            assertThat(interval.hasError()).describedAs("owned fixture activate two-second trade interval").isFalse();
            long activation=System.nanoTime(),until=activation+TimeUnit.SECONDS.toNanos(10);BigInteger observed=null;
            for(int polls=0;polls<20;polls++){
                if(System.nanoTime()>=until)throw new IllegalStateException("TRADE_INTERVAL_FIRST_BLOCK_DEADLINE");
                // Only successful read polls repeat. An RPC error/timeout propagates immediately, never retries.
                observed=intervalHeight(service);
                if(System.nanoTime()>=until)throw new IllegalStateException("TRADE_INTERVAL_FIRST_BLOCK_DEADLINE");
                if(observed.compareTo(before)>0)break;
                if(polls==19)throw new IllegalStateException("TRADE_INTERVAL_FIRST_BLOCK_DEADLINE");
                Thread.sleep(500);
            }
            if(observed==null||observed.compareTo(before)<=0)throw new IllegalStateException("TRADE_INTERVAL_FIRST_BLOCK_DEADLINE");
            var firstResponse=new Request<Object,org.web3j.protocol.core.methods.response.EthBlock>("eth_getBlockByNumber",List.of(Numeric.encodeQuantity(before.add(BigInteger.ONE)),false),service,org.web3j.protocol.core.methods.response.EthBlock.class).send();
            assertThat(firstResponse.hasError()).describedAs("owned fixture first periodic block evidence").isFalse();
            var first=firstResponse.getBlock();assertThat(first).isNotNull();assertThat(first.getNumber()).isEqualTo(before.add(BigInteger.ONE));
            assertThat(first.getHash()).matches("0x[0-9a-fA-F]{64}");assertThat(first.getTimestamp()).isNotNegative();
            if(System.nanoTime()>=until)throw new IllegalStateException("TRADE_INTERVAL_FIRST_BLOCK_DEADLINE");
            var evidence=new LinkedHashMap<String,Object>();evidence.put("initialMode","AUTOMINE");evidence.put("intervalSeconds",2);
            evidence.put("disabledAutomine",true);evidence.put("intervalRequestAcknowledgedWithoutRpcError",true);
            evidence.put("beforeAllTradingIntents",true);evidence.put("callTimeoutSeconds",3);evidence.put("automaticConnectionRetries",false);
            evidence.put("heightBeforeActivation",before.toString());evidence.put("observedHeightAfterActivation",observed.toString());
            evidence.put("firstPeriodicBlock",first.getNumber().toString());evidence.put("firstPeriodicTimestamp",first.getTimestamp().toString());evidence.put("firstPeriodicHash",first.getHash());
            evidence.put("firstBlockObservedWithinSeconds",10);evidence.put("activationToEvidenceNanos",System.nanoTime()-activation);
            evidence.put("maxHeightPolls",20);evidence.put("pollIntervalMillis",500);
            evidence.put("observedNewPeriodicBlock",true);evidence.put("fullIntervalCadenceMeasured",false);
            evidence.put("elapsedNanos",System.nanoTime()-began);evidence.put("scope","NEW_OWNED_TEST_CHAIN_ONLY_NO_TIMESTAMP_MANIPULATION");
            h.evidence("trade-scenario-mining-start",evidence);
        }finally{try{service.close();}finally{client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}}
    }
    private static BigInteger intervalHeight(HttpService service)throws IOException{
        var response=new Request<Object,Response>("eth_blockNumber",List.of(),service,Response.class).send();
        if(response.hasError()||!(response.getResult() instanceof String encoded)||!encoded.matches("0x[0-9a-fA-F]{1,64}"))throw new IOException("TRADE_INTERVAL_HEIGHT_UNAVAILABLE");
        return Numeric.toBigInt(encoded);
    }
}
