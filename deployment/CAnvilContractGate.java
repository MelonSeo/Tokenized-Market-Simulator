import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.*;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.futures.math.FuturesMath;
import org.web3j.crypto.*;
import org.web3j.rlp.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

/** Narrow C5a1 actual-contract fixture. No PG, Spring, market provider, scanner or user wallet. */
public final class CAnvilContractGate {
    static final BigInteger Z=BigInteger.ZERO, ONE=BigInteger.ONE, CHAIN=BigInteger.valueOf(31337);
    static final BigInteger UNIT=BigInteger.TEN.pow(18), SUPPLY=UNIT.multiply(BigInteger.valueOf(20_000_000));
    static final String ZERO="0x"+"0".repeat(40), ZEROHASH="0x"+"0".repeat(64);
    static final int BODY_LIMIT=1024*1024;
    static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static {JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(BODY_LIMIT).maxNumberLength(100).build());}
    final Path fixture,output;final HttpClient client;long rpcId=0,recordId=0,evidenceBytes=0;final long started=System.nanoTime();
    final CAbiCodec abi=new CAbiCodec();final CAbiEventCodec events=new CAbiEventCodec();final CEip712Verifier crypto=new CEip712Verifier();
    final LinkedHashMap<String,ECKeyPair> keys=new LinkedHashMap<>();final Map<String,BigInteger> txNonces=new HashMap<>();
    String token,controller,vault,rules,genesis;String symbol=hash("C5A1_LOCAL_SYMBOL"),policy=hash("C5A1_LOCAL_POLICY");
    CAnvilContractGate(Path fixture,Path output){this.fixture=fixture;this.output=output;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();}
    public static void main(String[] args){
        try{verifyTransportPolicy();}catch(Exception e){System.err.println("C5A1_TRANSPORT_POLICY_REJECTED");System.exit(1);return;}
        if(args.length==2&&args[0].equals("--self-test")){selfTest();try{new CAnvilContractGate(Path.of(args[1]),Path.of(args[1])).verifySourceMetadata();}catch(Exception e){String code=e instanceof IllegalStateException&&e.getMessage()!=null&&e.getMessage().matches("C5A1_[A-Z0-9_]+")?e.getMessage():"C5A1_SOURCE_FIXTURE_IO";throw new IllegalStateException(code);}System.out.println("C5A1_OFFLINE_SELF_TEST_PASS");return;}
        if(args.length!=2||!args[0].equals("/fixture")||!args[1].equals("/evidence"))throw new IllegalArgumentException("C5A1_SCOPE");
        try{var gate=new CAnvilContractGate(Path.of(args[0]),Path.of(args[1]));if(gate.read(gate.fixture.resolve("manifest.json")).path("scope").asText().equals("C5A2_RISK_CLAIMS_ANVIL_ONLY")){new CAnvilRiskClaimsScenario(gate).run();System.out.println("C5A2_CONTRACT_GATE_PASS");}else{gate.run();System.out.println("C5A1_CONTRACT_GATE_PASS");}}
        catch(Exception failure){System.err.println("C5A1_STOP_NO_RETRY");System.exit(1);}
    }
    void run()throws Exception{
        check(Runtime.version().feature()==21,"JDK21");check(Files.isDirectory(output)&&Files.isDirectory(fixture),"PATHS");
        for(String name:List.of("http.proxyHost","https.proxyHost","socksProxyHost"))check(System.getProperty(name)==null,"PROXY_PROPERTY");
        for(String name:List.of("HTTP_PROXY","HTTPS_PROXY","ALL_PROXY"))check(System.getenv(name)==null,"PROXY_ENV");
        var manifest=read(fixture.resolve("manifest.json"));check(manifest.path("scope").asText().equals("C5A1_ANVIL_ONLY"),"SCOPE");
        check(manifest.path("rpc").asText().equals("http://anvil:8545"),"RPC_ALLOWLIST");check(manifest.path("runId").asText().matches("canvil-[0-9a-f]{16}"),"RUN");
        check(manifest.path("sourceIdentity").asText().matches("[0-9a-f]{64}"),"SOURCE_IDENTITY");
        // Actual Java10001 write/read probe precedes source checks, key generation and every RPC.
        save("private-write-probe.json",JSON.createObjectNode().put("runId",manifest.path("runId").asText()).put("scope","NEW_OWNED_EVIDENCE_WRITE_PROBE"));
        eq(read(output.resolve("private-write-probe.json")).path("runId").asText(),manifest.path("runId").asText());
        save("transport-policy.json",JSON.createObjectNode().put("javaRuntime",Runtime.version().toString()).put("retryConnect",false).put("retryAlways",false).put("maxAttempts",1).put("scope","FIXED_JDK21_INTERNAL_FIELD_ASSERTION_NOT_NETWORK_PACKET_CAPTURE"));
        verifySourceMetadata();
        for(String role:List.of("treasury","admin","relayer","oracle","userA","userB"))keys.put(role,Keys.createEcKeyPair(new SecureRandom()));
        ObjectNode privateKeys=JSON.createObjectNode(),addresses=JSON.createObjectNode();
        for(var e:keys.entrySet()){privateKeys.put(e.getKey(),e.getValue().getPrivateKey().toString(16));addresses.put(e.getKey(),address(e.getValue()));}
        check(new HashSet<>(keys.values().stream().map(CAnvilContractGate::address).toList()).size()==6,"ROLE_KEYS_DISTINCT");
        // All fresh fixture keys are persisted before native gas setup or signed transaction submission.
        save("keys.private.json",privateKeys);save("addresses.json",addresses);
        check(number(rpc("eth_chainId")).equals(CHAIN),"CHAIN_ID");genesis=rpc("eth_getBlockByNumber","0x0",false).path("hash").asText();CAbiModels.hash(genesis);
        for(var key:keys.values())rpc("anvil_setBalance",address(key),hex(UNIT.multiply(BigInteger.valueOf(100))));
        save("native-gas-bootstrap.json",JSON.createObjectNode().put("scope","NEW_FIXTURE_NATIVE_GAS_ONLY_NO_TOKEN_STORAGE_OVERRIDE").put("amountWei",UNIT.multiply(BigInteger.valueOf(100)).toString()));
        token=deploy("CMockCollateral",words(addressWord(addr("treasury"))),keys.get("treasury"));
        controller=deploy("CCollateralizedFuturesController",words(addressWord(token),addressWord(addr("relayer")),addressWord(addr("oracle")),addressWord(addr("admin")),symbol,policy),keys.get("treasury"));
        rules=callAddress(controller,"rules()");vault=callAddress(controller,"vault()");
        check(Set.of(token,controller,rules,vault).size()==4,"CONTRACTS_DISTINCT");
        for(String contract:List.of("CMockCollateral","CCollateralizedFuturesController","CMarginVault","FuturesEngine"))verifyRuntime(contract,switch(contract){case "CMockCollateral"->token;case "CMarginVault"->vault;case "FuturesEngine"->rules;default->controller;});
        eq(callAddress(vault,"asset()"),token);eq(callAddress(vault,"controller()"),controller);eq(callAddress(controller,"relayer()"),addr("relayer"));eq(callAddress(controller,"pauseAdmin()"),addr("admin"));
        eq(callAddress(rules,"executor()"),controller);eq(callAddress(rules,"priceSigner()"),addr("oracle"));eq(call(rules,"supportedSymbolHash()"),symbol);eq(call(rules,"policyHash()"),policy);
        eq(callNum(token,"totalSupply()"),SUPPLY);eq(callNum(token,"decimals()"),BigInteger.valueOf(18));eq(balance(addr("treasury")),SUPPLY);
        // H10m remains treasury-owned until the actual fundHouse transfer; U2m actual wallet ownership.
        transfer("treasury",addr("userA"),units(1_000_000));transfer("treasury",addr("userB"),units(1_000_000));
        cash("treasury",VaultAction.FUND_HOUSE,units(10_000_000));
        eq(balance(addr("treasury")),units(8_000_000));eq(balance(addr("userA")),units(1_000_000));eq(balance(addr("userB")),units(1_000_000));
        cash("userA",VaultAction.DEPOSIT,units(10_000));cash("userB",VaultAction.DEPOSIT,units(1_000));
        cash("userA",VaultAction.WITHDRAW,units(100));cashState(units(10_900),Z,units(10_000_000));
        String position=hash("C5A1_POSITION_A"),account=call(controller,"accountRef(address)",addressWord(addr("userA")));
        BigInteger price=BigInteger.valueOf(100_00000000L),collateral=units(1_000),quantity=FuturesMath.quantity(collateral,10,price);
        executeUser(position,account,0,0,collateral,Z,quantity,price,price,0,false);
        eq(free(addr("userA")),units(8_900));cashState(units(9_900),collateral,units(10_000_000));
        executeUser(position,account,1,1,units(1_500),collateral,quantity,price,price,1,false);
        eq(free(addr("userA")),units(8_400));cashState(units(9_400),units(1_500),units(10_000_000));
        // CLOSE at 105 returns principal1500+profit500; house decreases by actual500, not a claim.
        executeUser(position,account,4,2,units(1_500),units(1_500),quantity,price,BigInteger.valueOf(105_00000000L),2,true);
        eq(free(addr("userA")),units(10_400));cashState(units(11_400),Z,units(9_999_500));
        eq(callNum(vault,"totalUnpaidProfit()"),Z);eq(callNum(controller,"nonces(address)",addressWord(addr("userA"))),BigInteger.valueOf(3));
        String p=call(rules,"getPosition(bytes32)",position);eq(wordNum(p,6),BigInteger.valueOf(3));eq(wordNum(p,5),units(1_500));eq(wordNum(p,7),BigInteger.valueOf(3));
        String lock=call(vault,"positionLocks(bytes32)",position);eq(wordAddress(lock,0),addr("userA"));eq(wordNum(lock,1),Z);eq(wordNum(lock,2),ONE);
        eq(call(rules,"activePosition(bytes32)",account),ZEROHASH);
        // Genuine failed transactions, not estimate-only evidence. All failures use new tx nonce.
        BigInteger freeBefore=free(addr("userA")),houseBefore=callNum(vault,"houseBalance()");
        for(String data:List.of(selector("mint(address,uint256)")+words(addressWord(addr("userA")),word(ONE)),selector("burn(address,uint256)")+words(addressWord(addr("userA")),word(ONE))))send(keys.get("relayer"),token,data,false);
        send(keys.get("relayer"),vault,selector("lock(bytes32,bytes32,address,uint256)")+words(hash("badop"),hash("badpos"),addressWord(addr("userA")),word(ONE)),false);
        send(keys.get("userA"),controller,abi.encode(new Pause(true)),false);
        eq(free(addr("userA")),freeBefore);eq(callNum(vault,"houseBalance()"),houseBefore);eq(callNum(token,"totalSupply()"),SUPPLY);
        ObjectNode result=JSON.createObjectNode().put("scope","C5A1_ACTUAL_LOCAL_ANVIL_CONTRACT_ONLY").put("sourceIdentity",manifest.path("sourceIdentity").asText()).put("genesisHash",genesis).put("chainId",CHAIN.toString()).put("token",token).put("controller",controller).put("rules",rules).put("vault",vault).put("completeCoverage",false).put("postgresRun",false).put("realMetaMask",false).put("riskWall60Run",false).put("claimsInsolvencyRun",false).put("result","PASS");
        save("gate-result.json",result);
    }
    void verifySourceMetadata()throws Exception{
        JsonNode sources=read(fixture.resolve("source-map.json"));check(sources.isArray()&&sources.size()>0&&sources.size()<256,"SOURCE_MAP");
        for(JsonNode s:sources){String file=s.path("file").asText();check(file.matches("source-[0-9]{3}\\.bin"),"SOURCE_PATH");check(Files.size(fixture.resolve(file))<=512*1024,"SOURCE_SIZE");byte[] raw=Files.readAllBytes(fixture.resolve(file));
            check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(s.path("sha256").asText()),"SOURCE_RAW_SHA256_"+file.substring(7,10));
            check(compilerSourceMatches(raw,s.path("keccak256").asText()),"SOURCE_METADATA_KECCAK_"+file.substring(7,10));}
    }
    // Foundry's actual compiler-input normalization: only CRLF -> LF. Raw file bytes stay preserved.
    static byte[] compilerSourceBytes(byte[] raw){check(raw.length<=512*1024,"SOURCE_SIZE");var out=new ByteArrayOutputStream(raw.length);for(int i=0;i<raw.length;i++){if(raw[i]==13&&i+1<raw.length&&raw[i+1]==10)continue;out.write(raw[i]);}return out.toByteArray();}
    static boolean compilerSourceMatches(byte[] raw,String expected){return hex(Hash.sha3(compilerSourceBytes(raw))).equals(CAbiModels.hash(expected));}
    String deploy(String name,String constructor,ECKeyPair sender)throws Exception{
        JsonNode artifact=read(fixture.resolve(name+".json"));String bytecode=artifact.path("bytecode").path("object").asText();check(bytecode.matches("0x[0-9a-fA-F]+")&&bytecode.length()<100_000,"ARTIFACT_CREATION");
        JsonNode receipt=send(sender,null,bytecode+constructor,true);String address=CAbiModels.address(receipt.path("contractAddress").asText());
        if(name.equals("CMockCollateral"))expect(receipt,List.of(new Expected(address,EmitterKind.TOKEN,new CAbiEventModels.Transfer(ZERO,addr("treasury"),SUPPLY))));
        else check(receipt.path("logs").isEmpty(),"CONTROLLER_CREATE_LOGS");return address;
    }
    void verifyRuntime(String name,String address)throws Exception{
        verifyRuntime(name,address,"");
    }
    void verifyRuntime(String name,String address,String suffix)throws Exception{
        check(suffix.isEmpty()||suffix.equals("-risk")||suffix.equals("-claims"),"RUNTIME_PHASE");
        JsonNode artifact=read(fixture.resolve(name+".json")),refs=artifact.path("deployedBytecode").path("immutableReferences");
        byte[] actual=bytes(rpc("eth_getCode",address,"latest").asText()),expected=bytes(artifact.path("deployedBytecode").path("object").asText());check(actual.length==expected.length&&actual.length>0,"RUNTIME_SIZE");
        boolean[] holes=new boolean[actual.length];Iterator<JsonNode> groups=refs.elements();while(groups.hasNext()){byte[] value=null;for(JsonNode ref:groups.next()){int at=ref.path("start").intValue(),len=ref.path("length").intValue();check(at>=0&&len==32&&at<=actual.length-len,"IMMUTABLE_RANGE");byte[] next=Arrays.copyOfRange(actual,at,at+len);if(value!=null)check(Arrays.equals(value,next),"IMMUTABLE_REPEAT");value=next;for(int i=at;i<at+len;i++){check(!holes[i],"IMMUTABLE_OVERLAP");holes[i]=true;}}}
        for(int i=0;i<actual.length;i++)if(!holes[i])check(actual[i]==expected[i],"RUNTIME_FIXED_BYTES");
        save("code-"+name+suffix+".json",JSON.createObjectNode().put("address",address).put("runtimeCodeHash",hex(Hash.sha3(actual))).put("artifactTemplateHash",hex(Hash.sha3(expected))).put("immutableBinding","fixed bytes + repeated slots + separately verified getters/domains; not AST-variable-slot identity"));
    }
    void executeUser(String position,String account,int action,int version,BigInteger after,BigInteger before,BigInteger quantity,BigInteger entry,BigInteger price,int nonce,boolean direct)throws Exception{
        BigInteger now=number(rpc("eth_getBlockByNumber","latest",false).path("timestamp"));String operation=hash("C5A1_OP_"+action);Report r=new Report(operation,position,account,symbol,BigInteger.valueOf(action),BigInteger.valueOf(version),Z,Z,BigInteger.TEN,quantity,entry,before,after,price,now,now.add(BigInteger.valueOf(30)),policy,controller);
        Intent i=new Intent(addr("userA"),addr("relayer"),operation,position,account,symbol,r.action(),r.expectedVersion(),Z,Z,BigInteger.TEN,action==0?Z:entry,before,after,price,price,quantity,quantity,BigInteger.valueOf(nonce),now,now.add(BigInteger.valueOf(120)),policy);
        String reportHash=crypto.reportDigest(r,CHAIN,rules),intentHash=crypto.intentDigest(i,CHAIN,controller);
        String encoded=new CAbiCodec().encode(new Direct(r,"0x",i));String reportWords=encoded.substring(10,10+18*64),intentWords=encoded.substring(10+19*64,10+41*64);
        eq(call(rules,"reportDigest((bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32,address))",reportWords),reportHash);
        eq(call(controller,"intentDigest((address,address,bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32))",intentWords),intentHash);
        String ps=signature(reportHash,keys.get("oracle"));Call call=direct?new Direct(r,ps,i):new Relayed(r,ps,i,signature(intentHash,keys.get("userA")));
        if(action==0){
            BigInteger originalFree=free(addr("userA"));
            // Complete, currently valid economics; only approval/caller is wrong in these failures.
            rejectOpen("missing-user-signature",keys.get("relayer"),controller,abi.encode(new Relayed(r,ps,i,"0x")),r,i);
            rejectOpen("oracle-not-owner",keys.get("relayer"),controller,abi.encode(new Relayed(r,ps,i,signature(intentHash,keys.get("oracle")))),r,i);
            rejectOpen("other-user-not-owner",keys.get("relayer"),controller,abi.encode(new Relayed(r,ps,i,signature(intentHash,keys.get("userB")))),r,i);
            rejectOpen("nonowner-direct",keys.get("relayer"),controller,abi.encode(new Direct(r,ps,i)),r,i);
            rejectOpen("owner-not-oracle",keys.get("relayer"),controller,abi.encode(new Relayed(r,signature(reportHash,keys.get("userA")),i,signature(intentHash,keys.get("userA")))),r,i);
            rejectOpen("wrong-user-contract-domain",keys.get("relayer"),controller,abi.encode(new Relayed(r,ps,i,signature(crypto.intentDigest(i,CHAIN,rules),keys.get("userA")))),r,i);
            rejectOpen("wrong-user-chain-domain",keys.get("relayer"),controller,abi.encode(new Relayed(r,ps,i,signature(crypto.intentDigest(i,CHAIN.add(ONE),controller),keys.get("userA")))),r,i);
            Intent wrongNonce=withBounds(i,i.nonce().add(ONE),i.priceMinE8(),i.priceMaxE8(),i.quantityMin(),i.quantityMax());
            rejectOpen("wrong-user-nonce",keys.get("relayer"),controller,abi.encode(new Relayed(r,ps,wrongNonce,signature(crypto.intentDigest(wrongNonce,CHAIN,controller),keys.get("userA")))),r,wrongNonce);
            Intent wrongPrice=withBounds(i,i.nonce(),price.add(ONE),price.add(ONE),i.quantityMin(),i.quantityMax());
            rejectOpen("report-outside-approved-price",keys.get("relayer"),controller,abi.encode(new Relayed(r,ps,wrongPrice,signature(crypto.intentDigest(wrongPrice,CHAIN,controller),keys.get("userA")))),r,wrongPrice);
            Intent wrongQuantity=withBounds(i,i.nonce(),i.priceMinE8(),i.priceMaxE8(),quantity.add(ONE),quantity.add(ONE));
            rejectOpen("report-outside-approved-quantity",keys.get("relayer"),controller,abi.encode(new Relayed(r,ps,wrongQuantity,signature(crypto.intentDigest(wrongQuantity,CHAIN,controller),keys.get("userA")))),r,wrongQuantity);
            String reportTuple="(bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32,address)";
            String rulesOpen=selector("open("+reportTuple+",bytes)")+abi.encode(new Risk(RiskAction.START_CALL,r,ps)).substring(10);
            eq(read(fixture.resolve("FuturesEngine.json")).path("methodIdentifiers").path("open("+reportTuple+",bytes)").asText(),selector("open("+reportTuple+",bytes)").substring(2));
            rejectOpen("relayer-cannot-bypass-controller-rules",keys.get("relayer"),rules,rulesOpen,r,i);
            eq(free(addr("userA")),originalFree);eq(callNum(controller,"nonces(address)",addressWord(addr("userA"))),Z);
            eq(callNum(rules,"usedOperations(bytes32)",operation),Z);eq(callAddress(controller,"positionOwner(bytes32)",position),ZERO);
        }
        var mark=FuturesMath.mark(FuturesMath.Side.LONG,quantity,entry,after,price);BigInteger pnl=action==4?mark.realized():Z;
        List<Expected> e=new ArrayList<>();e.add(new Expected(rules,EmitterKind.RULES,new OperationApplied(operation,position,r.action(),r.expectedVersion().add(ONE),price,after,mark.unrealized(),pnl,Z,Z,Z)));
        e.add(new Expected(rules,EmitterKind.RULES,new FuturesReportConsumed(operation,now,r.validUntil())));
        if(action==4)e.add(new Expected(vault,EmitterKind.VAULT,new PositionSettled(operation,position,addr("userA"),after,pnl,Z,pnl,Z)));
        else e.add(new Expected(vault,EmitterKind.VAULT,new CollateralLocked(operation,position,addr("userA"),after.subtract(before),after)));
        e.add(new Expected(controller,EmitterKind.CONTROLLER,new CUserIntentExecuted(operation,position,addr("userA"),intentHash,reportHash,r.action(),i.nonce(),direct)));
        expect(send(keys.get(direct?"userA":"relayer"),controller,abi.encode(call),true),e);
        eq(callNum(rules,"usedOperations(bytes32)",operation),ONE);eq(callAddress(controller,"positionOwner(bytes32)",position),addr("userA"));
    }
    static Intent withBounds(Intent i,BigInteger nonce,BigInteger pmin,BigInteger pmax,BigInteger qmin,BigInteger qmax){return new Intent(i.owner(),i.relayer(),i.operationId(),i.positionId(),i.accountRef(),i.symbolHash(),i.action(),i.expectedVersion(),i.expectedCallEpoch(),i.side(),i.leverage(),i.entryPriceE8(),i.collateralBefore(),i.collateralAfter(),pmin,pmax,qmin,qmax,nonce,i.issuedAt(),i.deadline(),i.policyHash());}
    void rejectOpen(String label,ECKeyPair sender,String target,String data,Report r,Intent i)throws Exception{
        JsonNode receipt=send(sender,target,data,false);BigInteger at=number(rpc("eth_getBlockByHash",receipt.path("blockHash").asText(),false).path("timestamp"));
        check(at.compareTo(r.observedAt())>=0&&at.compareTo(r.validUntil())<=0&&at.compareTo(i.issuedAt())>=0&&at.compareTo(i.deadline())<=0,"NEGATIVE_CASE_EXPIRED_NOT_AUTH_PROOF");
        check(number(receipt.path("gasUsed")).compareTo(BigInteger.valueOf(25_000_000))<0,"NEGATIVE_CASE_OUT_OF_GAS_NOT_AUTH_PROOF");
        save("negative-"+label+".json",JSON.createObjectNode().put("scenario",label).put("txHash",receipt.path("transactionHash").asText()).put("status",0).put("reportAndIntentUnexpired",true).put("scope","ACTUAL_EVM_REVERT_UNDER_TESTED_INPUTS_NOT_GENERAL_REVERT_REASON_DECODER"));
    }
    void cash(String role,VaultAction action,BigInteger amount)throws Exception{
        String owner=addr(role);BigInteger walletBefore=balance(owner),freeBefore=free(owner),houseBefore=callNum(vault,"houseBalance()");
        if(action!=VaultAction.WITHDRAW){expect(send(keys.get(role),token,abi.encode(new Approve(vault,amount)),true),List.of(new Expected(token,EmitterKind.TOKEN,new Approval(owner,vault,amount))));}
        Event transfer=new CAbiEventModels.Transfer(action==VaultAction.WITHDRAW?vault:owner,action==VaultAction.WITHDRAW?owner:vault,amount);
        Event ve=switch(action){case DEPOSIT->new Deposited(owner,amount);case WITHDRAW->new Withdrawn(owner,amount);case FUND_HOUSE->new HouseFunded(owner,amount);};
        expect(send(keys.get(role),vault,abi.encode(new VaultAmount(action,amount)),true),List.of(new Expected(token,EmitterKind.TOKEN,transfer),new Expected(vault,EmitterKind.VAULT,ve)));
        eq(balance(owner),action==VaultAction.WITHDRAW?walletBefore.add(amount):walletBefore.subtract(amount));
        eq(free(owner),action==VaultAction.DEPOSIT?freeBefore.add(amount):action==VaultAction.WITHDRAW?freeBefore.subtract(amount):freeBefore);
        eq(callNum(vault,"houseBalance()"),action==VaultAction.FUND_HOUSE?houseBefore.add(amount):houseBefore);
        if(action!=VaultAction.WITHDRAW)eq(callNum(token,"allowance(address,address)",addressWord(owner)+addressWord(vault)),Z);
    }
    void cashState(BigInteger free,BigInteger locked,BigInteger house)throws Exception{eq(callNum(vault,"totalFree()"),free);eq(callNum(vault,"totalLocked()"),locked);eq(callNum(vault,"houseBalance()"),house);eq(balance(vault),free.add(locked).add(house));eq(callNum(vault,"recognizedAssets()"),balance(vault));eq(callNum(vault,"unassignedAssets()"),Z);}
    void transfer(String from,String to,BigInteger amount)throws Exception{expect(send(keys.get(from),token,abi.encode(new CAbiModels.Transfer(to,amount)),true),List.of(new Expected(token,EmitterKind.TOKEN,new CAbiEventModels.Transfer(addr(from),to,amount))));}
    record Expected(String emitter,EmitterKind kind,Event event){}
    void expect(JsonNode receipt,List<Expected> expected){JsonNode logs=receipt.path("logs");check(logs.isArray()&&logs.size()==expected.size(),"EXACT_EVENT_COUNT");for(int i=0;i<expected.size();i++){JsonNode log=logs.get(i);Expected e=expected.get(i);eq(log.path("address").asText(),e.emitter());List<String> topics=new ArrayList<>();log.path("topics").forEach(t->topics.add(t.asText()));eq(events.decode(e.kind(),new CAbiEventModels.Log(e.emitter(),topics,log.path("data").asText())),e.event());}}
    JsonNode send(ECKeyPair key,String to,String data,boolean success)throws Exception{
        String sender=address(key);BigInteger nonce=number(rpc("eth_getTransactionCount",sender,"pending"));
        if(txNonces.containsKey(sender))eq(nonce,txNonces.get(sender));
        byte[] raw=raw(key,to,data,nonce);String hash=hex(Hash.sha3(raw));long id=++recordId;
        save(String.format("%03d-signed.private.json",id),JSON.createObjectNode().put("sender",sender).put("nonce",nonce.toString()).put("hash",hash).put("raw",hex(raw)));
        String returned=rpc("eth_sendRawTransaction",hex(raw)).asText();eq(returned,hash);txNonces.put(sender,nonce.add(ONE));
        long end=System.nanoTime()+Duration.ofSeconds(45).toNanos();JsonNode receipt;
        while(true){receipt=rpc("eth_getTransactionReceipt",hash);if(!receipt.isNull())break;check(System.nanoTime()<end,"RECEIPT_TIMEOUT_STOP");Thread.sleep(1000);}
        // Preserve the entire bounded observation before semantic/canonical/status validation.
        save(String.format("%03d-receipt.observed.private.json",id),receipt);
        try{validateReceipt(receipt,hash,sender,to,data,nonce);eq(number(receipt.path("status")),success?ONE:Z);if(!success)check(receipt.path("logs").isEmpty(),"REVERTED_LOGS");
            save(String.format("%03d-receipt.verdict.json",id),JSON.createObjectNode().put("hash",hash).put("verdict","VALIDATED_EXPECTED_STATUS").put("expectedSuccess",success));return receipt;
        }catch(Exception failure){save(String.format("%03d-receipt.verdict.json",id),JSON.createObjectNode().put("hash",hash).put("verdict","VALIDATION_REJECTED_STOP").put("expectedSuccess",success));throw new IOException("C5A1_RECEIPT_VALIDATION_STOP");}
    }
    void validateReceipt(JsonNode receipt,String hash,String sender,String to,String data,BigInteger nonce)throws Exception{
        eq(receipt.path("transactionHash").asText(),hash);eq(receipt.path("from").asText(),sender);if(to!=null)eq(receipt.path("to").asText(),to);else check(receipt.path("to").isNull(),"CREATION_TO");
        String blockHash=CAbiModels.hash(receipt.path("blockHash").asText()),number=receipt.path("blockNumber").asText();JsonNode block=rpc("eth_getBlockByHash",blockHash,false);eq(block.path("number").asText(),number);eq(rpc("eth_getBlockByNumber",number,false).path("hash").asText(),blockHash);
        JsonNode tx=rpc("eth_getTransactionByHash",hash);eq(tx.path("hash").asText(),hash);eq(tx.path("blockHash").asText(),blockHash);eq(tx.path("from").asText(),sender);eq(tx.path("transactionIndex").asText(),receipt.path("transactionIndex").asText());
        eq(number(tx.path("nonce")),nonce);eq(number(tx.path("value")),Z);eq(tx.path("input").asText(),data.toLowerCase(Locale.ROOT));
        int transactionIndex=number(receipt.path("transactionIndex")).intValueExact();check(block.path("transactions").isArray()&&transactionIndex<block.path("transactions").size(),"BLOCK_TX_INDEX");eq(block.path("transactions").get(transactionIndex).asText(),hash);
        if(to==null){check(tx.path("to").isNull(),"TX_CREATION_TO");String created="0x"+hex(Hash.sha3(RlpEncoder.encode(new RlpList(RlpString.create(bytes(sender)),rlp(nonce))))).substring(26);eq(receipt.path("contractAddress").asText(),created);}else eq(tx.path("to").asText(),to);
        check(receipt.path("logs").isArray()&&receipt.path("logs").size()<=128,"LOG_CAP");BigInteger last=BigInteger.valueOf(-1);
        for(JsonNode log:receipt.path("logs")){eq(log.path("transactionHash").asText(),hash);eq(log.path("blockHash").asText(),blockHash);eq(log.path("blockNumber").asText(),number);eq(log.path("transactionIndex").asText(),receipt.path("transactionIndex").asText());check(log.path("removed").isBoolean()&&!log.path("removed").booleanValue(),"REMOVED");BigInteger index=number(log.path("logIndex"));check(index.compareTo(last)>0,"LOG_ORDER");last=index;}
        save("block-"+hash.substring(2)+".json",block);
    }
    JsonNode rpc(String method,Object...params)throws Exception{
        check(System.nanoTime()-started<Duration.ofSeconds(290).toNanos(),"WATCHDOG");
        check(Set.of("eth_chainId","eth_getBlockByNumber","eth_getBlockByHash","anvil_setBalance","eth_getTransactionCount","eth_sendRawTransaction","eth_getTransactionReceipt","eth_getTransactionByHash","eth_getCode","eth_call").contains(method),"RPC_METHOD");
        long id=++rpcId;byte[] body=JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params));check(body.length<BODY_LIMIT,"REQUEST_CAP");
        HttpRequest req=HttpRequest.newBuilder(URI.create("http://anvil:8545")).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        long start=System.nanoTime();AtomicReference<CappedBody> bodySubscriber=new AtomicReference<>();try{var response=singleAttempt(()->client.sendAsync(req,info->{var b=new CappedBody();bodySubscriber.set(b);return b;}),8000);if(method.equals("eth_getTransactionReceipt"))saveRaw(String.format("rpc-%04d-receipt-http.observed.private.json",id),response.body());JsonNode result=parseRpc(response.statusCode(),response.body(),id);save(String.format("rpc-%04d.json",id),JSON.createObjectNode().put("method",method).put("elapsedMs",(System.nanoTime()-start)/1_000_000).put("received",true));return result;}
        catch(Exception error){save(String.format("rpc-%04d-stop.json",id),JSON.createObjectNode().put("method",method).put("elapsedMs",(System.nanoTime()-start)/1_000_000).put("stop","NO_RETRY_OR_RESEND"));throw new IOException("C5A1_RPC_STOP");}
        finally{if(bodySubscriber.get()!=null)bodySubscriber.get().cancel();}
    }
    static JsonNode parseRpc(int status,byte[] body,long id)throws Exception{check(status==200,"HTTP_STATUS");check(body.length<=BODY_LIMIT,"RESPONSE_CAP");JsonNode json=JSON.readTree(body);check(json!=null&&json.isObject()&&json.path("jsonrpc").asText().equals("2.0")&&json.path("id").isIntegralNumber()&&json.path("id").bigIntegerValue().equals(BigInteger.valueOf(id))&&json.has("result")&&!json.has("error"),"RPC_RESPONSE");return json.get("result");}
    static void checkTransportProperties(Properties p){eq(p.getProperty("jdk.httpclient.disableRetryConnect"),"true");eq(p.getProperty("jdk.httpclient.enableAllMethodRetry"),"false");eq(p.getProperty("jdk.httpclient.redirects.retrylimit"),"1");}
    static void verifyTransportPolicy()throws Exception{
        check(Runtime.version().feature()==21,"JDK21");checkTransportProperties(System.getProperties());
        // Exactly these fixed JDK fields, never arbitrary input-controlled reflection/polymorphism.
        Class<?> type=Class.forName("jdk.internal.net.http.MultiExchange");
        for(String name:List.of("RETRY_CONNECT","RETRY_ALWAYS")){var field=type.getDeclaredField(name);check(field.trySetAccessible()&&field.getType()==boolean.class&&!field.getBoolean(null),"HTTP_INTERNAL_RETRY_FLAGS");}
        var attempts=type.getDeclaredField("max_attempts");check(attempts.trySetAccessible()&&attempts.getType()==int.class&&attempts.getInt(null)==1,"HTTP_INTERNAL_RETRY_LIMIT");
    }
    static <T> T singleAttempt(Supplier<CompletableFuture<T>> start,long milliseconds)throws Exception{CompletableFuture<T> future=start.get();try{return future.get(milliseconds,TimeUnit.MILLISECONDS);}catch(Exception e){future.cancel(true);throw new IOException("C5A1_RPC_DEADLINE_OR_TRANSPORT");}}
    static final class CappedBody implements HttpResponse.BodySubscriber<byte[]>{
        final CompletableFuture<byte[]> done=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return done;}
        public void onSubscribe(Flow.Subscription s){if(subscription!=null){s.cancel();return;}subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){try{int incoming=0;for(var b:buffers){incoming=Math.addExact(incoming,b.remaining());check(incoming<=BODY_LIMIT-bytes.size(),"RESPONSE_CAP");}for(var b:buffers){byte[] chunk=new byte[b.remaining()];b.get(chunk);bytes.write(chunk);}subscription.request(1);}catch(Exception e){cancel();done.completeExceptionally(new IOException("C5A1_RESPONSE_CAP"));}}
        public void onError(Throwable ignored){done.completeExceptionally(new IOException("C5A1_BODY_FAILED"));}
        public void onComplete(){done.complete(bytes.toByteArray());}
        void cancel(){if(subscription!=null)subscription.cancel();}
    }
    String call(String to,String sig,String...args)throws Exception{return rpc("eth_call",Map.of("to",to,"data",queryData(sig,args)),"latest").asText();}
    BigInteger callNum(String to,String sig,String...args)throws Exception{return wordNum(call(to,sig,args),0);}
    String callAddress(String to,String sig,String...args)throws Exception{return wordAddress(call(to,sig,args),0);}
    BigInteger balance(String owner)throws Exception{return callNum(token,"balanceOf(address)",addressWord(owner));}
    BigInteger free(String owner)throws Exception{return callNum(vault,"freeBalance(address)",addressWord(owner));}
    String addr(String role){return address(keys.get(role));}
    JsonNode read(Path path)throws Exception{check(Files.size(path)<=2*BODY_LIMIT,"INPUT_FILE_CAP");return JSON.readTree(Files.readAllBytes(path));}
    void save(String name,JsonNode value)throws Exception{saveRaw(name,JSON.writeValueAsBytes(value));}
    void saveRaw(String name,byte[] bytes)throws Exception{check(name.matches("[A-Za-z0-9_.-]+"),"OUTPUT_NAME");check(bytes.length<=2*BODY_LIMIT&&bytes.length<=128L*1024*1024-evidenceBytes,"EVIDENCE_CAP");Files.write(output.resolve(name),bytes,StandardOpenOption.CREATE_NEW);evidenceBytes+=bytes.length;}
    static byte[] raw(ECKeyPair key,String to,String data,BigInteger nonce){var fields=new ArrayList<RlpType>(List.of(rlp(nonce),rlp(BigInteger.valueOf(1_000_000_000)),rlp(BigInteger.valueOf(25_000_000)),RlpString.create(to==null?new byte[0]:bytes(to)),rlp(Z),RlpString.create(bytes(data))));var unsigned=new ArrayList<>(fields);unsigned.addAll(List.of(rlp(CHAIN),rlp(Z),rlp(Z)));var sig=Sign.signMessage(Hash.sha3(RlpEncoder.encode(new RlpList(unsigned))),key,false);fields.add(rlp(CHAIN.multiply(BigInteger.TWO).add(BigInteger.valueOf(35+Byte.toUnsignedInt(sig.getV()[0])-27))));fields.add(rlp(new BigInteger(1,sig.getR())));fields.add(rlp(new BigInteger(1,sig.getS())));return RlpEncoder.encode(new RlpList(fields));}
    static RlpString rlp(BigInteger n){return RlpString.create(n);}
    static String signature(String digest,ECKeyPair key){var s=Sign.signMessage(bytes(digest),key,false);return hex(s.getR())+HexFormat.of().formatHex(s.getS())+HexFormat.of().formatHex(s.getV());}
    static String address(ECKeyPair key){return "0x"+Keys.getAddress(key);}
    static String selector(String s){return Hash.sha3String(s).substring(0,10);}
    static String hash(String text){return Hash.sha3String(text);}
    static String word(BigInteger n){CAbiModels.uint(n);return String.format("%064x",n);}
    static String addressWord(String s){return "0".repeat(24)+CAbiModels.address(s).substring(2);}
    static String words(String...args){return String.join("",Arrays.stream(args).map(s->{check(s!=null,"ABI_WORD");String value=s.startsWith("0x")?s.substring(2):s;check(!value.isEmpty()&&value.length()%64==0&&value.matches("[0-9a-fA-F]+"),"ABI_WORD");return value.toLowerCase(Locale.ROOT);}).toList());}
    static String queryData(String sig,String...args){check(sig!=null&&sig.matches("[A-Za-z_][A-Za-z0-9_]*\\([A-Za-z0-9_,()\\[\\]]*\\)"),"GETTER_SIGNATURE");return selector(sig)+words(args);}
    static BigInteger wordNum(String s,int index){check(s!=null&&s.matches("0x[0-9a-fA-F]+")&&s.length()%64==2&&index>=0&&s.length()>=2+(index+1)*64,"GETTER_ABI");return new BigInteger(s.substring(2+index*64,2+(index+1)*64),16);}
    static String wordAddress(String s,int index){String word=word(wordNum(s,index));check(word.startsWith("0".repeat(24)),"GETTER_ADDRESS");return "0x"+word.substring(24);}
    static BigInteger number(JsonNode n){check(n.isTextual()&&n.asText().matches("0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)")&&n.asText().length()<=66,"RPC_QUANTITY");return new BigInteger(n.asText().substring(2),16);}
    static byte[] bytes(String s){check(s!=null&&s.matches("0x(?:[0-9a-fA-F]{2})*"),"HEX");return HexFormat.of().parseHex(s.substring(2));}
    static String hex(byte[] b){return "0x"+HexFormat.of().formatHex(b);}
    static String hex(BigInteger n){return "0x"+n.toString(16);}
    static BigInteger units(long n){return UNIT.multiply(BigInteger.valueOf(n));}
    static void check(boolean value,String code){if(!value)throw new IllegalStateException("C5A1_"+code);}
    static void eq(Object a,Object b){check(Objects.equals(a,b),"VALUE_MISMATCH");}
    static void selfTest(){
        var properties=new Properties();properties.setProperty("jdk.httpclient.disableRetryConnect","true");properties.setProperty("jdk.httpclient.enableAllMethodRetry","false");properties.setProperty("jdk.httpclient.redirects.retrylimit","1");checkTransportProperties(properties);
        for(String name:List.of("jdk.httpclient.disableRetryConnect","jdk.httpclient.enableAllMethodRetry","jdk.httpclient.redirects.retrylimit")){var wrong=new Properties();wrong.putAll(properties);wrong.remove(name);try{checkTransportProperties(wrong);throw new AssertionError("missing retry control accepted");}catch(IllegalStateException expected){}wrong.setProperty(name,name.endsWith("retrylimit")?"5":name.endsWith("disableRetryConnect")?"false":"true");try{checkTransportProperties(wrong);throw new AssertionError("wrong retry control accepted");}catch(IllegalStateException expected){}}
        eq(selector("deposit(uint256)"),"0xb6b55f25");eq(selector("transfer(address,uint256)"),"0xa9059cbb");eq(wordNum("0x"+word(CAbiModels.UINT_MAX),0),CAbiModels.UINT_MAX);
        for(String bad:List.of("0x01","0x","1","0x-1")){try{number(TextNode.valueOf(bad));throw new AssertionError("accepted bad quantity");}catch(IllegalStateException expected){}}
        check(signature(hash("offline"),ECKeyPair.create(BigInteger.valueOf(17))).length()==132,"SIG_LENGTH");
        eq(queryData("getPosition(bytes32)",hash("position")),selector("getPosition(bytes32)")+hash("position").substring(2));
        eq(queryData("balanceOf(address)",addressWord("0x"+"a".repeat(40))),selector("balanceOf(address)")+"0".repeat(24)+"a".repeat(40));
        eq(queryData("allowance(address,address)",addressWord("0x"+"a".repeat(40)),addressWord("0x"+"b".repeat(40))),selector("allowance(address,address)")+"0".repeat(24)+"a".repeat(40)+"0".repeat(24)+"b".repeat(40));
        eq(queryData("totalSupply()"),selector("totalSupply()"));
        byte[] compilerText="a\nb\r".getBytes(StandardCharsets.UTF_8);String expectedSource=hex(Hash.sha3(compilerText));
        check(compilerSourceMatches("a\r\nb\r".getBytes(StandardCharsets.UTF_8),expectedSource),"CRLF_COMPILER_INPUT");
        check(Arrays.equals(compilerSourceBytes("a\r\nb\r".getBytes(StandardCharsets.UTF_8)),compilerText),"STANDALONE_CR_PRESERVED");
        check(!compilerSourceMatches("a\r\nc\r".getBytes(StandardCharsets.UTF_8),expectedSource),"SOURCE_TAMPER_REJECTED");
        for(String malformed:List.of("0x1","0x","abc0x"+"a".repeat(64))){try{queryData("getPosition(bytes32)",malformed);throw new AssertionError("malformed ABI word accepted");}catch(IllegalStateException expected){}}
        var testOwner=ECKeyPair.create(BigInteger.valueOf(17));var testOracle=ECKeyPair.create(BigInteger.valueOf(23));String testController="0x"+"c".repeat(40),testRules="0x"+"d".repeat(40);
        Intent terms=new Intent(address(testOwner),"0x"+"b".repeat(40),hash("op"),hash("pos"),hash("account"),hash("symbol"),Z,Z,Z,Z,BigInteger.TEN,Z,Z,BigInteger.valueOf(1000),BigInteger.valueOf(100),BigInteger.valueOf(100),BigInteger.valueOf(100),BigInteger.valueOf(100),Z,BigInteger.valueOf(1000),BigInteger.valueOf(1120),hash("policy"));
        Intent nonce=withBounds(terms,ONE,terms.priceMinE8(),terms.priceMaxE8(),terms.quantityMin(),terms.quantityMax());eq(nonce.nonce(),ONE);eq(nonce.owner(),terms.owner());eq(nonce.operationId(),terms.operationId());
        Intent bounded=withBounds(terms,Z,BigInteger.valueOf(101),BigInteger.valueOf(101),BigInteger.valueOf(102),BigInteger.valueOf(102));eq(bounded.priceMaxE8(),BigInteger.valueOf(101));eq(bounded.quantityMax(),BigInteger.valueOf(102));eq(bounded.collateralAfter(),terms.collateralAfter());
        var eip=new CEip712Verifier();String intentDigest=eip.intentDigest(terms,CHAIN,testController);
        eq(eip.recover(intentDigest,signature(intentDigest,testOwner)),address(testOwner));
        for(String wrong:List.of(signature(intentDigest,testOracle),signature(eip.intentDigest(terms,CHAIN,testRules),testOwner),signature(eip.intentDigest(terms,CHAIN.add(ONE),testController),testOwner)))check(!eip.recover(intentDigest,wrong).equals(terms.owner()),"NEGATIVE_SIGNATURE_FIXTURE");
        try{
            eq(parseRpc(200,"{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":null}".getBytes(StandardCharsets.UTF_8),7),JSON.nullNode());
            for(String bad:List.of("{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":1}","{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":1,\"error\":{}}","{\"jsonrpc\":\"2.0\",\"id\":7,\"id\":7,\"result\":1}","{}","{}{}")){try{parseRpc(200,bad.getBytes(StandardCharsets.UTF_8),7);throw new AssertionError("RPC parser accepted invalid response");}catch(Exception expected){}}
            try{parseRpc(503,new byte[0],7);throw new AssertionError("accepted HTTP failure");}catch(Exception expected){}
            try{parseRpc(200,new byte[BODY_LIMIT+1],7);throw new AssertionError("accepted oversized body");}catch(Exception expected){}
            var pending=new CompletableFuture<String>();int[] calls={0};try{singleAttempt(()->{calls[0]++;return pending;},10);throw new AssertionError("no deadline");}catch(IOException expected){}check(pending.isCancelled()&&calls[0]==1,"DEADLINE_CANCEL_ONCE");
            int[] sends={0};try{singleAttempt(()->{sends[0]++;return CompletableFuture.failedFuture(new IOException("potential private sentinel"));},10);throw new AssertionError("no stop");}catch(IOException expected){check(expected.getCause()==null&&expected.getSuppressed().length==0&&!expected.toString().contains("sentinel"),"REDACTION");}eq(sends[0],1);
            var subscriber=new CappedBody();boolean[] cancelled={false};subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled[0]=true;}});subscriber.onNext(List.of(ByteBuffer.allocate(BODY_LIMIT+1)));check(cancelled[0]&&subscriber.bytes.size()==0&&subscriber.done.isCompletedExceptionally(),"BOUNDED_BODY");
            // Malformed Receipt observation remains append-only even if subsequent validation fails.
            Path temp=Files.createTempDirectory("c5a1-selftest-private-");var gate=new CAnvilContractGate(temp,temp);
            byte[] observed="{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"status\":\"0x1\",\"blockHash\":\"wrong\"}}".getBytes(StandardCharsets.UTF_8);
            gate.saveRaw("receipt-http.observed.private.json",observed);eq(Files.readString(temp.resolve("receipt-http.observed.private.json")),new String(observed,StandardCharsets.UTF_8));
            try{gate.saveRaw("receipt-http.observed.private.json",new byte[0]);throw new AssertionError("receipt overwrite accepted");}catch(FileAlreadyExistsException expected){}
            gate.save("receipt.verdict.json",JSON.createObjectNode().put("verdict","VALIDATION_REJECTED_STOP"));check(Files.size(temp.resolve("receipt-http.observed.private.json"))==observed.length,"FAILED_OBSERVATION_RETAINED");
        }catch(Exception unexpected){throw new AssertionError("C5A1_SELF_TEST_FAILURE");}
    }
}
