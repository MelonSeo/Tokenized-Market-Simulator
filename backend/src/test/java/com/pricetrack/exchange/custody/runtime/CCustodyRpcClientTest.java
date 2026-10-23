package com.pricetrack.exchange.custody.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Source;
import okio.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** No sockets, Docker or real RPC. Actual HTTP/connection behaviour requires the Linux Gate. */
class CCustodyRpcClientTest {
    private static final URI ENDPOINT=URI.create("http://anvil:8545");
    private static final String OWNER="0x"+"1".repeat(40),HASH="0x"+"2".repeat(64);
    private static final Map<String,Object> PIN=Map.of("blockHash",HASH,"requireCanonical",true);
    private boolean parentHandlers;
    @BeforeEach void captureExpectedWarningsLocally(){var logger=Logger.getLogger(CCustodyRpcClient.class.getName());parentHandlers=logger.getUseParentHandlers();logger.setUseParentHandlers(false);}
    @AfterEach void restoreWarningDestination(){Logger.getLogger(CCustodyRpcClient.class.getName()).setUseParentHandlers(parentHandlers);}

    @Test void dedicatedClientHasNoRetryRedirectProxyOrAuthentication(){
        OkHttpClient client=CCustodyRpcClient.buildClient();
        assertFalse(client.retryOnConnectionFailure());assertFalse(client.followRedirects());assertFalse(client.followSslRedirects());
        assertEquals(Proxy.NO_PROXY,client.proxy());assertSame(Authenticator.NONE,client.authenticator());assertSame(Authenticator.NONE,client.proxyAuthenticator());
        assertEquals(3000,client.callTimeoutMillis());assertEquals(3000,client.connectTimeoutMillis());assertEquals(3000,client.readTimeoutMillis());
        assertEquals(1,client.networkInterceptors().size());
    }
    @Test void rejectsUnsafeEndpointWithoutCreatingAnyCall(){
        FakeFactory factory=new FakeFactory((c,b)->{});
        for(String uri:List.of("ftp://anvil:8545","http://secret@anvil:8545","http://anvil:8545?api-key=secret","http://anvil:8545#fragment","http://anvil:0","http://anvil:65536"))
            assertThrows(IllegalArgumentException.class,()->new CCustodyRpcClient(URI.create(uri),factory));
        assertEquals(0,factory.calls.size());
    }
    @Test void rejectsWriteMethodsAndUnpinnedParametersBeforeTransport(){
        FakeFactory factory=new FakeFactory((c,b)->{});
        try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
            for(String method:List.of("eth_sendRawTransaction","eth_sendTransaction","anvil_setBalance","evm_increaseTime","personal_sign"))
                assertEquals("C_RPC_METHOD_NOT_ALLOWED",failure(()->client.request(method,List.of(),3000)).code());
            assertEquals("C_RPC_METHOD_NOT_ALLOWED",failure(()->client.request(null,List.of(),3000)).code());
            assertEquals("C_RPC_PARAMS_INVALID",failure(()->client.request("eth_getCode",List.of(OWNER,"latest"),3000)).code());
            assertEquals("C_RPC_PARAMS_INVALID",failure(()->client.request("eth_call",List.of(Map.of("to",OWNER,"data","0x12345678","value","0x1"),PIN),3000)).code());
            assertEquals("C_RPC_PARAMS_INVALID",failure(()->client.request("eth_getBlockByNumber",List.of("pending",false),3000)).code());
            assertEquals("C_RPC_PARAMS_INVALID",failure(()->client.request("eth_chainId",List.of("unexpected"),3000)).code());
        }
        assertEquals(0,factory.calls.size());
    }
    @Test void sendsOneExactReadRequestAndClosesBody(){
        TrackingBody body=body("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x7a69\"}");
        FakeFactory factory=new FakeFactory((c,b)->respond(c,b,200,body));
        try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
            assertEquals("0x7a69",client.request("eth_chainId",List.of(),3000).asText());
        }
        assertEquals(1,factory.calls.size());FakeCall call=factory.calls.get(0);assertEquals(1,call.enqueues.get());
        assertEquals("POST",call.request().method());assertEquals(ENDPOINT.toString()+"/",call.request().url().toString());
        assertFalse(call.synchronous);assertTrue(call.cancelled);assertEquals(1,body.closed.get());
    }
    @Test void acceptsOnlyThePinnedShapeForCodeAndCall(){
        FakeFactory factory=new FakeFactory((c,b)->respond(c,b,200,body(reply(c,"\"0x\""))));
        try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
            client.request("eth_getCode",List.of(OWNER,PIN),3000);
            client.request("eth_call",List.of(Map.of("to",OWNER,"data","0x12345678"),PIN),3000);
            client.request("eth_getBlockByNumber",List.of("0x0",false),3000);
        }
        assertEquals(3,factory.calls.size());assertTrue(factory.calls.stream().allMatch(c->c.enqueues.get()==1));
    }
    @Test void transactionAndReceiptReadsReachExactlyOneInjectedTransportCall(){
        for(String method:List.of("eth_getTransactionByHash","eth_getTransactionReceipt")){
            FakeFactory factory=new FakeFactory((c,b)->respond(c,b,200,body(reply(c,"null"))));
            try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
                assertTrue(client.request(method,List.of(HASH),3000).isNull());
            }
            assertEquals(1,factory.calls.size());assertEquals(1,factory.calls.getFirst().enqueues.get());
            try{var buffer=new Buffer();factory.calls.getFirst().request().body().writeTo(buffer);
                var body=new ObjectMapper().readTree(buffer.readUtf8());assertEquals(method,body.path("method").asText());
                assertEquals(1,body.path("params").size());assertEquals(HASH,body.path("params").get(0).asText());
            }catch(IOException e){throw new AssertionError(e);}
        }
    }
    @Test void transactionReadsRejectWrongHashAndArityBeforeAnyTransportCall(){
        FakeFactory factory=new FakeFactory((c,b)->{});
        try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
            for(String method:List.of("eth_getTransactionByHash","eth_getTransactionReceipt")){
                for(List<Object> params:List.<List<Object>>of(List.of(),List.of(HASH,false),List.of("latest"),List.of("0x"),List.of(OWNER),List.of("0x"+"g".repeat(64))))
                    assertEquals("C_RPC_PARAMS_INVALID",failure(()->client.request(method,params,3000)).code());
            }
        }
        assertTrue(factory.calls.isEmpty());
    }
    @Test void rejectsDuplicateTrailingIdAndRpcErrorWithoutLeakingPayload(){
        List<String> invalid=List.of(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":1,\"result\":1}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1} {}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":1}",
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":1}",
            "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":1}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1,\"error\":{\"message\":\"private-sentinel\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"private-sentinel\"}}",
            "[]");
        for(String payload:invalid){
            FakeFactory factory=new FakeFactory((c,b)->respond(c,b,200,body(payload)));
            try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
                CCustodyFailure safe=failure(()->client.request("eth_chainId",List.of(),3000));
                assertFalse(safe.toString().contains("private-sentinel"));assertNull(safe.getCause());assertEquals(0,safe.getSuppressed().length);
            }
            assertEquals(1,factory.calls.size());
        }
    }
    @Test void nullResultIsAnObservationNotTransportSuccessWithInventedValue(){
        FakeFactory factory=new FakeFactory((c,b)->respond(c,b,200,body(reply(c,"null"))));
        try(var client=new CCustodyRpcClient(ENDPOINT,factory)){assertTrue(client.request("eth_chainId",List.of(),3000).isNull());}
    }
    @Test void rejectsInvalidUtf8AndOversizedBody(){
        byte[] invalid={(byte)0xc3,(byte)0x28};
        for(TrackingBody body:List.of(new TrackingBody(invalid,invalid.length),new TrackingBody(new byte[0],CCustodyRpcClient.MAX_BODY_BYTES+1L),
            new TrackingBody(new byte[CCustodyRpcClient.MAX_BODY_BYTES+1],-1))){
            FakeFactory factory=new FakeFactory((c,b)->respond(c,b,200,body));
            try(var client=new CCustodyRpcClient(ENDPOINT,factory)){failure(()->client.request("eth_chainId",List.of(),3000));}
            assertEquals(1,body.closed.get());assertEquals(1,factory.calls.size());
        }
    }
    @Test void non200CallbackIsRejectedEvenForFakeFactoryThatBypassesInterceptors(){
        for(int status:List.of(301,307,401,407,408,421,503)){
            TrackingBody body=body("private-sentinel");FakeFactory factory=new FakeFactory((c,b)->respond(c,b,status,body));
            try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
                assertEquals("C_RPC_HTTP_STATUS",failure(()->client.request("eth_chainId",List.of(),3000)).code());
            }
            assertEquals(1,body.closed.get());assertEquals(1,factory.calls.size());
        }
    }
    @Test void networkInterceptorRejects503BeforeRetryFollowupAndClosesResponse(){
        for(int status:List.of(301,307,401,407,408,421,503)){
            var attempt=new CCustodyRpcClient.Attempt();TrackingBody body=body("private-sentinel");
            Request request=new Request.Builder().url(ENDPOINT.toString()).tag(CCustodyRpcClient.Attempt.class,attempt).build();
            FakeChain chain=new FakeChain(request,status,body);
            IOException safe=assertThrows(IOException.class,()->CCustodyRpcClient.singleExchange(chain));
            assertEquals("C_RPC_HTTP_STATUS",safe.getMessage());assertEquals("C_RPC_HTTP_STATUS",attempt.failure.get());
            assertEquals(1,chain.proceeds.get());assertEquals(1,body.closed.get());
        }
    }
    @Test void secondExchangeIsBlockedBeforeChainProceedAndStateIsPerCall()throws Exception{
        var first=new CCustodyRpcClient.Attempt();Request request=new Request.Builder().url(ENDPOINT.toString()).tag(CCustodyRpcClient.Attempt.class,first).build();
        FakeChain chain=new FakeChain(request,200,body("{}"));
        CCustodyRpcClient.singleExchange(chain).close();assertThrows(IOException.class,()->CCustodyRpcClient.singleExchange(chain));assertEquals(1,chain.proceeds.get());
        var second=new CCustodyRpcClient.Attempt();FakeChain other=new FakeChain(request.newBuilder().tag(CCustodyRpcClient.Attempt.class,second).build(),200,body("{}"));
        CCustodyRpcClient.singleExchange(other).close();assertEquals(1,other.proceeds.get());assertEquals(1,second.exchanges.get());
    }
    @Test void connectionAndHeaderEventsCancelSecondAttemptWithoutThrowingListenerException(){
        OkHttpClient client=CCustodyRpcClient.buildClient();var attempt=new CCustodyRpcClient.Attempt();
        Request request=new Request.Builder().url(ENDPOINT.toString()).tag(CCustodyRpcClient.Attempt.class,attempt).build();
        FakeCall call=new FakeCall(request,(c,b)->{});var listener=client.eventListenerFactory().create(call);
        listener.connectStart(call,new InetSocketAddress("127.0.0.1",1),Proxy.NO_PROXY);assertFalse(call.cancelled);
        listener.connectStart(call,new InetSocketAddress("127.0.0.1",1),Proxy.NO_PROXY);assertTrue(call.cancelled);assertEquals("C_RPC_RETRY_BLOCKED",attempt.failure.get());
        FakeCall headers=new FakeCall(request.newBuilder().tag(CCustodyRpcClient.Attempt.class,new CCustodyRpcClient.Attempt()).build(),(c,b)->{});
        var headerListener=client.eventListenerFactory().create(headers);headerListener.requestHeadersStart(headers);assertFalse(headers.cancelled);
        headerListener.requestHeadersStart(headers);assertTrue(headers.cancelled);
    }
    @Test void timeoutCancelsWithoutRetryAndLateResponseIsClosed(){
        FakeFactory factory=new FakeFactory((c,b)->{});
        try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
            assertEquals("C_RPC_TIMEOUT",failure(()->client.request("eth_chainId",List.of(),25)).code());
            assertEquals(1,factory.calls.size());FakeCall call=factory.calls.get(0);assertTrue(call.cancelled);
            TrackingBody late=body("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1}");respond(call,call.callback,200,late);
            assertEquals(1,late.closed.get());assertEquals(1,call.enqueues.get());
        }
    }
    @Test void transportFailureDoesNotAttachOrLogPrivateException(){
        Logger logger=Logger.getLogger(CCustodyRpcClient.class.getName());List<String> logs=new ArrayList<>();
        Handler handler=new Handler(){public void publish(LogRecord record){logs.add(record.getMessage());assertNull(record.getThrown());}public void flush(){}public void close(){}};
        logger.addHandler(handler);
        FakeFactory factory=new FakeFactory((c,b)->b.onFailure(c,new IOException("private-sentinel http://secret@host raw-jwt")));
        try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
            CCustodyFailure safe=failure(()->client.request("eth_chainId",List.of(),3000));assertEquals("C_RPC_TRANSPORT",safe.code());assertNull(safe.getCause());
            assertFalse(safe.toString().contains("private-sentinel"));assertTrue(logs.stream().noneMatch(s->s.contains("private-sentinel")||s.contains("raw-jwt")||s.contains("anvil:8545")));
            assertTrue(logs.stream().anyMatch(s->s.contains("correlation=")&&s.contains("elapsedMs=")&&s.contains("method=eth_chainId")));
        }finally{logger.removeHandler(handler);}
    }
    @Test void closedClientCannotCreateOrSubmitAnotherCall(){
        FakeFactory factory=new FakeFactory((c,b)->{});var client=new CCustodyRpcClient(ENDPOINT,factory);client.close();client.close();
        assertEquals("C_RPC_CLOSED",failure(()->client.request("eth_chainId",List.of(),3000)).code());assertEquals(0,factory.calls.size());
    }
    @Test void rejectsInvalidDeadlineBeforeCallCreation(){
        FakeFactory factory=new FakeFactory((c,b)->{});
        try(var client=new CCustodyRpcClient(ENDPOINT,factory)){
            for(long timeout:List.of(0L,3001L,Long.MAX_VALUE))assertEquals("C_RPC_DEADLINE_INVALID",failure(()->client.request("eth_chainId",List.of(),timeout)).code());
        }
        assertEquals(0,factory.calls.size());
    }
    private static CCustodyFailure failure(Runnable task){return assertThrows(CCustodyFailure.class,task::run);}
    private static TrackingBody body(String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);return new TrackingBody(bytes,bytes.length);}
    private static String reply(FakeCall call,String result){
        try{Buffer buffer=new Buffer();call.request.body().writeTo(buffer);JsonNode request=new ObjectMapper().readTree(buffer.readUtf8());return "{\"jsonrpc\":\"2.0\",\"id\":"+request.path("id").asLong()+",\"result\":"+result+"}";}
        catch(IOException error){throw new AssertionError(error);}
    }
    private static void respond(FakeCall call,Callback callback,int status,ResponseBody body){
        try{callback.onResponse(call,new Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1).code(status).message("controlled").body(body).header("Retry-After","0").build());}
        catch(IOException error){throw new AssertionError(error);}
    }
    static final class TrackingBody extends ResponseBody {
        final AtomicInteger closed=new AtomicInteger();final long length;final BufferedSource source;
        TrackingBody(byte[] bytes,long length){this.length=length;Buffer buffer=new Buffer().write(bytes);source=okio.Okio.buffer(new Source(){
            public long read(Buffer sink,long count)throws IOException{return buffer.read(sink,count);}public Timeout timeout(){return Timeout.NONE;}
            public void close(){closed.incrementAndGet();}
        });}
        public MediaType contentType(){return MediaType.get("application/json");}public long contentLength(){return length;}public BufferedSource source(){return source;}
    }
    static final class FakeFactory implements Call.Factory {
        final List<FakeCall> calls=new CopyOnWriteArrayList<>();final BiConsumer<FakeCall,Callback> behaviour;
        FakeFactory(BiConsumer<FakeCall,Callback> behaviour){this.behaviour=behaviour;}
        public Call newCall(Request request){FakeCall call=new FakeCall(request,behaviour);calls.add(call);return call;}
    }
    static final class FakeCall implements Call {
        final Request request;final BiConsumer<FakeCall,Callback> behaviour;final AtomicInteger enqueues=new AtomicInteger();final Timeout timeout=new Timeout();
        volatile boolean cancelled,synchronous;volatile Callback callback;
        FakeCall(Request request,BiConsumer<FakeCall,Callback> behaviour){this.request=request;this.behaviour=behaviour;}
        public Request request(){return request;}public Response execute(){synchronous=true;throw new AssertionError("SYNC_NOT_ALLOWED");}
        public void enqueue(Callback callback){this.callback=callback;enqueues.incrementAndGet();behaviour.accept(this,callback);}
        public void cancel(){cancelled=true;}public boolean isExecuted(){return enqueues.get()!=0;}public boolean isCanceled(){return cancelled;}
        public Timeout timeout(){return timeout;}public Call clone(){return new FakeCall(request,behaviour);}
    }
    static final class FakeChain implements Interceptor.Chain {
        final Request request;final int status;final ResponseBody body;final AtomicInteger proceeds=new AtomicInteger();
        FakeChain(Request request,int status,ResponseBody body){this.request=request;this.status=status;this.body=body;}
        public Request request(){return request;}public Response proceed(Request next){proceeds.incrementAndGet();return new Response.Builder().request(next).protocol(Protocol.HTTP_1_1).code(status).message("controlled").header("Retry-After","0").body(body).build();}
        public Connection connection(){return null;}public Call call(){return new FakeCall(request,(c,b)->{});}
        public int connectTimeoutMillis(){return 3000;}public Interceptor.Chain withConnectTimeout(int n,TimeUnit unit){return this;}
        public int readTimeoutMillis(){return 3000;}public Interceptor.Chain withReadTimeout(int n,TimeUnit unit){return this;}
        public int writeTimeoutMillis(){return 3000;}public Interceptor.Chain withWriteTimeout(int n,TimeUnit unit){return this;}
    }
}
