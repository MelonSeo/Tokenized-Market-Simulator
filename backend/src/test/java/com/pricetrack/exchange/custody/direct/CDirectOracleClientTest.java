package com.pricetrack.exchange.custody.direct;
import okhttp3.Call;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.authorization.CReportCandidateBuilder;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.*;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.Buffer;
import okio.Timeout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Call.Factory controlled transport only; no sockets, private files or real Oracle. */
public class CDirectOracleClientTest {
    static final String TOKEN="1".repeat(64);static final ObjectMapper JSON=new ObjectMapper();
    static CReportCandidateBuilder.Candidate candidate(){var c=CDirectServiceTest.identity();var now=BigInteger.valueOf(CDirectServiceTest.NOW.getEpochSecond());
        Report r=new Report(CDirectServiceTest.h(20),CDirectServiceTest.h(21),CDirectServiceTest.h(22),c.symbolHash(),BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN,BigInteger.valueOf(100),CDirectServiceTest.E8.multiply(BigInteger.valueOf(100)),BigInteger.ZERO,BigInteger.valueOf(1000),CDirectServiceTest.E8.multiply(BigInteger.valueOf(100)),now,now.add(BigInteger.valueOf(30)),c.policyHash(),c.controller().address());
        return new CReportCandidateBuilder.Candidate(r,new CEip712Verifier().reportDigest(r,c.chainId(),c.rules().address()),null,null,null);
    }
    static Map<String,String> reply(CReportCandidateBuilder.Candidate c){var id=CDirectServiceTest.identity();return new LinkedHashMap<>(Map.of("deploymentId",id.deploymentId(),"reportDigest",c.reportDigest(),"oracle",id.oracle(),"signature",CDirectServiceTest.sign(c.report())));}
    interface Responder {Response answer(Request r)throws IOException;}
    static class Factory implements Call.Factory {final AtomicInteger count=new AtomicInteger();final Responder responder;Request request;Factory(Responder r){responder=r;}public Call newCall(Request r){count.incrementAndGet();request=r;return new Fake(r,responder);}}
    static class Fake implements Call {final Request request;final Responder responder;boolean executed,cancelled;Fake(Request r,Responder p){request=r;responder=p;}public Request request(){return request;}public Response execute()throws IOException{executed=true;return responder.answer(request);}public void enqueue(Callback callback){throw new AssertionError("EXPECTED_SINGLE_SYNCHRONOUS_ORACLE_CALL");}public void cancel(){cancelled=true;}public boolean isExecuted(){return executed;}public boolean isCanceled(){return cancelled;}public Timeout timeout(){return new Timeout();}public Call clone(){return new Fake(request,responder);}}
    static Response response(Request request,int code,String body){return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(ResponseBody.create(body,MediaType.get("application/json"))).build();}
    static String json(Object v){try{return JSON.writeValueAsString(v);}catch(Exception e){throw new AssertionError(e);}}
    static String failure(CDirectOracleClient c,CReportCandidateBuilder.Candidate candidate){var error=assertThrows(CCustodyFailure.class,()->c.sign(CDirectServiceTest.identity(),candidate));assertEquals(503,error.status());assertNull(error.getCause());assertFalse(error.toString().contains(TOKEN));return error.code();}
    @Test void oneExactFixedRequestReturnsOnlyVerifiedOracleSignature()throws Exception{var c=candidate();var f=new Factory(r->response(r,200,json(reply(c))));try(var client=new CDirectOracleClient(TOKEN,f)){assertEquals(CDirectServiceTest.sign(c.report()),client.sign(CDirectServiceTest.identity(),c));}assertEquals(1,f.count.get());assertEquals("http://oracle:8091/sign",f.request.url().toString());assertEquals("POST",f.request.method());assertEquals(TOKEN,f.request.header("X-TMS-C-Oracle-Token"));assertEquals("close",f.request.header("Connection"));var buffer=new Buffer();f.request.body().writeTo(buffer);var body=JSON.readTree(buffer.readUtf8());var names=new HashSet<String>();body.fieldNames().forEachRemaining(names::add);assertEquals(Set.of("deploymentId","report"),names);assertEquals(CDirectServiceTest.identity().deploymentId(),body.path("deploymentId").asText());assertFalse(body.has("privateKey"));}
    @Test void httpAuthRedirectAndUnavailableNeverRetry(){for(int status:List.of(401,302,503)){var f=new Factory(r->response(r,status,"private HTTP error body"));try(var client=new CDirectOracleClient(TOKEN,f)){assertEquals("C_DIRECT_ORACLE_UNAVAILABLE_STOP_NO_RETRY",failure(client,candidate()));}assertEquals(1,f.count.get());}}
    @Test void invalidDigestIsBlockedBeforeAnyTransport(){var c=candidate();var changed=new CReportCandidateBuilder.Candidate(c.report(),CDirectServiceTest.h(999),null,null,null);var f=new Factory(r->{throw new AssertionError("NO_TRANSPORT");});try(var client=new CDirectOracleClient(TOKEN,f)){assertEquals("C_DIRECT_ORACLE_UNAVAILABLE_STOP_NO_RETRY",failure(client,changed));}assertEquals(0,f.count.get());}
    @Test void wrongIdentityDigestOracleAndSignatureFailWithOneCall(){var c=candidate();for(String field:List.of("deploymentId","reportDigest","oracle","signature")){var body=reply(c);body.put(field,field.equals("signature")?"0x"+"00".repeat(65):field.equals("oracle")?CDirectServiceTest.a(99):CDirectServiceTest.h(999));var f=new Factory(r->response(r,200,json(body)));try(var client=new CDirectOracleClient(TOKEN,f)){assertEquals("C_DIRECT_ORACLE_UNAVAILABLE_STOP_NO_RETRY",failure(client,c));}assertEquals(1,f.count.get());}}
    @Test void oversizedDuplicateTrailingMalformedAndIoFailuresStopWithoutRetry(){var c=candidate();for(String body:List.of("x".repeat(8193),"{\"x\":1,\"x\":2}",json(reply(c))+" {}","[]","{invalid")){var f=new Factory(r->response(r,200,body));try(var client=new CDirectOracleClient(TOKEN,f)){assertEquals("C_DIRECT_ORACLE_UNAVAILABLE_STOP_NO_RETRY",failure(client,c));}assertEquals(1,f.count.get());}var f=new Factory(r->{throw new IOException("PRIVATE_IO_DETAIL");});try(var client=new CDirectOracleClient(TOKEN,f)){assertEquals("C_DIRECT_ORACLE_UNAVAILABLE_STOP_NO_RETRY",failure(client,c));}assertEquals(1,f.count.get());}
    @Test void ownedClientDisablesProxyAuthenticationRedirectAndRetry()throws Exception{try(var client=new CDirectOracleClient(TOKEN)){var field=CDirectOracleClient.class.getDeclaredField("owned");field.setAccessible(true);var c=(OkHttpClient)field.get(client);assertEquals(Proxy.NO_PROXY,c.proxy());assertFalse(c.retryOnConnectionFailure());assertFalse(c.followRedirects());assertFalse(c.followSslRedirects());assertSame(okhttp3.Authenticator.NONE,c.authenticator());assertSame(okhttp3.Authenticator.NONE,c.proxyAuthenticator());assertEquals(3000,c.callTimeoutMillis());assertEquals(1,c.networkInterceptors().size());}assertThrows(IllegalArgumentException.class,()->new CDirectOracleClient("bad",r->{throw new AssertionError();}));}
}
