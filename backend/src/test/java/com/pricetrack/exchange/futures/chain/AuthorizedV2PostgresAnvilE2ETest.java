package com.pricetrack.exchange.futures.chain;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.web3j.protocol.http.HttpService;
import static org.assertj.core.api.Assertions.*;

/** Opt-in NEW owned PG/Anvil. Independent child signer, never a user private key Spring property. */
@EnabledIfEnvironmentVariable(named="DAPP_V2_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class)
class AuthorizedV2PostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("DAPP_V2_E2E_RUN"));
        if(p==null||!p.getProperty("app.futures.user-authorization-v2.enabled","").equals("true"))throw new IllegalStateException("NEW_V2_FIXTURE_REQUIRED");
        p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @Autowired TestRestTemplate http;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Autowired FuturesLedger ledger;@Autowired FuturesTransactionStore txs;@Autowired AuthorizedV2Store drafts;
    @Autowired FuturesAutoPostgresAnvilE2ETest.Scenario scenario;@Autowired Environment env;
    @Autowired javax.sql.DataSource source;@Autowired FuturesSchema schema;@Autowired org.springframework.context.ApplicationContext context;
    @Autowired com.pricetrack.exchange.futures.runtime.FuturesMarketGate market;@Autowired AuthorizedFuturesStore common;
    static final String BASE="/api/futures/authorized-v2",ORIGIN="http://127.0.0.1:5199";
    static final BigInteger C=BigInteger.TEN.pow(23);
    Path run(){return Path.of(System.getenv("DAPP_V2_E2E_RUN"));}
    String key(){return UUID.randomUUID().toString();}
    void evidence(String name,Object value)throws Exception {Files.writeString(run().resolve(name+".evidence.json"),json.writeValueAsString(value),StandardOpenOption.CREATE_NEW);}
    ResponseEntity<JsonNode> call(String path,String token,Object body,String key){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.set("Origin",ORIGIN);if(token!=null)h.setBearerAuth(token);if(key!=null)h.set("Idempotency-Key",key);return http.exchange(path,body==null?HttpMethod.GET:HttpMethod.POST,new HttpEntity<>(body,h),JsonNode.class);}
    JsonNode ok(ResponseEntity<JsonNode> r){assertThat(r.getStatusCode().is2xxSuccessful()).describedAs("HTTP response: %s",r.getBody()).isTrue();return r.getBody();}
    void await(String label,int seconds,BooleanSupplier predicate)throws Exception {long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!predicate.getAsBoolean()){if(System.nanoTime()>stop)throw new IllegalStateException(label);Thread.sleep(100);}}
    String externalSign(String slot,String mode,String value)throws Exception {
        // Sender JVM is an independent test harness; its key file is not loaded into Spring Environment.
        var command=signerCommand(run(),Path.of(System.getProperty("java.home")),System.getProperty("os.name").startsWith("Windows"),System.getenv("DAPP_V2_SIGNER_CLASSPATH"),slot,mode,value);
        var process=new ProcessBuilder(command).redirectError(run().resolve("signer-"+UUID.randomUUID()+".private.log").toFile()).start();
        if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("SIGNER_TIMEOUT");}
        String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).isZero();assertThat(result).matches(mode.equals("address")?"0x[0-9a-fA-F]{40}":mode.equals("cancel")?"0x[0-9a-fA-F]{64}":"0x[0-9a-fA-F]{130}");return result;
    }
    /** Test-only portability. Compiled dependencies must be copied into THIS fresh fixture, not old runtimes. */
    static List<String> signerCommand(Path fixture,Path javaHome,boolean windows,String fixtureClasspath,String slot,String mode,String value){
        if(slot==null||!slot.matches("[a-z][a-z0-9_-]{0,39}")||!Set.of("address","personal","digest","cancel").contains(mode))throw new IllegalStateException("DAPP_EXTERNAL_SIGNER_CONFIGURATION_INVALID");
        var command=new ArrayList<String>();command.add(javaHome.resolve("bin").resolve(windows?"java.exe":"java").toString());
        if(fixtureClasspath==null){
            // Original Windows source-launcher/classpath behavior, with only the OS executable name selected.
            command.add("@"+Path.of("../deployment/runtime/dapp-stage2-offline/java-classpath.args").toAbsolutePath());
            command.add(Path.of("../deployment/runtime/dapp-stage2-offline/Bv2ExternalSigner.java").toAbsolutePath().toString());
            command.add(fixture.resolve("secrets/user-"+slot).toAbsolutePath().toString());
        }else{
            try{
                if(fixtureClasspath.isBlank()||fixtureClasspath.length()>65536)throw new IllegalArgumentException();
                var root=fixture.toRealPath();var session=root.getParent().getParent();
                if(!root.getFileName().toString().matches("futures-api-[0-9a-f]{8}")||!root.getParent().getFileName().toString().equals("fixtures")||!session.getFileName().toString().matches("phase-a-\\d{14}-[0-9a-f]{8}"))throw new IllegalArgumentException();
                String[] entries=fixtureClasspath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1);
                if(entries.length==0||entries.length>256)throw new IllegalArgumentException();
                var paths=new ArrayList<String>();boolean signerClass=false;
                for(String entry:entries){
                    if(entry.isBlank()||entry.contains("*"))throw new IllegalArgumentException();var path=Path.of(entry).toRealPath();
                    if(!path.startsWith(root)||(!Files.isDirectory(path)&&(!Files.isRegularFile(path)||!path.getFileName().toString().endsWith(".jar"))))throw new IllegalArgumentException();
                    if(Files.isDirectory(path)&&Files.isRegularFile(path.resolve("Bv2ExternalSigner.class"))){if(!path.resolve("Bv2ExternalSigner.class").toRealPath().startsWith(root))throw new IllegalArgumentException();signerClass=true;}
                    paths.add(path.toString());
                }
                var key=root.resolve("secrets/user-"+slot).toRealPath();
                if(!signerClass||!Files.isRegularFile(key)||!key.startsWith(root.resolve("secrets")))throw new IllegalArgumentException();
                command.add("-cp");command.add(String.join(java.io.File.pathSeparator,paths));command.add("Bv2ExternalSigner");command.add(key.toString());
            }catch(Exception invalid){throw new IllegalStateException("DAPP_EXTERNAL_SIGNER_CONFIGURATION_INVALID");}
        }
        command.add(mode);command.add(value);return List.copyOf(command);
    }
    <T> T patched(T value,Class<T> type,Map<String,?> changes){var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(value);changes.forEach((k,v)->node.set(k,json.valueToTree(v)));return json.convertValue(node,type);}
    String rawCall(FuturesReport r,String ps,UserIntentV2Codec.Intent i,String us){return org.web3j.abi.FunctionEncoder.encode(new org.web3j.abi.datatypes.Function("open",List.of(new org.web3j.abi.datatypes.StaticStruct(FuturesReportCodec.fields(r)),new org.web3j.abi.datatypes.DynamicBytes(org.web3j.utils.Numeric.hexStringToByteArray(ps)),new org.web3j.abi.datatypes.StaticStruct(UserIntentV2Codec.fields(i)),new org.web3j.abi.datatypes.DynamicBytes(org.web3j.utils.Numeric.hexStringToByteArray(us))),List.of()));}
    void chainNegativeGate(User user,Operation opened,FuturesChainIdentity chain,org.web3j.crypto.Credentials signer,FuturesRpcGateway rpc)throws Exception {
        var original=txs.find(opened.id()).orElseThrow();var results=new ArrayList<Map<String,Object>>();
        var httpService=new HttpService(env.getRequiredProperty("app.futures.user-authorization-v2.rpc-url"));var web3=org.web3j.protocol.Web3j.build(httpService);try{
            for(String which:List.of("baseline","missing-signature","price-bounds","quantity-bounds","wrong-owner","wrong-nonce","wrong-chain","wrong-contract","user-expired","report-expired","v1-abi","signature-replay","unauthorized-relayer")){
                long now=Instant.now().getEpochSecond();String op=org.web3j.crypto.Hash.sha3String(key()),pid=org.web3j.crypto.Hash.sha3String(key());
                var r=patched(original.report(),FuturesReport.class,Map.of("operationId",op,"positionId",pid,"observedAt",BigInteger.valueOf(now),"validUntil",BigInteger.valueOf(now+30)));
                var i=patched(original.authorization().intentV2(),UserIntentV2Codec.Intent.class,Map.of("operationId",op,"positionId",pid,"nonce",rpc.userNonce(user.owner()),"issuedAt",BigInteger.valueOf(now),"deadline",BigInteger.valueOf(now+120)));
                if(which.equals("price-bounds"))i=patched(i,UserIntentV2Codec.Intent.class,Map.of("priceMinE8",r.priceE8().add(BigInteger.ONE),"priceMaxE8",r.priceE8().add(BigInteger.ONE)));
                if(which.equals("quantity-bounds"))i=patched(i,UserIntentV2Codec.Intent.class,Map.of("quantityMin",r.quantity().add(BigInteger.ONE),"quantityMax",r.quantity().add(BigInteger.ONE)));
                if(which.equals("wrong-owner"))i=patched(i,UserIntentV2Codec.Intent.class,Map.of("owner",chain.priceSigner()));
                if(which.equals("wrong-nonce"))i=patched(i,UserIntentV2Codec.Intent.class,Map.of("nonce",i.nonce().add(BigInteger.ONE)));
                if(which.equals("user-expired"))i=patched(i,UserIntentV2Codec.Intent.class,Map.of("issuedAt",BigInteger.valueOf(now-121),"deadline",BigInteger.valueOf(now-1)));
                if(which.equals("report-expired"))r=patched(r,FuturesReport.class,Map.of("observedAt",BigInteger.valueOf(now-31),"validUntil",BigInteger.valueOf(now-1)));
                var domain=which.equals("wrong-chain")?new FuturesChainIdentity(BigInteger.valueOf(31338),chain.engine(),chain.executor(),chain.priceSigner(),chain.rules(),"2"):which.equals("wrong-contract")?new FuturesChainIdentity(chain.chainId(),env.getRequiredProperty("app.futures.engine-address"),chain.executor(),chain.priceSigner(),chain.rules(),"2"):chain;
                String us=externalSign(user.slot(),"digest",org.web3j.utils.Numeric.toHexString(UserIntentV2Codec.digest(domain,i))),ps=FuturesReportCodec.sign(r,chain.chainId(),chain.rules(),signer.getEcKeyPair());
                String data=rawCall(r,ps,i,which.equals("missing-signature")?"0x":us);
                if(which.equals("v1-abi"))data=FuturesReportCodec.encode(r,ps);
                if(which.equals("signature-replay"))data=rawCall(original.report(),original.signature(),original.authorization().intentV2(),original.authorization().userSignature());
                var response=web3.ethCall(org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(which.equals("unauthorized-relayer")?user.owner():chain.executor(),chain.engine(),data),org.web3j.protocol.core.DefaultBlockParameterName.PENDING).send();
                boolean rejected=response.hasError()||response.isReverted();assertThat(rejected).describedAs("actual EVM eth_call %s",which).isEqualTo(!which.equals("baseline"));results.add(Map.of("case",which,"rejected",rejected,"boundary","REAL_ANVIL_ETH_CALL_NOT_MINED_TRANSACTION"));
            }
        }finally{web3.shutdown();}
        evidence("chain-negative",results);
    }
    void prepareResponseLoss(User user,FuturesChainIdentity chain,org.web3j.crypto.Credentials executor,FuturesRpcGateway rpc)throws Exception {
        // Stop only this NEW V2 worker. Legacy worker cannot select v2-marked preparations.
        stopV2Worker();
        scenario.price("75000");String k=key();var d=ok(call(BASE+"/drafts",user.token(),request(Action.OPEN,null,"LONG",C),k));var draft=drafts.draft(user.id(),d.path("id").asText(),false);
        String signature=externalSign(user.slot(),"digest",org.web3j.utils.Numeric.toHexString(UserIntentV2Codec.digest(chain,draft.intent())));
        ok(call(BASE+"/drafts/"+draft.id()+"/submit",user.token(),Map.of("signature",signature),k));var auth=common.operation(draft.operation());
        var transport=new FuturesTransactionSender.Transport(){
            public void verifyDeployment(FuturesChainIdentity c)throws java.io.IOException{rpc.verifyDeployment(c);}public BigInteger pendingNonce(String s)throws java.io.IOException{return rpc.pendingNonce(s);}public BigInteger gasPrice()throws java.io.IOException{return rpc.gasPrice();}
            public BigInteger estimate(String s,BigInteger n,BigInteger g,String e,String data)throws java.io.IOException{return rpc.estimate(s,n,g,e,data);}
            public boolean known(String h)throws java.io.IOException{return rpc.known(h);}
            public String broadcast(String raw)throws java.io.IOException{var saved=txs.find(draft.operation()).orElseThrow();assertThat(saved.status()).isEqualTo("SIGNED");assertThat(saved.raw()).isEqualTo(raw);rpc.broadcast(raw);throw new java.io.IOException("TEST_RESPONSE_LOSS_AFTER_REAL_SEND");}
        };
        var sender=new FuturesTransactionSender(chain,executor,txs,transport);var fixed=common.fixedReport(draft.operation()).orElseThrow();
        sender.submit(draft.operation(),fixed.getKey(),fixed.getValue(),()->AuthorizedV2Config.recheckBeforeSigning(chain,market,txs,rpc,draft.operation()));
        var saved=txs.find(draft.operation()).orElseThrow();assertThat(saved.status()).isEqualTo("SIGNED");assertThat(ledger.operation(draft.operation()).state()).isEqualTo("SIGNED");
        evidence("restart-prepare",Map.of("operation",draft.operation(),"hash",saved.hash(),"nonce",saved.nonce(),"rawSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(org.web3j.utils.Numeric.hexStringToByteArray(saved.raw()))),"pid",ProcessHandle.current().pid(),"locked",jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,user.id()).toPlainString()));
    }
    void stopV2Worker()throws Exception {
        var runtime=context.getBean(AuthorizedV2Config.V2Runtime.class);var coreField=runtime.getClass().getDeclaredField("core");coreField.setAccessible(true);Object core=coreField.get(runtime);var workerField=core.getClass().getDeclaredField("worker");workerField.setAccessible(true);((com.pricetrack.exchange.futures.runtime.FuturesWorker)workerField.get(core)).close();
    }
    String prepareOnly(User user,FuturesChainIdentity chain)throws Exception {
        String k=key();var d=ok(call(BASE+"/drafts",user.token(),request(Action.OPEN,null,"LONG",C),k));var draft=drafts.draft(user.id(),d.path("id").asText(),false);
        String sig=externalSign(user.slot(),"digest",org.web3j.utils.Numeric.toHexString(UserIntentV2Codec.digest(chain,draft.intent())));
        ok(call(BASE+"/drafts/"+draft.id()+"/submit",user.token(),Map.of("signature",sig),k));assertThat(ledger.operation(draft.operation()).state()).isEqualTo("PREPARED");return draft.operation();
    }
    void cancellationAndEvidenceFaults(FuturesChainIdentity chain,org.web3j.crypto.Credentials executor,FuturesRpcGateway rpc)throws Exception {
        stopV2Worker();scenario.price("75000");User cancelling=user("cancel");String id=prepareOnly(cancelling,chain);var fixed=common.fixedReport(id).orElseThrow();
        var hold=new FuturesTransactionSender.Transport(){
            public void verifyDeployment(FuturesChainIdentity c)throws java.io.IOException{rpc.verifyDeployment(c);}public BigInteger pendingNonce(String s)throws java.io.IOException{return rpc.pendingNonce(s);}public BigInteger gasPrice()throws java.io.IOException{return rpc.gasPrice();}
            public BigInteger estimate(String s,BigInteger n,BigInteger g,String e,String data)throws java.io.IOException{return rpc.estimate(s,n,g,e,data);}public boolean known(String h)throws java.io.IOException{return rpc.known(h);}
            public String broadcast(String raw)throws java.io.IOException{assertThat(txs.find(id).orElseThrow().raw()).isEqualTo(raw);throw new java.io.IOException("TEST_HELD_AFTER_DURABLE_SIGNED_BEFORE_SEND");}
        };
        var heldSender=new FuturesTransactionSender(chain,executor,txs,hold);heldSender.submit(id,fixed.getKey(),fixed.getValue());var held=txs.find(id).orElseThrow();
        assertThat(held.status()).isEqualTo("SIGNED");assertThat(rpc.receipt(held.hash())).isEmpty();assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,cancelling.id())).isEqualByComparingTo("100000");
        String cancelHash=externalSign(cancelling.slot(),"cancel",env.getRequiredProperty("app.futures.user-authorization-v2.rpc-url")+"|"+chain.engine()+"|"+common.operation(id).intentV2().nonce());
        assertThat(rpc.receipt(cancelHash).orElseThrow().isStatusOK()).isTrue();assertThat(ledger.operation(id).state()).isEqualTo("SIGNED");
        var sender=new FuturesTransactionSender(chain,executor,txs,rpc);var reconciler=new FuturesReconciler(chain,rpc,txs,sender,ledger,1);
        assertThat(reconciler.reconcile(id)).isEqualTo(FuturesReconciler.Result.WAITING);
        await("CANCELLED_RAW_NOT_MINED",20,()->{var result=reconciler.reconcile(id);assertThat(result).isIn(FuturesReconciler.Result.WAITING,FuturesReconciler.Result.REVERTED,FuturesReconciler.Result.ALREADY_FINAL);return ledger.operation(id).state().equals("REVERTED");});
        var receipt=rpc.receipt(held.hash()).orElseThrow();assertThat(receipt.isStatusOK()).isFalse();assertThat(receipt.getLogs()).isEmpty();assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());
        assertThat(txs.find(id).orElseThrow().raw()).isEqualTo(held.raw());assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,cancelling.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='REVERT_RELEASE'",Integer.class,id)).isEqualTo(1);
        evidence("cancel-signed-race",Map.of("operation",id,"hash",held.hash(),"cancelHash",cancelHash,"state",ledger.operation(id).state(),"sameRaw",true,"boundary","SIGNED held before broadcast; canonical cancellation first; exact revert reason not inferred from Receipt alone"));
        User reviewing=user("review");String review=prepareOnly(reviewing,chain);var rf=common.fixedReport(review).orElseThrow();sender.submit(review,rf.getKey(),rf.getValue());var signed=txs.find(review).orElseThrow();
        await("REVIEW_REAL_RECEIPT_MISSING",20,()->{try{return rpc.receipt(signed.hash()).isPresent();}catch(Exception x){return false;}});
        var original=rpc.receipt(signed.hash()).orElseThrow();assertThat(original.isStatusOK()).isTrue();
        var corrupted=rpc.receipt(signed.hash()).orElseThrow();
        var topics=new ArrayList<>(corrupted.getLogs().get(2).getTopics());topics.set(2,"0x"+"00".repeat(32));corrupted.getLogs().get(2).setTopics(topics);
        var injected=org.mockito.Mockito.spy(rpc);org.mockito.Mockito.doReturn(Optional.of(corrupted)).when(injected).receipt(signed.hash());
        var verifier=new FuturesReconciler(chain,injected,txs,sender,ledger,1);assertThat(verifier.reconcile(review)).isEqualTo(FuturesReconciler.Result.REVIEW_REQUIRED);
        assertThat(ledger.operation(review).state()).isEqualTo("REVIEW_REQUIRED");assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,reviewing.id())).isEqualByComparingTo("100000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='CONFIRM'",Integer.class,review)).isZero();
        evidence("review-lock",Map.of("operation",review,"hash",signed.hash(),"canonicalRealReceiptSuccess",true,"injectedOwnerTopicMismatch",true,"state",ledger.operation(review).state(),"lockedPreserved",true,"scope","real PG/Anvil transaction; test RPC response fault injection, not a forged canonical onchain event"));
    }
    void recover(FuturesRpcGateway rpc)throws Exception {
        var old=json.readTree(run().resolve("restart-prepare.evidence.json").toFile());String operation=old.path("operation").asText();
        assertThat(ProcessHandle.current().pid()).isNotEqualTo(old.path("pid").asLong());await("SECOND_JVM_CONFIRM_TIMEOUT",30,()->ledger.operation(operation).state().equals("CONFIRMED"));
        var saved=txs.find(operation).orElseThrow();assertThat(saved.hash()).isEqualTo(old.path("hash").asText());assertThat(saved.nonce()).isEqualTo(old.path("nonce").asLong());
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(org.web3j.utils.Numeric.hexStringToByteArray(saved.raw())))).isEqualTo(old.path("rawSha256").asText());
        var receipt=rpc.receipt(saved.hash()).orElseThrow();assertThat(receipt.isStatusOK()).isTrue();assertThat(receipt.getLogs()).hasSize(3);assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='CONFIRM'",Integer.class,operation)).isEqualTo(1);
        evidence("restart-recover",Map.of("operation",operation,"hash",saved.hash(),"nonce",saved.nonce(),"pid",ProcessHandle.current().pid(),"state",ledger.operation(operation).state(),"sameRaw",true));
    }
    record User(String token,long id,String slot,String owner){}
    User user(String slot)throws Exception {
        String login="v2_"+slot;var response=ok(call("/api/auth/signup",null,Map.of("loginId",login,"password","fixture-password","nickname",login),null));String token=response.path("accessToken").asText();assertThat(token).isNotBlank();
        long id=jdbc.queryForObject("SELECT id FROM users WHERE login_id=?",Long.class,login);ok(call("/api/wallet/faucet",token,Map.of(),key()));
        String owner=externalSign(slot,"address","");var challenge=ok(call(BASE+"/wallet/challenges",token,Map.of("wallet",owner),null));
        String signature=externalSign(slot,"personal",challenge.path("message").asText());ok(call(BASE+"/wallet/link",token,Map.of("challengeId",challenge.path("id").asText(),"signature",signature),null));return new User(token,id,slot,owner);
    }
    Map<String,Object> request(Action action,Position p,String side,BigInteger margin){
        var r=new LinkedHashMap<String,Object>();r.put("action",action.name());r.put("positionId",p==null?null:p.id());r.put("expectedVersion",p==null?0:p.version());r.put("side",side);r.put("leverage",10);r.put("margin",margin.toString());
        r.put("priceMinE8","5000000000000");r.put("priceMaxE8","10000000000000");r.put("quantityMin",p==null?"10000000000000000000":p.quantity().toString());r.put("quantityMax",p==null?"20000000000000000000":p.quantity().toString());r.put("ttlSeconds",120);return r;
    }
    void pgMigrationAndConstraints()throws Exception {
        // A separate owned schema models V1 before the additive V2 migration, not preserved user data.
        String namespace="migration_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE SCHEMA "+namespace);
        try(var connection=source.getConnection()){
            connection.createStatement().execute("SET search_path TO "+namespace);
            var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);var j=new JdbcTemplate(ds);
            j.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");j.update("INSERT INTO users VALUES(1),(2)");
            String ddl=new String(new org.springframework.core.io.ClassPathResource("futures-schema.sql").getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            var old=new org.springframework.core.io.ByteArrayResource(ddl.split("-- V2 supplemental routing:")[0].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(old).execute(ds);
            j.update("INSERT INTO futures_wallet_links(user_id,wallet,linked_at) VALUES(1,?,CURRENT_TIMESTAMP)","0x"+"11".repeat(20));
            j.update("INSERT INTO futures_authorized_drafts VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT')",key(),1,key(),key(),"old-key","{\"old\":true}","test-only-signature","{\"version\":1}",BigDecimal.ONE,java.sql.Timestamp.from(Instant.now()),"SIMULATED",1L,java.sql.Timestamp.from(Instant.now().plusSeconds(30)));
            String before=j.queryForObject("SELECT metadata_json FROM futures_authorized_drafts",String.class);
            new FuturesSchema(ds).initialize();assertThat(j.queryForObject("SELECT metadata_json FROM futures_authorized_drafts",String.class)).isEqualTo(before);
            assertThat(j.queryForObject("SELECT count(*) FROM futures_authorized_drafts",Integer.class)).isEqualTo(1);
            assertThatThrownBy(()->j.update("INSERT INTO futures_wallet_links(user_id,wallet,linked_at) VALUES(2,?,CURRENT_TIMESTAMP)","0x"+"11".repeat(20))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(()->j.update("INSERT INTO futures_authorized_v2_positions VALUES(?,?)",key(),"0x"+"22".repeat(20))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(()->j.update("UPDATE futures_authorized_v2_operations SET controller=controller")).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("append-only");
            assertThatThrownBy(()->j.update("DELETE FROM futures_authorized_v2_positions")).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("append-only");
            connection.createStatement().execute("SET search_path TO public");
            evidence("postgres-migration",Map.of("schema",namespace,"v1RowsPreserved",true,"walletUnique",true,"v2ForeignKey",true,"appendOnly",true));
        }
    }
    void autoLiquidation(User user,String pid,String price,FuturesRpcGateway rpc)throws Exception {
        long riskStarted=System.nanoTime();scenario.price(price);
        await("AUTO_CALL_"+user.slot(),25,()->ledger.position(pid,user.id()).deadline()>0);
        long deadline=ledger.position(pid,user.id()).deadline();
        String callId=jdbc.queryForObject("SELECT id FROM futures_operations WHERE position_id=? AND action='START_CALL'",String.class,pid);
        var callReceipt=rpc.receipt(txs.find(callId).orElseThrow().hash()).orElseThrow();long callTimestamp=rpc.block(callReceipt.getBlockNumber()).getTimestamp().longValueExact();
        assertThat(deadline-callTimestamp).isEqualTo(60);
        await("AUTO_LIQ_"+user.slot(),90,()->ledger.position(pid,user.id()).status().equals("LIQUIDATED"));
        long elapsed=System.nanoTime()-riskStarted;String liqId=jdbc.queryForObject("SELECT id FROM futures_operations WHERE position_id=? AND action='LIQUIDATE'",String.class,pid);
        var tx=txs.find(liqId).orElseThrow();var receipt=rpc.receipt(tx.hash()).orElseThrow();long mined=rpc.block(receipt.getBlockNumber()).getTimestamp().longValueExact();
        assertThat(elapsed).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(60));assertThat(receipt.isStatusOK()).isTrue();assertThat(receipt.getLogs()).hasSize(2);
        assertThat(mined).isGreaterThanOrEqualTo(deadline);assertThat(tx.report().observedAt().longValueExact()).isGreaterThanOrEqualTo(deadline);
        assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());
        evidence("auto-"+user.slot(),Map.of("position",pid,"callHash",callReceipt.getTransactionHash(),"callTimestamp",callTimestamp,"deadline",deadline,"liquidationHash",tx.hash(),"liquidationTimestamp",mined,"observedAt",tx.report().observedAt().toString(),"wallElapsedNanos",elapsed,"positionAfter",ledger.position(pid,user.id())));
    }
    Operation trade(User u,Action action,Position p,String side,BigInteger margin,boolean delayed,FuturesRpcGateway rpc)throws Exception {
        String k=key();JsonNode d;
        if(delayed){var pool=Executors.newFixedThreadPool(2);try{var one=pool.submit(()->call(BASE+"/drafts",u.token(),request(action,p,side,margin),k));var two=pool.submit(()->call(BASE+"/drafts",u.token(),request(action,p,side,margin),k));d=ok(one.get(10,TimeUnit.SECONDS));assertThat(ok(two.get(10,TimeUnit.SECONDS)).path("id").asText()).isEqualTo(d.path("id").asText());}finally{pool.shutdownNow();}}
        else d=ok(call(BASE+"/drafts",u.token(),request(action,p,side,margin),k));
        String id=d.path("id").asText();var stored=drafts.draft(u.id(),id,false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE id=?",Integer.class,stored.operation())).isZero();
        if(delayed){long before=System.nanoTime();Thread.sleep(10_100);assertThat(System.nanoTime()-before).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(10));}
        String sig=externalSign(u.slot(),"digest",org.web3j.utils.Numeric.toHexString(UserIntentV2Codec.digest(stored.metadata().chain(),stored.intent())));
        if(delayed){
            // Real PostgreSQL raises AFTER ledger reservation, forcing metadata+margin rollback.
            jdbc.execute("CREATE FUNCTION bv2_test_rollback() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned fixture injected metadata failure'; END $$");
            jdbc.execute("CREATE TRIGGER bv2_test_rollback BEFORE INSERT ON futures_authorized_v2_operations FOR EACH ROW EXECUTE FUNCTION bv2_test_rollback()");
            // Assert the precise SQLSTATE at the Spring service boundary; /error security dispatch
            // can map an otherwise unhandled servlet exception to 403, which is not a SQL proof.
            var runtime=context.getBean(AuthorizedV2Config.V2Runtime.class);var field=runtime.getClass().getDeclaredField("service");field.setAccessible(true);var command=(AuthorizedV2Service)field.get(runtime);
            try{assertThatThrownBy(()->command.submit(u.id(),id,k,sig)).isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class).satisfies(error->assertThat(((java.sql.SQLException)error.getCause()).getSQLState()).isEqualTo("P0001"));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE id=?",Integer.class,stored.operation())).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_authorized_operations WHERE operation_id=?",Integer.class,stored.operation())).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=?",Integer.class,stored.operation())).isZero();
                assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,u.id())).isZero();
                assertThat(drafts.draft(u.id(),id,false).state()).isEqualTo("DRAFT");
            }finally{jdbc.execute("DROP TRIGGER bv2_test_rollback ON futures_authorized_v2_operations");jdbc.execute("DROP FUNCTION bv2_test_rollback()");}
        }
        JsonNode accepted;
        if(delayed){var pool=Executors.newFixedThreadPool(2);try{var one=pool.submit(()->call(BASE+"/drafts/"+id+"/submit",u.token(),Map.of("signature",sig),k));var two=pool.submit(()->call(BASE+"/drafts/"+id+"/submit",u.token(),Map.of("signature",sig),k));accepted=ok(one.get(15,TimeUnit.SECONDS));assertThat(ok(two.get(15,TimeUnit.SECONDS)).path("id").asText()).isEqualTo(accepted.path("id").asText());}finally{pool.shutdownNow();}}
        else accepted=ok(call(BASE+"/drafts/"+id+"/submit",u.token(),Map.of("signature",sig),k));
        String operation=accepted.path("id").asText();assertThat(operation).isEqualTo(stored.operation());
        await("V2_SETTLEMENT_TIMEOUT",25,()->Set.of("CONFIRMED","REVIEW_REQUIRED","REVERTED","REJECTED").contains(ledger.operation(operation).state()));assertThat(ledger.operation(operation).state()).isEqualTo("CONFIRMED");
        var tx=txs.find(operation).orElseThrow();var receipt=rpc.receipt(tx.hash()).orElseThrow();assertThat(receipt.isStatusOK()).isTrue();assertThat(receipt.getLogs()).hasSize(3);assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());
        if(delayed)assertThat(tx.report().observedAt().subtract(stored.intent().issuedAt())).isGreaterThanOrEqualTo(BigInteger.TEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='CONFIRM'",Integer.class,operation)).isEqualTo(1);
        assertThat(ok(call(BASE+"/drafts/"+id+"/submit",u.token(),Map.of("signature",sig),k)).path("id").asText()).isEqualTo(operation);
        evidence("trade-"+operation,Map.of("action",action.name(),"txHash",tx.hash(),"blockHash",receipt.getBlockHash(),"logs",receipt.getLogs(),"intentIssuedAt",stored.intent().issuedAt().toString(),"reportObservedAt",tx.report().observedAt().toString(),"state",ledger.operation(operation).state()));
        return ledger.operation(operation);
    }
    @Test void actualPostgresRuntimeAndAuthorizedTrades()throws Exception {
        String prefix="app.futures.user-authorization-v2.";var exec=org.web3j.crypto.Credentials.create(env.getRequiredProperty(prefix+"executor-private-key"));var signer=org.web3j.crypto.Credentials.create(env.getRequiredProperty(prefix+"signer-private-key"));
        var chain=new FuturesChainIdentity(BigInteger.valueOf(31337),env.getRequiredProperty(prefix+"controller-address"),exec.getAddress(),signer.getAddress(),env.getRequiredProperty(prefix+"rules-address"),"2");
        try(var rpc=new FuturesRpcGateway(new HttpService(env.getRequiredProperty(prefix+"rpc-url")),chain,env.getRequiredProperty(prefix+"controller-code-hash"),env.getRequiredProperty(prefix+"rules-code-hash"))){
            rpc.verifyDeployment(chain);assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("exchange_futures_e2e");
            if("recover".equals(System.getenv("DAPP_V2_E2E_STAGE"))){recover(rpc);cancellationAndEvidenceFaults(chain,exec,rpc);return;}
            if("reprice-recover".equals(System.getenv("DAPP_V2_E2E_STAGE"))){recover(rpc);return;}
            pgMigrationAndConstraints();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE (tgname='futures_authorized_v2_operations_immutable' AND tgrelid='public.futures_authorized_v2_operations'::regclass) OR (tgname='futures_authorized_v2_positions_immutable' AND tgrelid='public.futures_authorized_v2_positions'::regclass)",Integer.class)).isEqualTo(2);
            await("CLOCK_PRECONDITION",25,()->{try{return rpc.block(rpc.height()).getTimestamp().longValueExact()<=Instant.now().getEpochSecond()+1;}catch(Exception e){return false;}});
            ledger.initializeEmptyFixture();User normal=user("normal"),longUser=user("long"),shortUser=user("short");
            if("cancel".equals(System.getenv("DAPP_V2_E2E_STAGE"))){AuthorizedV2CancellationPostgresGate.run(this,chain,exec,signer,rpc,normal);return;}
            if("audit".equals(System.getenv("DAPP_V2_E2E_STAGE"))){AuthorizedV2AuditPostgresGate.run(this,chain,exec,signer,rpc);return;}
            if("race".equals(System.getenv("DAPP_V2_E2E_STAGE"))){revisionRaceGate(normal,longUser,chain,exec,signer,rpc);return;}
            if("reprice".equals(System.getenv("DAPP_V2_E2E_STAGE"))){repriceGate(normal,chain,exec,signer,rpc);return;}
            if("tail".equals(System.getenv("DAPP_V2_E2E_STAGE"))){
                var openedTail=trade(normal,Action.OPEN,null,"LONG",C,false,rpc);var tailPosition=ledger.position(openedTail.positionId(),normal.id());trade(normal,Action.CLOSE,tailPosition,"LONG",BigInteger.ZERO,false,rpc);
                chainNegativeGate(normal,openedTail,chain,signer,rpc);
                evidence("gate",Map.of("identity",chain,"normal",tailPosition.id(),"postgres",true,"actualRuntime",true,"scope","NEW supplementary identity/error/restart Gate; automatic and PG concurrency evidence in preceding unchanged-productHash run"));
                prepareResponseLoss(normal,chain,exec,rpc);return;
            }
            var opened=trade(normal,Action.OPEN,null,"LONG",C,true,rpc);var p=ledger.position(opened.positionId(),normal.id());
            assertThat(call(BASE+"/positions/"+p.id(),longUser.token(),null,null).getStatusCode().is4xxClientError()).isTrue();
            String normalPid=p.id();scenario.price("70000");await("RECOVERY_CALL",25,()->ledger.position(normalPid,normal.id()).riskStatus().equals("GRACE"));
            scenario.price("75000");await("RECOVERY_CLEAR",25,()->ledger.position(normalPid,normal.id()).riskStatus().equals("HEALTHY"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE position_id=? AND action='CLEAR_CALL' AND state='CONFIRMED'",Integer.class,normalPid)).isEqualTo(1);
            p=ledger.position(normalPid,normal.id());trade(normal,Action.TOP_UP,p,"LONG",C,false,rpc);
            scenario.price("60000");await("TOPUP_RECOVERY_CALL",25,()->ledger.position(normalPid,normal.id()).riskStatus().equals("GRACE"));
            p=ledger.position(normalPid,normal.id());trade(normal,Action.TOP_UP,p,"LONG",C,false,rpc);assertThat(ledger.position(normalPid,normal.id()).riskStatus()).isEqualTo("HEALTHY");
            evidence("grace-recovery",Map.of("position",normalPid,"priceRecoveryClear",true,"topupRecovery",true,"operationStates",jdbc.queryForList("SELECT id,action,state FROM futures_operations WHERE position_id=?",normalPid)));
            p=ledger.position(normalPid,normal.id());trade(normal,Action.CLOSE,p,"LONG",BigInteger.ZERO,false,rpc);
            assertThat(ledger.position(p.id(),normal.id()).status()).isEqualTo("CLOSED");assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,normal.id())).isZero();
            scenario.price("75000");var lo=trade(longUser,Action.OPEN,null,"LONG",C,false,rpc);
            BigInteger beforeNonce=rpc.userNonce(longUser.owner());String cancelHash=externalSign(longUser.slot(),"cancel",env.getRequiredProperty(prefix+"rpc-url")+"|"+chain.engine()+"|"+beforeNonce);
            var cancellation=rpc.receipt(cancelHash).orElseThrow();assertThat(cancellation.isStatusOK()).isTrue();assertThat(rpc.block(cancellation.getBlockNumber()).getHash()).isEqualTo(cancellation.getBlockHash());assertThat(rpc.userNonce(longUser.owner())).isEqualTo(beforeNonce.add(BigInteger.ONE));
            evidence("cancel-before-automatic",Map.of("hash",cancelHash,"blockHash",cancellation.getBlockHash(),"nonceBefore",beforeNonce.toString(),"nonceAfter",rpc.userNonce(longUser.owner()).toString(),"positionStillOpen",ledger.position(lo.positionId(),longUser.id()).status()));
            autoLiquidation(longUser,lo.positionId(),"60000",rpc);
            scenario.price("75000");var so=trade(shortUser,Action.OPEN,null,"SHORT",C,false,rpc);
            autoLiquidation(shortUser,so.positionId(),"90000",rpc);
            scenario.price("75000");chainNegativeGate(normal,opened,chain,signer,rpc);
            BigInteger wallets=jdbc.queryForObject("SELECT sum(amount) FROM user_balances WHERE symbol='mKRW'",BigDecimal.class).movePointRight(18).toBigIntegerExact();
            BigInteger house=jdbc.queryForObject("SELECT amount FROM futures_house WHERE id=1",BigDecimal.class).toBigIntegerExact();
            assertThat(wallets.add(house)).isEqualTo(BigInteger.valueOf(13_000_000).multiply(BigInteger.TEN.pow(18)));
            assertThat(jdbc.queryForObject("SELECT sum(locked_amount) FROM user_balances WHERE symbol='mKRW'",BigDecimal.class)).isZero();assertThat(jdbc.queryForObject("SELECT reserved FROM futures_house WHERE id=1",BigDecimal.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE wallet_delta+house_delta<>0",Integer.class)).isZero();
            evidence("settlement-conservation",Map.of("walletsWei",wallets.toString(),"houseWei",house.toString(),"totalWei",wallets.add(house).toString(),"journal",jdbc.queryForList("SELECT operation_id,entry_type,wallet_delta,locked_delta,house_delta,bad_debt FROM futures_journal"),"scope","DB mock collateral; not ERC20 custody or full V2 audit"));
            evidence("gate",Map.of("identity",chain,"normal",p.id(),"long",lo.positionId(),"short",so.positionId(),"postgres",true,"actualRuntime",true,"audit","INCONCLUSIVE_FULL_V2_COVERAGE_NOT_IMPLEMENTED"));
            prepareResponseLoss(normal,chain,exec,rpc);
        }
    }

    /** Actual PostgreSQL row-lock/CAS races. No user/browser signer or price-control product endpoint. */
    void revisionRaceGate(User first,User second,FuturesChainIdentity chain,org.web3j.crypto.Credentials executor,org.web3j.crypto.Credentials signer,FuturesRpcGateway rpc)throws Exception {
        stopV2Worker();scenario.price("75000");String id=prepareOnly(first,chain);
        var revisions=context.getBean(AuthorizedV2RevisionStore.class);
        var refresh=new AuthorizedV2Repricing(revisions,txs,market,chain,signer,op->AuthorizedV2Config.recheckBeforeSigning(chain,market,txs,rpc,op));
        var old=txs.fixedAuthorizedReport(id).orElseThrow();var mark=new AuthorizedV2IntentStore(source,ledger,drafts,chain).evidence(id);
        var a=txs.authorization(id);String oldData=UserIntentCodec.encode(old.getKey(),old.getValue(),a);
        long nonce=rpc.pendingNonce(chain.executor()).longValueExact();
        byte[] bytes=org.web3j.crypto.TransactionEncoder.signMessage(org.web3j.crypto.RawTransaction.createTransaction(BigInteger.valueOf(nonce),BigInteger.ONE,BigInteger.valueOf(2000000),chain.engine(),BigInteger.ZERO,oldData),31337,executor);
        String raw=org.web3j.utils.Numeric.toHexString(bytes),hash=org.web3j.utils.Numeric.toHexString(org.web3j.crypto.Hash.sha3(bytes));
        scenario.price("75001");var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> attempt=()->{go.await();try{return refresh.reprice(id,old.getKey(),mark);}catch(FuturesLedger.Failure conflict){assertThat(conflict.code).isEqualTo("REVISION_CONFLICT");return false;}};
        int wins;
        try{var one=pool.submit(attempt);var two=pool.submit(attempt);go.countDown();wins=(one.get(15,TimeUnit.SECONDS)?1:0)+(two.get(15,TimeUnit.SECONDS)?1:0);}finally{pool.shutdownNow();}
        assertThat(wins).isEqualTo(1);assertThat(txs.executionRevision(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_v2_execution_revisions WHERE operation_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='RESERVE'",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='REPRICE'",Integer.class,id)).isEqualTo(1);
        assertThatThrownBy(()->ledger.rejectUnsignedAtReport(id,"PRICE_CHANGED",old.getKey())).hasMessage("REVISION_CONFLICT");
        assertThatThrownBy(()->txs.saveSigned(id,chain,old.getKey(),old.getValue(),raw,hash,nonce)).hasMessage("REVISION_CONFLICT");
        assertThat(txs.find(id)).isEmpty();assertThat(ledger.operation(id).state()).isEqualTo("PREPARED");
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,first.id())).isEqualByComparingTo("100000");
        // Opposite ordering on another owned account: durable SIGNED first, revision attempt second.
        scenario.price("75000");String signedId=prepareOnly(second,chain);var fixed=txs.fixedAuthorizedReport(signedId).orElseThrow();
        var hold=new FuturesTransactionSender.Transport(){
            public void verifyDeployment(FuturesChainIdentity c)throws java.io.IOException{rpc.verifyDeployment(c);}
            public BigInteger pendingNonce(String s)throws java.io.IOException{return rpc.pendingNonce(s);}
            public BigInteger gasPrice()throws java.io.IOException{return rpc.gasPrice();}
            public BigInteger estimate(String s,BigInteger n,BigInteger g,String e,String data)throws java.io.IOException{return rpc.estimate(s,n,g,e,data);}
            public boolean known(String h)throws java.io.IOException{return rpc.known(h);}
            public String broadcast(String unused)throws java.io.IOException{throw new java.io.IOException("OWNED_SIGNED_HOLD_NO_SEND");}
        };
        new FuturesTransactionSender(chain,executor,txs,hold).submit(signedId,fixed.getKey(),fixed.getValue(),()->AuthorizedV2Config.recheckBeforeSigning(chain,market,txs,rpc,signedId));
        var signed=txs.find(signedId).orElseThrow();scenario.price("75001");var fresh=market.capture();
        var next=patched(fixed.getKey(),FuturesReport.class,Map.of("priceE8",fresh.price(),"entryPriceE8",fresh.price(),"quantity",com.pricetrack.exchange.futures.math.FuturesMath.quantity(C,10,fresh.price()),"observedAt",BigInteger.valueOf(fresh.observedAt().getEpochSecond()),"validUntil",BigInteger.valueOf(fresh.observedAt().getEpochSecond()+30)));
        String ps=FuturesReportCodec.sign(next,chain.chainId(),chain.rules(),signer.getEcKeyPair());
        assertThatThrownBy(()->revisions.replaceUnsigned(signedId,fixed.getKey(),next,ps,fresh)).hasMessage("REVISION_CONFLICT");
        var same=txs.find(signedId).orElseThrow();assertThat(same.raw()).isEqualTo(signed.raw());assertThat(same.hash()).isEqualTo(signed.hash());assertThat(same.nonce()).isEqualTo(signed.nonce());assertThat(txs.executionRevision(signedId)).isZero();
        evidence("gate",Map.of("scope","REAL_POSTGRESQL_REVISION_ROW_LOCKS_NOT_BROWSER","concurrentWinners",wins,"revision",1,"staleRejectBlocked",true,"oldSaveSignedBlocked",true,"signedFirstRevisionBlocked",true,"reservationAndJournalSingle",true,"noBroadcast",true));
    }

    /** Real PG/HTTP/Anvil with deterministic test-provider ticks. NOT product Synthetic/Chrome. */
    void repriceGate(User user,FuturesChainIdentity chain,org.web3j.crypto.Credentials executor,org.web3j.crypto.Credentials signer,FuturesRpcGateway rpc)throws Exception {
        stopV2Worker();var revisionStore=context.getBean(AuthorizedV2RevisionStore.class);
        var refresh=new AuthorizedV2Repricing(revisionStore,txs,market,chain,signer,id->AuthorizedV2Config.recheckBeforeSigning(chain,market,txs,rpc,id));
        Position position=null;var results=new ArrayList<Map<String,Object>>();
        for(Action action:List.of(Action.OPEN,Action.TOP_UP,Action.CLOSE,Action.OPEN)){
            boolean last=results.size()==3;scenario.price("75000");String k=key();
            var draftJson=ok(call(BASE+"/drafts",user.token(),request(action,action==Action.OPEN?null:position,"LONG",action==Action.CLOSE?BigInteger.ZERO:C),k));
            var draft=drafts.draft(user.id(),draftJson.path("id").asText(),false);
            String us=externalSign(user.slot(),"digest",org.web3j.utils.Numeric.toHexString(UserIntentV2Codec.digest(chain,draft.intent())));
            ok(call(BASE+"/drafts/"+draft.id()+"/submit",user.token(),Map.of("signature",us),k));String id=draft.operation();
            var old=txs.fixedAuthorizedReport(id).orElseThrow();var oldMark=new AuthorizedV2IntentStore(source,ledger,drafts,chain).evidence(id);
            if(results.isEmpty()){
                jdbc.execute("CREATE FUNCTION revision_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned revision commit fault'; END $$");
                jdbc.execute("CREATE TRIGGER revision_failure BEFORE UPDATE ON futures_v2_execution_heads FOR EACH ROW EXECUTE FUNCTION revision_failure()");
                scenario.price("75001");
                try{assertThatThrownBy(()->refresh.reprice(id,old.getKey(),oldMark)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
                finally{jdbc.execute("DROP TRIGGER revision_failure ON futures_v2_execution_heads");jdbc.execute("DROP FUNCTION revision_failure()");}
                assertThat(txs.fixedAuthorizedReport(id).orElseThrow()).isEqualTo(old);assertThat(txs.executionRevision(id)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=?",Integer.class,id)).isEqualTo(1);scenario.price("75000");
            }
            var estimates=new java.util.concurrent.atomic.AtomicInteger();
            var transport=new FuturesTransactionSender.Transport(){
                public void verifyDeployment(FuturesChainIdentity c)throws java.io.IOException{rpc.verifyDeployment(c);}
                public BigInteger pendingNonce(String s)throws java.io.IOException{return rpc.pendingNonce(s);}
                public BigInteger gasPrice()throws java.io.IOException{return rpc.gasPrice();}
                public BigInteger estimate(String s,BigInteger n,BigInteger g,String e,String data)throws java.io.IOException{var gas=rpc.estimate(s,n,g,e,data);if(estimates.incrementAndGet()==1)scenario.price("75001");return gas;}
                public boolean known(String hash)throws java.io.IOException{return rpc.known(hash);}
                public String broadcast(String raw)throws java.io.IOException{String hash=rpc.broadcast(raw);if(last)throw new java.io.IOException("OWNED_RESPONSE_LOSS_AFTER_REAL_SEND");return hash;}
            };
            var sender=new FuturesTransactionSender(chain,executor,txs,transport);
            var execution=new com.pricetrack.exchange.futures.runtime.FuturesExecution(ledger,new AuthorizedV2IntentStore(source,ledger,drafts,chain),market,chain,signer,txs,sender,id2->AuthorizedV2Config.recheckBeforeSigning(chain,market,txs,rpc,id2),refresh::reprice);
            execution.submitPrepared(id);var signed=txs.find(id).orElseThrow();assertThat(txs.executionRevision(id)).isEqualTo(1);assertThat(estimates.get()).isEqualTo(2);
            assertThat(signed.report().priceE8()).isEqualTo(new BigInteger("7500100000000"));assertThat(common.fixedReport(id).orElseThrow()).isEqualTo(old);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_v2_execution_revisions WHERE operation_id=?",Integer.class,id)).isEqualTo(2);
            assertThatThrownBy(()->jdbc.update("UPDATE futures_v2_execution_revisions SET provider='OTHER' WHERE operation_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            if(last){
                assertThat(signed.status()).isEqualTo("SIGNED");evidence("restart-prepare",Map.of("operation",id,"hash",signed.hash(),"nonce",signed.nonce(),"rawSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(org.web3j.utils.Numeric.hexStringToByteArray(signed.raw()))),"pid",ProcessHandle.current().pid(),"revision",1));
            }else{
                var reconciler=new FuturesReconciler(chain,rpc,txs,sender,ledger,1);
                await("REPRICE_CANONICAL_CONFIRM",25,()->{reconciler.reconcile(id);return ledger.operation(id).state().equals("CONFIRMED");});
                var receipt=rpc.receipt(signed.hash()).orElseThrow();assertThat(receipt.isStatusOK()).isTrue();assertThat(receipt.getLogs()).hasSize(3);assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());
                position=ledger.position(draft.position(),user.id());
                if(action==Action.CLOSE){assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,user.id())).isZero();assertThat(jdbc.queryForObject("SELECT reserved FROM futures_house",BigDecimal.class)).isZero();}
                results.add(Map.of("action",action,"operation",id,"txHash",signed.hash(),"blockHash",receipt.getBlockHash(),"revision",1,"events",3,"state",ledger.operation(id).state()));
            }
        }
        evidence("gate",Map.of("scope","REAL_PG_ANVIL_HTTP_CONTROLLED_PROVIDER_1S_TICK_NOT_CHROME_OR_PRODUCT_SYNTHETIC","trades",results,"rollback",true,"appendOnly",true,"signedRevision1SecondJvmPending",true));
    }
}
