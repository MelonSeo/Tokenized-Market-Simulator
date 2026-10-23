import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.CCustodyIdentity;
import com.sun.net.httpserver.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** New local internal Oracle role only. Never user/relayer keys, transactions or arbitrary hash signing. */
public final class CDirectOracleServer {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final CEip712Verifier CRYPTO=new CEip712Verifier();
    static void check(boolean ok,String code){if(!ok)throw new IllegalArgumentException("C_DIRECT_ORACLE_"+code);}
    static long sessionSeconds(String value){long seconds=Long.parseLong(value);check(seconds>=30&&seconds<=3600,"LIFETIME");return seconds;}
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("--self-test")){
            String target="0x"+"1".repeat(40);BigInteger chain=BigInteger.valueOf(31337);
            check(!CRYPTO.reportDomain(chain,target).equals(CRYPTO.intentDomain(chain,target)),"DOMAIN_SEPARATION");
            check(Report.class.getRecordComponents().length==18,"REPORT_FIELDS");
            for(String valid:List.of("30","900","1800","3600"))sessionSeconds(valid);
            for(String invalid:List.of("0","29","3601","-1")){
                try{sessionSeconds(invalid);throw new AssertionError("C_ORACLE_UNBOUNDED_SESSION");}catch(IllegalArgumentException expected){}
            }
            System.out.println("C_DIRECT_ORACLE_COMPILED_NO_SOCKET_SELF_TEST_PASS");return;
        }
        check(args.length==6,"ARGS");
        CCustodyIdentity id=JSON.readValue(Files.readAllBytes(Path.of(args[0])),CCustodyIdentity.class);
        String key=Files.readString(Path.of(args[1])).trim(),token=Files.readString(Path.of(args[2])).trim();
        check(key.matches("[0-9a-f]{64}")&&token.matches("[0-9a-f]{64}"),"PRIVATE_CONFIG");
        BigInteger privateValue=new BigInteger(key,16);check(privateValue.signum()>0&&privateValue.compareTo(Sign.CURVE_PARAMS.getN())<0,"PRIVATE_RANGE");
        ECKeyPair pair=ECKeyPair.create(privateValue);
        check(("0x"+Keys.getAddress(pair)).equals(id.oracle()),"FIXED_SIGNER");
        Path control=Path.of(args[3]),evidence=Path.of(args[4]);
        check(Files.isDirectory(control)&&Files.isDirectory(evidence),"OWNED_DIRECTORIES");
        long seconds=sessionSeconds(args[5]);
        long until=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        var workers=Executors.newFixedThreadPool(2);
        HttpServer server=HttpServer.create(new InetSocketAddress("0.0.0.0",8091),8);server.setExecutor(workers);
        server.createContext("/sign",exchange->{
            try{
                if(!exchange.getRequestMethod().equals("POST")||!exchange.getRequestURI().toString().equals("/sign")
                    ||!MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),exchange.getRequestHeaders().getFirst("X-TMS-C-Oracle-Token")==null?new byte[0]:exchange.getRequestHeaders().getFirst("X-TMS-C-Oracle-Token").getBytes(StandardCharsets.US_ASCII))){respond(exchange,403,Map.of("code","C_ORACLE_SCOPE"));return;}
                String length=exchange.getRequestHeaders().getFirst("Content-Length");
                if(length==null||!length.matches("[1-9][0-9]{0,4}")||Integer.parseInt(length)>16384){respond(exchange,400,Map.of("code","C_ORACLE_REQUEST_SIZE"));return;}
                byte[] body=exchange.getRequestBody().readNBytes(16385);check(body.length==Integer.parseInt(length),"BODY");
                JsonNode request=JSON.readTree(body);check(request.isObject()&&request.size()==2&&id.deploymentId().equals(request.path("deploymentId").asText()),"IDENTITY");
                Report report=JSON.treeToValue(request.get("report"),Report.class);validate(id,report,Instant.now());
                String digest=CRYPTO.reportDigest(report,id.chainId(),id.rules().address());
                Path claim=evidence.resolve("op-"+report.operationId().substring(2)+".claim");
                Path result=evidence.resolve("op-"+report.operationId().substring(2)+".private.json");
                synchronized(CDirectOracleServer.class){
                    if(Files.exists(claim)){
                        if(Files.isRegularFile(result)){
                            JsonNode saved=JSON.readTree(Files.readAllBytes(result));
                            if(digest.equals(saved.path("reportDigest").asText())){respond(exchange,200,saved);return;}
                        }
                        respond(exchange,409,Map.of("code","C_ORACLE_CLAIM_EXISTS_STOP"));return;
                    }
                    Files.writeString(claim,digest,StandardCharsets.US_ASCII,StandardOpenOption.CREATE_NEW);
                    var s=Sign.signMessage(Numeric.hexStringToByteArray(digest),pair,false);
                    String signature=Numeric.toHexString(concat(s.getR(),s.getS(),s.getV()));
                    CRYPTO.verifyReport(report,id.chainId(),id.rules().address(),id.oracle(),signature);
                    var value=Map.of("deploymentId",id.deploymentId(),"reportDigest",digest,"oracle",id.oracle(),"signature",signature);
                    Files.write(result,JSON.writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);
                    respond(exchange,200,value);
                }
            }catch(Exception ignored){try{respond(exchange,503,Map.of("code","C_ORACLE_STOP_NO_RETRY"));}catch(Exception closed){exchange.close();}}
        });
        try{
            server.start();Files.writeString(evidence.resolve("ready.json"),JSON.writeValueAsString(Map.of("deploymentId",id.deploymentId(),"oracle",id.oracle(),"sessionSeconds",seconds,"scope","INTERNAL_FIXED_REPORT_SIGNER_NOT_USER_OR_RELAYER")),StandardOpenOption.CREATE_NEW);
            while(!Files.exists(control.resolve("STOP"))&&System.nanoTime()<until)Thread.sleep(100);
        }finally{server.stop(1);workers.shutdownNow();}
    }
    static void validate(CCustodyIdentity id,Report r,Instant now){
        check(r!=null&&r.executor().equals(id.controller().address())&&r.symbolHash().equals(id.symbolHash())&&r.policyHash().equals(id.policyHash())
            &&Set.of(BigInteger.ZERO,BigInteger.ONE,BigInteger.valueOf(4)).contains(r.action()),"REPORT_SCOPE");
        BigInteger time=BigInteger.valueOf(now.getEpochSecond());
        check(r.observedAt().compareTo(time.add(BigInteger.TWO))<=0&&r.validUntil().equals(r.observedAt().add(BigInteger.valueOf(30)))
            &&time.compareTo(r.validUntil())<=0&&r.observedAt().compareTo(time.subtract(BigInteger.valueOf(5)))>=0,"REPORT_WINDOW");
        check(r.priceE8().signum()>0&&r.priceE8().compareTo(BigInteger.TEN.pow(20))<0&&r.quantity().signum()>0&&r.quantity().compareTo(BigInteger.TEN.pow(30))<0,"REPORT_BOUNDS");
    }
    static byte[] concat(byte[]...parts){int n=0;for(var p:parts)n+=p.length;byte[] out=new byte[n];int at=0;for(var p:parts){System.arraycopy(p,0,out,at,p.length);at+=p.length;}return out;}
    static void respond(HttpExchange x,int status,Object value)throws Exception{byte[] data=JSON.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("Connection","close");x.sendResponseHeaders(status,data.length);try(var out=x.getResponseBody()){out.write(data);}finally{x.close();}}
}
