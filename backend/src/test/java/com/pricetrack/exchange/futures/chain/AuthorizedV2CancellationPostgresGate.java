package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.futures.audit.*;
import com.pricetrack.exchange.futures.audit.FuturesAuditDeployment.*;
import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.runtime.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.web3j.crypto.*;
import org.web3j.protocol.core.*;
import org.web3j.protocol.http.HttpService;
import static org.assertj.core.api.Assertions.*;

/** New owned PG/Anvil short cancellation Gate. Not MetaMask, Synthetic or another 60-second Gate. */
final class AuthorizedV2CancellationPostgresGate {
    private static final String AUDITS="/api/admin/futures/authorized-audits";
    private AuthorizedV2CancellationPostgresGate(){}
    static void run(AuthorizedV2PostgresAnvilE2ETest h,FuturesChainIdentity chain,Credentials executor,Credentials signer,
                    FuturesRpcGateway rpc,AuthorizedV2PostgresAnvilE2ETest.User user)throws Exception {
        assertThat(System.getenv("DAPP_V2_E2E_STAGE")).isEqualTo("cancel");
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_operations",Integer.class)).isZero();
        String prefix="app.futures.user-authorization-v2.";
        var target=FuturesAuditDeployment.target(Route.B_V2,h.run().getFileName()+"-v2",chain,h.env.getRequiredProperty(prefix+"controller-code-hash"),h.env.getRequiredProperty(prefix+"rules-code-hash"),FuturesLedger.POLICY_HASH);
        var legacy=h.context.getBean(FuturesChainIdentity.class);
        var legacyTarget=FuturesAuditDeployment.target(Route.LEGACY,h.run().getFileName()+"-legacy",legacy,h.env.getRequiredProperty("app.futures.code-hash"),h.env.getRequiredProperty("app.futures.code-hash"),FuturesLedger.POLICY_HASH);
        assertThat(h.call(AUDITS,user.token(),Map.of(),null).getStatusCode().value()).isEqualTo(403);
        assertThat(h.jdbc.update("UPDATE users SET role='ADMIN' WHERE id=? AND role='USER'",user.id())).isEqualTo(1);
        var baseline=h.ok(h.call(AUDITS+"/baselines",user.token(),Map.of("targetKey",target.key()),null));
        h.evidence("cancellation-v2-baseline-before-trades",baseline);
        h.stopV2Worker();h.scenario.price("75000");
        startPeriodicMining(h,rpc);
        var intents=new AuthorizedV2IntentStore(h.source,h.ledger,h.drafts,chain);
        var sender=new FuturesTransactionSender(chain,executor,h.txs,rpc);
        var reconciler=new FuturesReconciler(chain,rpc,h.txs,sender,h.ledger,1);
        var refresh=new AuthorizedV2Repricing(h.context.getBean(AuthorizedV2RevisionStore.class),h.txs,h.market,chain,signer,id->AuthorizedV2Config.recheckBeforeSigning(chain,h.market,h.txs,rpc,id));
        var execution=new FuturesExecution(h.ledger,intents,h.market,chain,signer,h.txs,sender,id->AuthorizedV2Config.recheckBeforeSigning(chain,h.market,h.txs,rpc,id),refresh::reprice);
        try(var worker=new FuturesWorker(h.ledger,intents,h.market,rpc,reconciler,execution,h.txs)){
            worker.start();
            assertThat(rpc.userNonce(user.owner(),rpc.height())).isZero();
            var opened=h.trade(user,Action.OPEN,null,"LONG",AuthorizedV2PostgresAnvilE2ETest.C,false,rpc);
            assertIntentNonce(h,opened,BigInteger.ZERO);assertThat(rpc.userNonce(user.owner(),rpc.height())).isEqualTo(BigInteger.ONE);
            String before=financial(h);
            cancellations(h,chain,rpc,user);
            assertThat(financial(h)).isEqualTo(before);
            assertThat(rpc.userNonce(user.owner(),rpc.height())).isEqualTo(BigInteger.TWO);
            var position=h.ledger.position(opened.positionId(),user.id());
            var topped=h.trade(user,Action.TOP_UP,position,"LONG",AuthorizedV2PostgresAnvilE2ETest.C,false,rpc);assertIntentNonce(h,topped,BigInteger.TWO);
            var closed=h.trade(user,Action.CLOSE,h.ledger.position(position.id(),user.id()),"LONG",BigInteger.ZERO,false,rpc);assertIntentNonce(h,closed,BigInteger.valueOf(3));
            assertThat(h.ledger.position(position.id(),user.id()).status()).isEqualTo("CLOSED");
            assertThat(rpc.userNonce(user.owner(),rpc.height())).isEqualTo(BigInteger.valueOf(4));
        }
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE state<>'CONFIRMED'",Integer.class)).isZero();
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_transactions",Integer.class)).isEqualTo(3);
        assertThat(h.jdbc.queryForObject("SELECT sum(locked_amount) FROM user_balances WHERE symbol='mKRW'",BigDecimal.class)).isZero();
        assertThat(h.jdbc.queryForObject("SELECT reserved FROM futures_house WHERE id=1",BigDecimal.class)).isZero();
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE wallet_delta+house_delta<>0",Integer.class)).isZero();
        var financialBeforeAudit=financial(h);
        freezeOwnedCut(h,rpc);
        assertThat(financial(h)).isEqualTo(financialBeforeAudit);
        h.evidence("cancellation-legacy-fresh-baseline",h.ok(h.call(AUDITS+"/baselines",user.token(),Map.of("targetKey",legacyTarget.key()),null)));
        var result=h.json.treeToValue(h.ok(h.call(AUDITS,user.token(),Map.of(),null)),FuturesAuthorizedAuditObserver.Observation.class);
        assertThat(result.status()).isEqualTo("MATCH");assertThat(result.complete()).isTrue();
        var v2=result.targets().stream().filter(t->t.targetKey().equals(target.key())).findFirst().orElseThrow();
        assertThat(v2.coverage().transactions()).isEqualTo(3);assertThat(v2.coverage().verifiedTransactions()).isEqualTo(3);assertThat(v2.coverage().verifiedEvents()).isEqualTo(9);
        assertThat(v2.cancellationCoverage()).isEqualTo(new FuturesAuthorizedAuditObserver.CancellationCoverage(2,1,1,1,true));
        assertThat(financial(h)).isEqualTo(financialBeforeAudit);
        h.evidence("cancellation-observation-match",result);
        h.evidence("gate",Map.of("scope","NEW_OWNED_CANCELLATION_ONLY_NO_REAL_USER_KEY_NO_60_SECOND_RETEST","identity",chain,
                "dbTransactions",3,"externalCancellations",2,"unionTransactions",5,"financialUnchangedByCancellationAndAudit",true,"canonicalFailureCause","UNDETERMINED","metaMask",false));
    }
    private static void assertIntentNonce(AuthorizedV2PostgresAnvilE2ETest h,Operation operation,BigInteger expected){
        assertThat(h.txs.find(operation.id()).orElseThrow().authorization().intentV2().nonce()).isEqualTo(expected);
    }
    private static String financial(AuthorizedV2PostgresAnvilE2ETest h)throws Exception {
        var values=new LinkedHashMap<String,Object>();
        for(String table:List.of("user_balances","futures_positions","futures_house","futures_journal","futures_operations"))
            values.put(table,h.jdbc.queryForList("SELECT row_to_json(t)::text AS row FROM "+table+" t ORDER BY row_to_json(t)::text"));
        return Hash.sha3String(h.json.writeValueAsString(values));
    }
    private static void cancellations(AuthorizedV2PostgresAnvilE2ETest h,FuturesChainIdentity chain,FuturesRpcGateway rpc,AuthorizedV2PostgresAnvilE2ETest.User user)throws Exception {
        rpc.verifyDeployment(chain);assertThat(rpc.eoa(user.owner(),rpc.height())).isTrue();assertThat(rpc.pendingNonce(user.owner())).isZero();
        var client=FuturesRpcHttp.client().newBuilder().retryOnConnectionFailure(false).build();
        var service=new HttpService(h.env.getRequiredProperty("app.futures.user-authorization-v2.rpc-url"),client,false);
        try{
            control(service,"anvil_setBalance",List.of(user.owner(),"0xde0b6b3a7640000")); // Test ETH gas only; not collateral.
            control(service,"anvil_setIntervalMining",List.of(0));control(service,"evm_setAutomine",List.of(false));
            var gas=rpc.gasPrice();String first=raw(h,user,"0",gas),second=raw(h,user,"1",gas);
            String firstHash=Hash.sha3(first),secondHash=Hash.sha3(second);
            assertThat(rpc.broadcast(first)).isEqualToIgnoringCase(firstHash);assertThat(rpc.broadcast(second)).isEqualToIgnoringCase(secondHash);
            control(service,"evm_mine",List.of()); // Exactly one normal new block, no clock warp.
            var success=rpc.receipt(firstHash).orElseThrow();var failed=rpc.receipt(secondHash).orElseThrow();
            assertThat(success.isStatusOK()).isTrue();assertThat(success.getLogs()).hasSize(1);assertThat(failed.getStatus()).isEqualTo("0x0");assertThat(failed.getLogs()).isEmpty();
            assertThat(success.getBlockHash()).isEqualTo(failed.getBlockHash());assertThat(success.getTransactionIndex()).isLessThan(failed.getTransactionIndex());
            var block=rpc.block(success.getBlockNumber());
            assertThat(block.getHash()).isEqualTo(success.getBlockHash());
            for(String hash:List.of(firstHash,secondHash)){
                var receipt=rpc.receipt(hash).orElseThrow();var tx=rpc.transaction(hash).orElseThrow();
                String prefix="app.futures.user-authorization-v2.";
                var target=FuturesAuditDeployment.target(Route.B_V2,h.run().getFileName()+"-v2",chain,h.env.getRequiredProperty(prefix+"controller-code-hash"),h.env.getRequiredProperty(prefix+"rules-code-hash"),FuturesLedger.POLICY_HASH);
                assertThat(FuturesAuditNonceCancellationVerifier.verify(target,tx,receipt,block).verdict()).isEqualTo(FuturesAuditNonceCancellationVerifier.Verdict.VERIFIED);
                h.evidence(hash.equals(firstHash)?"cancellation-success":"cancellation-failed",Map.of("hash",hash,"receipt",receipt,"transactionType",tx.getType(),"expectedUserNonce",1,"success",receipt.isStatusOK(),"failureCause","UNDETERMINED"));
            }
        }finally{
            // This stage's normal mode is the same two-second periodic chain as the guarded fixture.
            // Restore it even when this NEW owned batch fails; never continue the test after failure.
            try{control(service,"evm_setAutomine",List.of(false));control(service,"anvil_setIntervalMining",List.of(2));}
            finally{try{service.close();}finally{client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}}
        }
    }
    private static void startPeriodicMining(AuthorizedV2PostgresAnvilE2ETest h,FuturesRpcGateway rpc)throws Exception {
        var client=FuturesRpcHttp.client().newBuilder().retryOnConnectionFailure(false).build();
        var service=new HttpService(h.env.getRequiredProperty("app.futures.user-authorization-v2.rpc-url"),client,false);
        try{
            BigInteger before=rpc.height();long began=System.nanoTime(),until=began+TimeUnit.SECONDS.toNanos(10);
            control(service,"evm_setAutomine",List.of(false));control(service,"anvil_setIntervalMining",List.of(2));
            BigInteger observed=null;
            for(int polls=0;polls<40;polls++){
                if(System.nanoTime()>=until)throw new IllegalStateException("CANCEL_PERIODIC_FIRST_BLOCK_DEADLINE");
                observed=rpc.height();if(observed.compareTo(before)>0)break;
                if(polls==39)throw new IllegalStateException("CANCEL_PERIODIC_FIRST_BLOCK_DEADLINE");Thread.sleep(250);
            }
            assertThat(observed).isGreaterThan(before);var first=rpc.block(before.add(BigInteger.ONE));
            assertThat(first.getNumber()).isEqualTo(before.add(BigInteger.ONE));assertThat(first.getHash()).matches("0x[0-9a-fA-F]{64}");
            if(System.nanoTime()>=until)throw new IllegalStateException("CANCEL_PERIODIC_FIRST_BLOCK_DEADLINE");
            h.evidence("cancellation-periodic-mining-start",Map.of("intervalSeconds",2,"heightBefore",before.toString(),"firstBlock",first.getNumber().toString(),"blockHash",first.getHash(),"firstTimestamp",first.getTimestamp().toString(),"activationToEvidenceNanos",System.nanoTime()-began,"beforeFirstTrade",true));
        }finally{try{service.close();}finally{client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}}
    }
    private static void freezeOwnedCut(AuthorizedV2PostgresAnvilE2ETest h,FuturesRpcGateway rpc)throws Exception {
        // Only after all five canonical transactions, DB settlement and the owned worker's close.
        // Stable observation is a test lifecycle boundary, not a product read-budget relaxation.
        var client=FuturesRpcHttp.client().newBuilder().retryOnConnectionFailure(false).build();
        var service=new HttpService(h.env.getRequiredProperty("app.futures.user-authorization-v2.rpc-url"),client,false);
        try{
            control(service,"anvil_setIntervalMining",List.of(0));control(service,"evm_setAutomine",List.of(false));
            var number=rpc.height();var block=rpc.block(number);assertThat(block.getNumber()).isEqualTo(number);assertThat(block.getHash()).matches("0x[0-9a-fA-F]{64}");
            h.evidence("cancellation-final-frozen-cut",Map.of("block",number.toString(),"blockHash",block.getHash(),"timestamp",block.getTimestamp().toString(),"scope","NEW_OWNED_CHAIN_AFTER_ALL_SETTLEMENTS","periodicMiningDuringTradesSeconds",2));
        }finally{try{service.close();}finally{client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}}
    }
    private static void control(HttpService service,String method,List<?> params)throws Exception {
        if(!Set.of("anvil_setBalance","anvil_setIntervalMining","evm_setAutomine","evm_mine").contains(method))throw new IllegalArgumentException("CANCEL_CONTROL_NOT_ALLOWED");
        var response=new Request<Object,Response>(method,new ArrayList<Object>(params),service,Response.class).send();
        assertThat(response.hasError()).describedAs("owned cancellation control failed").isFalse();
    }
    private static String raw(AuthorizedV2PostgresAnvilE2ETest h,AuthorizedV2PostgresAnvilE2ETest.User user,String nonce,BigInteger gas)throws Exception {
        var validated=AuthorizedV2PostgresAnvilE2ETest.signerCommand(h.run(),Path.of(System.getProperty("java.home")),false,System.getenv("DAPP_V2_SIGNER_CLASSPATH"),user.slot(),"address","");
        int index=validated.indexOf("Bv2ExternalSigner");if(index<0)throw new IllegalStateException("CANCEL_PORTABLE_SIGNER_REQUIRED");
        var command=new ArrayList<>(validated.subList(0,index));command.addAll(List.of("TrialCancelSigner",h.run().toRealPath().toString(),user.slot(),nonce,gas.toString()));
        var process=new ProcessBuilder(command).redirectError(h.run().resolve("cancel-signer-"+UUID.randomUUID()+".private.log").toFile()).start();
        if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("CANCEL_TRIAL_SIGNER_TIMEOUT");}
        String raw=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        if(process.exitValue()!=0||raw.length()>2048||!raw.matches("0x[0-9a-fA-F]{2,2046}"))throw new IllegalStateException("CANCEL_TRIAL_SIGNER_FAILED");
        return raw;
    }
}
