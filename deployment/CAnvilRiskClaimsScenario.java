import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import org.web3j.crypto.*;

/** Actual C5a2 only: controlled Oracle, trial EOAs, no Spring/JWT/product market or MetaMask. */
public final class CAnvilRiskClaimsScenario {
    final CAnvilContractGate g;
    static final BigInteger Z=BigInteger.ZERO,ONE=BigInteger.ONE;
    String phase="BOOT",riskVault,claimsVault;
    int operation=0,stageId=0;
    CAnvilRiskClaimsScenario(CAnvilContractGate gate){g=gate;}
    record Position(String role,String id,String account,int side) {}
    record UserCall(Report report,Intent intent,Call call) {}
    record Grace(Position position,BigInteger timestamp,BigInteger deadline,long mono,long wall) {}
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static BigInteger u(long n){return CAnvilContractGate.units(n);}
    static BigInteger p(long n){return b(n).multiply(FuturesMath.PRICE_SCALE);}
    static void eq(Object a,Object b){CAnvilContractGate.eq(a,b);}
    static void check(boolean c,String code){if(!c)throw new IllegalStateException("C5A2_"+code);}
    public static void main(String[] args)throws Exception{
        check(args.length==2&&args[0].equals("--self-test"),"SELF_TEST_SCOPE");
        selfTest();System.out.println("C5A2_OFFLINE_SELF_TEST_PASS");
    }
    void stage(String name)throws Exception{
        check(name.matches("[A-Z0-9_]+"),"STAGE");phase=name;
        g.save(String.format("c5a2-stage-%02d.json",++stageId),CAnvilContractGate.JSON.createObjectNode().put("stage",name).put("elapsedMs",(System.nanoTime()-g.started)/1_000_000));
    }
    void run()throws Exception{
        try{bootstrap();riskScenario();claimsScenario();stage("FINAL_CHECK");supply();
            JsonNode manifest=g.read(g.fixture.resolve("manifest.json"));
            g.save("gate-result.json",CAnvilContractGate.JSON.createObjectNode().put("result","PASS")
                .put("scope","C5A2_ACTUAL_LOCAL_ANVIL_RISK_CLAIMS_ONLY").put("sourceIdentity",manifest.path("sourceIdentity").asText())
                .put("chainId",CAnvilContractGate.CHAIN.toString()).put("genesisHash",g.genesis).put("token",g.token)
                .put("riskVault",riskVault).put("claimsVault",claimsVault).put("riskWall60Run",true).put("claimsInsolvencyRun",true)
                .put("completeCoverage",false).put("postgresRun",false).put("realMetaMask",false).put("productSynthetic",false)
                .put("serverShutdownAcceptance",false).put("freeWithdrawalWithoutServerOrOracleReport",true));
        }catch(Exception failure){String code=failure.getMessage();if(code==null||!code.matches("C5A[12]_[A-Z0-9_]+"))code="C5A2_SAFE_STOP";
            g.save("c5a2-failure.json",CAnvilContractGate.JSON.createObjectNode().put("stage",phase).put("code",code).put("retry",false));throw failure;}
    }
    void bootstrap()throws Exception{
        stage("BOOT");check(Runtime.version().feature()==21,"JDK21");
        check(Files.isDirectory(g.fixture)&&Files.isDirectory(g.output),"PATHS");
        for(String n:List.of("http.proxyHost","https.proxyHost","socksProxyHost"))check(System.getProperty(n)==null,"PROXY_PROPERTY");
        for(String n:List.of("HTTP_PROXY","HTTPS_PROXY","ALL_PROXY"))check(System.getenv(n)==null,"PROXY_ENV");
        JsonNode m=g.read(g.fixture.resolve("manifest.json"));
        eq(m.path("scope").asText(),"C5A2_RISK_CLAIMS_ANVIL_ONLY");eq(m.path("rpc").asText(),"http://anvil:8545");
        check(m.path("runId").asText().matches("canvil-[0-9a-f]{16}")&&m.path("sourceIdentity").asText().matches("[0-9a-f]{64}"),"MANIFEST");
        g.save("private-write-probe.json",CAnvilContractGate.JSON.createObjectNode().put("runId",m.path("runId").asText()));
        eq(g.read(g.output.resolve("private-write-probe.json")).path("runId").asText(),m.path("runId").asText());
        g.save("transport-policy.json",CAnvilContractGate.JSON.createObjectNode().put("javaRuntime",Runtime.version().toString()).put("retryConnect",false).put("retryAlways",false).put("maxAttempts",1));
        g.verifySourceMetadata();
        for(String role:List.of("treasury","admin","relayer","oracle","userA","userB"))g.keys.put(role,Keys.createEcKeyPair(new SecureRandom()));
        ObjectNode keys=CAnvilContractGate.JSON.createObjectNode(),addresses=CAnvilContractGate.JSON.createObjectNode();
        for(var e:g.keys.entrySet()){keys.put(e.getKey(),e.getValue().getPrivateKey().toString(16));addresses.put(e.getKey(),CAnvilContractGate.address(e.getValue()));}
        check(new HashSet<>(g.keys.values().stream().map(CAnvilContractGate::address).toList()).size()==6,"DISTINCT_KEYS");
        g.save("keys.private.json",keys);g.save("addresses.json",addresses);
        eq(CAnvilContractGate.number(g.rpc("eth_chainId")),CAnvilContractGate.CHAIN);
        g.genesis=g.rpc("eth_getBlockByNumber","0x0",false).path("hash").asText();CAbiModels.hash(g.genesis);
        for(var key:g.keys.values())g.rpc("anvil_setBalance",CAnvilContractGate.address(key),CAnvilContractGate.hex(u(100)));
        g.token=g.deploy("CMockCollateral",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.addr("treasury"))),g.keys.get("treasury"));
        g.verifyRuntime("CMockCollateral",g.token);
        eq(g.callNum(g.token,"totalSupply()"),CAnvilContractGate.SUPPLY);eq(g.callNum(g.token,"decimals()"),b(18));
        g.transfer("treasury",g.addr("userA"),u(1_000_000));g.transfer("treasury",g.addr("userB"),u(1_000_000));
    }
    void deploy(String name)throws Exception{
        g.symbol=CAnvilContractGate.hash("C5A2_SYMBOL_"+name);g.policy=CAnvilContractGate.hash("C5A2_POLICY_"+name);
        g.controller=g.deploy("CCollateralizedFuturesController",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.token),CAnvilContractGate.addressWord(g.addr("relayer")),CAnvilContractGate.addressWord(g.addr("oracle")),CAnvilContractGate.addressWord(g.addr("admin")),g.symbol,g.policy),g.keys.get("treasury"));
        g.rules=g.callAddress(g.controller,"rules()");g.vault=g.callAddress(g.controller,"vault()");
        check(Set.of(g.token,g.controller,g.rules,g.vault).size()==4,"CONTRACTS_DISTINCT");
        g.verifyRuntime("CCollateralizedFuturesController",g.controller,"-"+name);g.verifyRuntime("FuturesEngine",g.rules,"-"+name);g.verifyRuntime("CMarginVault",g.vault,"-"+name);
        eq(g.callAddress(g.vault,"asset()"),g.token);eq(g.callAddress(g.vault,"controller()"),g.controller);
        eq(g.callAddress(g.rules,"executor()"),g.controller);eq(g.callAddress(g.rules,"priceSigner()"),g.addr("oracle"));
        eq(g.callAddress(g.controller,"relayer()"),g.addr("relayer"));eq(g.callAddress(g.controller,"pauseAdmin()"),g.addr("admin"));
        eq(g.call(g.rules,"supportedSymbolHash()"),g.symbol);eq(g.call(g.rules,"policyHash()"),g.policy);
        g.save("deployment-"+name+".json",CAnvilContractGate.JSON.createObjectNode().put("controller",g.controller).put("rules",g.rules).put("vault",g.vault).put("symbol",g.symbol).put("policy",g.policy));
    }
    Position position(String role,String name,int side)throws Exception{return new Position(role,CAnvilContractGate.hash("C5A2_POS_"+name),g.call(g.controller,"accountRef(address)",CAnvilContractGate.addressWord(g.addr(role))),side);}
    BigInteger now()throws Exception{return CAnvilContractGate.number(g.rpc("eth_getBlockByNumber","latest",false).path("timestamp"));}
    String state(Position x)throws Exception{return g.call(g.rules,"getPosition(bytes32)",x.id());}
    BigInteger field(String state,int n){return CAnvilContractGate.wordNum(state,n);}
    BigInteger nonce(String role)throws Exception{return g.callNum(g.controller,"nonces(address)",CAnvilContractGate.addressWord(g.addr(role)));}
    Report report(Position x,int action,BigInteger price,BigInteger collateralAfter)throws Exception{
        String s=state(x);BigInteger t=now(),q=action==0?FuturesMath.quantity(collateralAfter,10,price):field(s,3);
        return new Report(CAnvilContractGate.hash("C5A2_OP_"+(++operation)),x.id(),x.account(),g.symbol,b(action),field(s,7),field(s,8),b(x.side()),b(10),q,action==0?price:field(s,4),field(s,5),collateralAfter,price,t,t.add(b(30)),g.policy,g.controller);
    }
    UserCall userCall(Position x,int action,BigInteger price,BigInteger collateral,boolean direct)throws Exception{
        Report r=report(x,action,price,collateral);BigInteger n=nonce(x.role());
        Intent i=new Intent(g.addr(x.role()),g.addr("relayer"),r.operationId(),x.id(),x.account(),g.symbol,r.action(),r.expectedVersion(),r.expectedCallEpoch(),r.side(),r.leverage(),action==0?Z:r.entryPriceE8(),r.collateralBefore(),r.collateralAfter(),price,price,r.quantity(),r.quantity(),n,r.observedAt(),r.observedAt().add(b(120)),g.policy);
        String rd=g.crypto.reportDigest(r,CAnvilContractGate.CHAIN,g.rules),id=g.crypto.intentDigest(i,CAnvilContractGate.CHAIN,g.controller);
        String ps=CAnvilContractGate.signature(rd,g.keys.get("oracle"));
        Call c=direct?new Direct(r,ps,i):new Relayed(r,ps,i,CAnvilContractGate.signature(id,g.keys.get(x.role())));
        return new UserCall(r,i,c);
    }
    JsonNode user(Position x,int action,long price,long collateral,boolean direct,long paid,long claim,long unpaid)throws Exception{
        UserCall c=userCall(x,action,p(price),u(collateral),direct);Report r=c.report();
        BigInteger n=nonce(x.role()),house=g.callNum(g.vault,"houseBalance()"),free=g.free(g.addr(x.role()));
        var mark=mark(x,r);var expected=new ArrayList<CAnvilContractGate.Expected>();
        expected.add(event(g.rules,EmitterKind.RULES,new OperationApplied(r.operationId(),x.id(),r.action(),r.expectedVersion().add(ONE),r.priceE8(),r.collateralAfter(),mark.unrealized(),action==4?mark.realized():Z,action==4?mark.badDebt():Z,r.expectedCallEpoch(),field(state(x),9))));
        expected.add(event(g.rules,EmitterKind.RULES,new FuturesReportConsumed(r.operationId(),r.observedAt(),r.validUntil())));
        if(action==4){if(claim!=0)expected.add(event(g.vault,EmitterKind.VAULT,new ProfitClaimCreated(b(claim),g.addr(x.role()),x.id(),r.operationId(),u(unpaid))));
            expected.add(event(g.vault,EmitterKind.VAULT,new PositionSettled(r.operationId(),x.id(),g.addr(x.role()),r.collateralAfter(),mark.realized(),mark.badDebt(),u(paid),b(claim))));}
        else expected.add(event(g.vault,EmitterKind.VAULT,new CollateralLocked(r.operationId(),x.id(),g.addr(x.role()),r.collateralAfter().subtract(r.collateralBefore()),r.collateralAfter())));
        expected.add(event(g.controller,EmitterKind.CONTROLLER,new CUserIntentExecuted(r.operationId(),x.id(),g.addr(x.role()),g.crypto.intentDigest(c.intent(),CAnvilContractGate.CHAIN,g.controller),g.crypto.reportDigest(r,CAnvilContractGate.CHAIN,g.rules),r.action(),n,direct)));
        JsonNode receipt=g.send(g.keys.get(direct?x.role():"relayer"),g.controller,g.abi.encode(c.call()),true);g.expect(receipt,expected);
        eq(nonce(x.role()),n.add(ONE));eq(g.callNum(g.rules,"usedOperations(bytes32)",r.operationId()),ONE);eq(g.callAddress(g.controller,"positionOwner(bytes32)",x.id()),g.addr(x.role()));
        String after=state(x);eq(field(after,7),r.expectedVersion().add(ONE));eq(field(after,3),r.quantity());eq(field(after,5),r.collateralAfter());
        if(action==4){eq(field(after,6),b(3));terminal(x);eq(g.free(g.addr(x.role())),free.add(r.collateralAfter()).add(mark.realized().min(Z)).add(u(paid)));eq(g.callNum(g.vault,"houseBalance()"),house.subtract(u(paid)).subtract(mark.realized().min(Z)));}
        else{eq(field(after,6),ONE);eq(g.free(g.addr(x.role())),free.subtract(r.collateralAfter().subtract(r.collateralBefore())));}
        invariant();return receipt;
    }
    FuturesMath.Mark mark(Position x,Report r){return FuturesMath.mark(x.side()==0?FuturesMath.Side.LONG:FuturesMath.Side.SHORT,r.quantity(),r.entryPriceE8(),r.collateralAfter(),r.priceE8());}
    static CAnvilContractGate.Expected event(String emitter,EmitterKind kind,Event e){return new CAnvilContractGate.Expected(emitter,kind,e);}
    void invariant()throws Exception{
        BigInteger free=g.free(g.addr("userA")).add(g.free(g.addr("userB"))),locked=g.callNum(g.vault,"lockedBalance(address)",CAnvilContractGate.addressWord(g.addr("userA"))).add(g.callNum(g.vault,"lockedBalance(address)",CAnvilContractGate.addressWord(g.addr("userB"))));
        g.cashState(free,locked,g.callNum(g.vault,"houseBalance()"));
        BigInteger total=Z,next=g.callNum(g.vault,"nextClaimId()");check(next.compareTo(b(3))<=0,"CLAIM_BOUND");
        for(BigInteger id=ONE;id.compareTo(next)<0;id=id.add(ONE)){String c=g.call(g.vault,"claims(uint256)",CAnvilContractGate.word(id));eq(field(c,3),field(c,4).add(field(c,5)));total=total.add(field(c,5));}
        eq(g.callNum(g.vault,"totalUnpaidProfit()"),total);eq(g.callNum(g.token,"totalSupply()"),CAnvilContractGate.SUPPLY);
    }
    void terminal(Position x)throws Exception{String lock=g.call(g.vault,"positionLocks(bytes32)",x.id());eq(CAnvilContractGate.wordAddress(lock,0),g.addr(x.role()));eq(field(lock,1),Z);eq(field(lock,2),ONE);eq(g.call(g.rules,"activePosition(bytes32)",x.account()),CAnvilContractGate.ZEROHASH);}
    JsonNode risk(Position x,RiskAction action,long price)throws Exception{
        int a=action==RiskAction.START_CALL?2:action==RiskAction.CLEAR_CALL?3:5;String s=state(x);Report r=report(x,a,p(price),field(s,5));
        BigInteger n=nonce(x.role()),house=g.callNum(g.vault,"houseBalance()");var m=mark(x,r);
        JsonNode receipt=g.send(g.keys.get("relayer"),g.controller,g.abi.encode(new Risk(action,r,CAnvilContractGate.signature(g.crypto.reportDigest(r,CAnvilContractGate.CHAIN,g.rules),g.keys.get("oracle")))),true);
        BigInteger at=CAnvilContractGate.number(g.rpc("eth_getBlockByHash",receipt.path("blockHash").asText(),false).path("timestamp"));
        BigInteger epoch=field(s,8).add(action==RiskAction.START_CALL?ONE:Z),deadline=action==RiskAction.START_CALL?at.add(b(60)):action==RiskAction.CLEAR_CALL?Z:field(s,9);
        var es=new ArrayList<CAnvilContractGate.Expected>();es.add(event(g.rules,EmitterKind.RULES,new OperationApplied(r.operationId(),x.id(),r.action(),r.expectedVersion().add(ONE),r.priceE8(),r.collateralAfter(),m.unrealized(),a==5?m.realized():Z,a==5?m.badDebt():Z,epoch,deadline)));
        es.add(event(g.rules,EmitterKind.RULES,new FuturesReportConsumed(r.operationId(),r.observedAt(),r.validUntil())));
        if(a==5){check(r.observedAt().compareTo(deadline)>=0&&at.compareTo(deadline)>=0,"LIQ_DEADLINE");eq(m.realized(),u(-1000));eq(m.badDebt(),u(1000));es.add(event(g.vault,EmitterKind.VAULT,new PositionSettled(r.operationId(),x.id(),g.addr(x.role()),r.collateralAfter(),m.realized(),m.badDebt(),Z,Z)));}
        es.add(event(g.controller,EmitterKind.CONTROLLER,new CRiskExecuted(r.operationId(),x.id(),g.addr(x.role()),g.crypto.reportDigest(r,CAnvilContractGate.CHAIN,g.rules),r.action())));g.expect(receipt,es);
        String after=state(x);eq(field(after,6),b(a==5?4:a==2?2:1));eq(field(after,7),r.expectedVersion().add(ONE));eq(field(after,8),epoch);eq(field(after,9),deadline);eq(nonce(x.role()),n);
        if(a==5){terminal(x);eq(g.callNum(g.vault,"houseBalance()"),house.add(u(1000)));}invariant();return receipt;
    }
    Grace start(Position x,long price)throws Exception{JsonNode r=risk(x,RiskAction.START_CALL,price);BigInteger at=CAnvilContractGate.number(g.rpc("eth_getBlockByHash",r.path("blockHash").asText(),false).path("timestamp"));return new Grace(x,at,field(state(x),9),System.nanoTime(),System.currentTimeMillis());}
    void cancel(Position x)throws Exception{BigInteger n=nonce(x.role());g.expect(g.send(g.keys.get(x.role()),g.controller,g.abi.encode(new Cancel(n)),true),List.of(event(g.controller,EmitterKind.CONTROLLER,new CNonceCancelled(g.addr(x.role()),n))));eq(nonce(x.role()),n.add(ONE));invariant();}
    void riskScenario()throws Exception{
        stage("RISK_DEPLOY");deploy("risk");riskVault=g.vault;
        g.cash("userA",VaultAction.DEPOSIT,u(2000));g.cash("userB",VaultAction.DEPOSIT,u(2000));
        Position a=position("userA","RISK_LONG",0),b=position("userB","RISK_SHORT",1);
        user(a,0,100,1000,false,0,0,0);user(b,0,100,1000,false,0,0,0);
        stage("RISK_RECOVERY");risk(a,RiskAction.START_CALL,94);risk(b,RiskAction.START_CALL,106);risk(a,RiskAction.CLEAR_CALL,100);risk(b,RiskAction.CLEAR_CALL,100);
        stage("RISK_SECOND_CALL");Grace ga=start(a,94),gb=start(b,106);cancel(a);cancel(b);
        Report early=report(a,5,p(80),u(1000));rejectRisk(a,early,ga.deadline());
        stage("WALL_GRACE_WAIT");while(!waited(ga,gb,System.nanoTime(),System.currentTimeMillis())||b(System.currentTimeMillis()/1000).compareTo(ga.deadline().max(gb.deadline()))<0){check(System.nanoTime()-g.started<Duration.ofSeconds(275).toNanos(),"WAIT_WATCHDOG");Thread.sleep(500);}
        // Real no-op transaction mines at natural wall time; no warp/mine/timestamp RPC is allowed.
        g.expect(g.send(g.keys.get("admin"),g.vault,g.abi.encode(new ProcessClaim()),true),List.of());
        check(now().compareTo(ga.deadline().max(gb.deadline()))>=0,"NATURAL_BLOCK_DEADLINE");
        stage("RISK_LIQUIDATE");JsonNode la=risk(a,RiskAction.LIQUIDATE,80),lb=risk(b,RiskAction.LIQUIDATE,120);
        g.save("risk-real-grace.json",CAnvilContractGate.JSON.createObjectNode().put("longStartTimestamp",ga.timestamp().toString()).put("shortStartTimestamp",gb.timestamp().toString()).put("longDeadline",ga.deadline().toString()).put("shortDeadline",gb.deadline().toString()).put("longLiquidationTimestamp",receiptTime(la).toString()).put("shortLiquidationTimestamp",receiptTime(lb).toString()).put("longMonotonicElapsedMs",(System.nanoTime()-ga.mono())/1_000_000).put("shortMonotonicElapsedMs",(System.nanoTime()-gb.mono())/1_000_000).put("longWallElapsedMs",System.currentTimeMillis()-ga.wall()).put("shortWallElapsedMs",System.currentTimeMillis()-gb.wall()).put("clockManipulation",false));
        eq(g.callNum(g.vault,"totalLocked()"),Z);eq(g.callNum(g.vault,"houseBalance()"),u(2000));eq(g.callNum(g.vault,"totalUnpaidProfit()"),Z);g.cash("userA",VaultAction.WITHDRAW,u(1000));g.cash("userB",VaultAction.WITHDRAW,u(1000));invariant();
    }
    static boolean waited(Grace a,Grace b,long mono,long wall){return mono-a.mono()>=Duration.ofSeconds(60).toNanos()&&mono-b.mono()>=Duration.ofSeconds(60).toNanos()&&wall-a.wall()>=60_000&&wall-b.wall()>=60_000;}
    void rejectRisk(Position x,Report r,BigInteger deadline)throws Exception{
        String before=state(x);BigInteger n=nonce(x.role()),locked=g.callNum(g.vault,"totalLocked()"),house=g.callNum(g.vault,"houseBalance()");
        JsonNode receipt=g.send(g.keys.get("relayer"),g.controller,g.abi.encode(new Risk(RiskAction.LIQUIDATE,r,CAnvilContractGate.signature(g.crypto.reportDigest(r,CAnvilContractGate.CHAIN,g.rules),g.keys.get("oracle")))),false);
        BigInteger at=receiptTime(receipt);check(at.compareTo(deadline)<0&&at.compareTo(r.validUntil())<=0,"EARLY_REJECTION_NOT_EXPIRED_OR_LATE");
        check(CAnvilContractGate.number(receipt.path("gasUsed")).compareTo(b(25_000_000))<0,"EARLY_NOT_OOG");eq(state(x),before);eq(nonce(x.role()),n);eq(g.callNum(g.rules,"usedOperations(bytes32)",r.operationId()),Z);eq(g.callNum(g.vault,"totalLocked()"),locked);eq(g.callNum(g.vault,"houseBalance()"),house);
        g.save("early-liquidation-rejected.json",CAnvilContractGate.JSON.createObjectNode().put("txHash",receipt.path("transactionHash").asText()).put("minedTimestamp",at.toString()).put("deadline",deadline.toString()).put("specificRevertReasonDecoded",false));
    }
    BigInteger receiptTime(JsonNode r)throws Exception{return CAnvilContractGate.number(g.rpc("eth_getBlockByHash",r.path("blockHash").asText(),false).path("timestamp"));}
    void claimsScenario()throws Exception{
        stage("CLAIMS_DEPLOY");deploy("claims");claimsVault=g.vault;
        g.cash("treasury",VaultAction.FUND_HOUSE,u(100));g.cash("userA",VaultAction.DEPOSIT,u(2000));g.cash("userB",VaultAction.DEPOSIT,u(2000));
        Position a=position("userA","CLAIM_A",0),b=position("userB","CLAIM_B",0);
        user(a,0,100,1000,false,0,0,0);user(b,0,100,1000,false,0,0,0);
        stage("CLAIMS_TERMINAL_A");user(a,4,110,1000,true,100,1,900);String claimA=claimIdentity(1);claim(1,a,u(900),Z,u(900));g.cash("userA",VaultAction.WITHDRAW,u(1100));
        g.cash("treasury",VaultAction.FUND_HOUSE,u(400));
        stage("CLAIMS_TERMINAL_B");user(b,1,110,1010,false,0,0,0);user(b,4,110,1010,true,0,2,1000);String claimB=claimIdentity(2);claim(2,b,u(1000),Z,u(1000));
        eq(g.callNum(g.vault,"houseBalance()"),u(400));eq(g.callNum(g.vault,"totalUnpaidProfit()"),u(1900));
        rejectOpen(a,"UNPAID_OPEN");
        g.expect(g.send(g.keys.get("admin"),g.controller,g.abi.encode(new Pause(true)),true),List.of(event(g.controller,EmitterKind.CONTROLLER,new NewPositionsPauseChanged(true))));
        stage("CLAIMS_FIFO_PARTIAL");process("admin",1,a,u(400),u(500));claim(1,a,u(900),u(400),u(500));eq(g.callNum(g.vault,"headClaimId()"),ONE);
        g.cash("treasury",VaultAction.FUND_HOUSE,u(500).add(ONE));process("userB",1,a,u(500),Z);eq(g.callNum(g.vault,"headClaimId()"),b(2));eq(g.callNum(g.vault,"houseBalance()"),ONE);claim(2,b,u(1000),Z,u(1000));
        process("relayer",2,b,ONE,u(1000).subtract(ONE));g.cash("treasury",VaultAction.FUND_HOUSE,u(1000).subtract(ONE));process("admin",2,b,u(1000).subtract(ONE),Z);
        eq(claimIdentity(1),claimA);eq(claimIdentity(2),claimB);eq(g.callNum(g.vault,"totalUnpaidProfit()"),Z);eq(g.callNum(g.vault,"headClaimId()"),b(3));
        BigInteger fa=g.free(g.addr("userA")),fb=g.free(g.addr("userB"));g.expect(g.send(g.keys.get("oracle"),g.vault,g.abi.encode(new ProcessClaim()),true),List.of());eq(g.free(g.addr("userA")),fa);eq(g.free(g.addr("userB")),fb);
        stage("OPEN_PAUSE_FREE_WITHDRAW");rejectOpen(a,"PAUSED_OPEN");
        g.cash("userA",VaultAction.DEPOSIT,ONE);g.cash("userA",VaultAction.WITHDRAW,g.free(g.addr("userA")));g.cash("userB",VaultAction.WITHDRAW,g.free(g.addr("userB")));invariant();
        eq(g.callNum(g.vault,"totalFree()"),Z);eq(g.callNum(g.vault,"totalLocked()"),Z);eq(g.callNum(g.vault,"houseBalance()"),Z);eq(g.balance(g.vault),Z);
        g.save("claims-summary.json",CAnvilContractGate.JSON.createObjectNode().put("claim1Original",u(900).toString()).put("claim2Original",u(1000).toString()).put("remaining",Z.toString()).put("oneHeadPerCall",true).put("oneWeiBoundary",true).put("freeWithdrawWithoutServer",true).put("shutdownAcceptance",false));
    }
    String claimIdentity(int id)throws Exception{String c=g.call(g.vault,"claims(uint256)",CAnvilContractGate.word(b(id)));return c.substring(0,2+4*64);}
    void claim(int id,Position x,BigInteger original,BigInteger paid,BigInteger remaining)throws Exception{
        String c=g.call(g.vault,"claims(uint256)",CAnvilContractGate.word(b(id)));eq(CAnvilContractGate.wordAddress(c,0),g.addr(x.role()));eq("0x"+c.substring(2+2*64,2+3*64),x.id());eq(field(c,3),original);eq(field(c,4),paid);eq(field(c,5),remaining);invariant();
    }
    void process(String role,int id,Position owner,BigInteger paid,BigInteger remaining)throws Exception{
        BigInteger free=g.free(g.addr(owner.role())),house=g.callNum(g.vault,"houseBalance()"),tokens=g.balance(g.vault);
        g.expect(g.send(g.keys.get(role),g.vault,g.abi.encode(new ProcessClaim()),true),List.of(event(g.vault,EmitterKind.VAULT,new ProfitClaimPaid(b(id),g.addr(owner.role()),paid,remaining))));
        eq(g.free(g.addr(owner.role())),free.add(paid));eq(g.callNum(g.vault,"houseBalance()"),house.subtract(paid));eq(g.balance(g.vault),tokens);invariant();
    }
    void rejectOpen(Position previous,String name)throws Exception{
        Position x=position(previous.role(),name,0);UserCall c=userCall(x,0,p(100),u(1000),false);
        BigInteger n=nonce(x.role()),free=g.free(g.addr(x.role())),house=g.callNum(g.vault,"houseBalance()"),locked=g.callNum(g.vault,"totalLocked()"),unpaid=g.callNum(g.vault,"totalUnpaidProfit()");
        JsonNode r=g.send(g.keys.get("relayer"),g.controller,g.abi.encode(c.call()),false);BigInteger at=receiptTime(r);check(at.compareTo(c.report().validUntil())<=0&&at.compareTo(c.intent().deadline())<=0&&at.compareTo(c.intent().issuedAt())>=0,"OPEN_REJECT_EXPIRED");check(CAnvilContractGate.number(r.path("gasUsed")).compareTo(b(25_000_000))<0,"OPEN_REJECT_OOG");
        eq(nonce(x.role()),n);eq(g.free(g.addr(x.role())),free);eq(g.callNum(g.vault,"houseBalance()"),house);eq(g.callNum(g.vault,"totalLocked()"),locked);eq(g.callNum(g.vault,"totalUnpaidProfit()"),unpaid);eq(g.callNum(g.rules,"usedOperations(bytes32)",c.report().operationId()),Z);eq(g.callAddress(g.controller,"positionOwner(bytes32)",x.id()),CAnvilContractGate.ZERO);eq(field(state(x),6),Z);invariant();
        g.save("negative-"+name+".json",CAnvilContractGate.JSON.createObjectNode().put("txHash",r.path("transactionHash").asText()).put("status",0).put("specificRevertReasonDecoded",false));
    }
    void supply()throws Exception{eq(g.balance(g.addr("treasury")).add(g.balance(g.addr("userA"))).add(g.balance(g.addr("userB"))).add(g.balance(riskVault)).add(g.balance(claimsVault)),CAnvilContractGate.SUPPLY);}
    static void selfTest(){
        for(int side=0;side<2;side++){var m=FuturesMath.mark(side==0?FuturesMath.Side.LONG:FuturesMath.Side.SHORT,u(100),p(100),u(1000),p(side==0?94:106));eq(m.equity(),u(400));eq(m.maintenance(),u(side==0?470:530));check(m.atRisk(),"RISK_FIXTURE");var loss=FuturesMath.mark(side==0?FuturesMath.Side.LONG:FuturesMath.Side.SHORT,u(100),p(100),u(1000),p(side==0?80:120));eq(loss.realized(),u(-1000));eq(loss.badDebt(),u(1000));}
        eq(FuturesMath.quantity(u(1000),10,p(100)),u(100));
        var a=new Grace(null,b(1),b(61),0,0);var b=new Grace(null,b(2),b(62),1_000_000_000L,1000);
        check(!waited(a,b,60_000_000_000L,60_000),"LATEST_START_WAIT");check(!waited(a,b,61_000_000_000L,60_999),"WALL_WAIT");check(waited(a,b,61_000_000_000L,61_000),"BOTH_CLOCKS");
        check(u(500).add(ONE).subtract(u(500)).equals(ONE),"ONE_WEI");
    }
}
