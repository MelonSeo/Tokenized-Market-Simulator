package com.pricetrack.exchange.futures.chain;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import okhttp3.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FuturesRpcHttpTest {
    Logger logger;ListAppender<ILoggingEvent> events;Level previous;
    @BeforeEach void start(){logger=(Logger)LoggerFactory.getLogger(FuturesRpcHttp.class);previous=logger.getLevel();logger.setLevel(Level.INFO);events=new ListAppender<>();events.start();logger.addAppender(events);}
    @AfterEach void close(){logger.detachAppender(events);events.stop();logger.setLevel(previous);}
    String logs(){return events.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));}
    @Test void clientPolicyIsUnchanged(){
        var expected=new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build();var actual=FuturesRpcHttp.client();
        assertThat(actual.callTimeoutMillis()).isEqualTo(3000);assertThat(actual.connectTimeoutMillis()).isEqualTo(expected.connectTimeoutMillis());assertThat(actual.readTimeoutMillis()).isEqualTo(expected.readTimeoutMillis());assertThat(actual.writeTimeoutMillis()).isEqualTo(expected.writeTimeoutMillis());assertThat(actual.retryOnConnectionFailure()).isEqualTo(expected.retryOnConnectionFailure());assertThat(actual.proxy()).isEqualTo(expected.proxy());assertThat(actual.proxySelector()).isSameAs(expected.proxySelector());
    }
    @Test void timeoutReportsLastPhaseNotExceptionOrRequestSecrets(){
        var listener=new FuturesRpcHttp.TraceFactory().create(null);listener.connectStart(null,new InetSocketAddress("127.0.0.1",1),Proxy.NO_PROXY);listener.requestBodyEnd(null,987);
        listener.callFailed(null,new InterruptedIOException("JWT-key-userSignature-raw-secret"));
        assertThat(logs()).contains("phase=REQUEST_BODY_COMPLETED","TRANSPORT_TIMEOUT","reused=false","event=CALL_FAILED","phaseElapsedMs=").doesNotContain("WAIT_RESPONSE_HEADERS","JWT","userSignature","raw-secret","987");assertThat(events.list.get(0).getThrowableProxy()).isNull();
    }
    @Test void reuseIsScopedToClientFactoryAndFailurePhaseRemainsConnect(){
        var factory=new FuturesRpcHttp.TraceFactory();var connection=mock(Connection.class);var one=factory.create(null);one.connectionAcquired(null,connection);one.callEnd(null);var two=factory.create(null);two.connectionAcquired(null,connection);two.callEnd(null);
        assertThat(events.list.get(0).getFormattedMessage()).contains("reused=false");assertThat(events.list.get(1).getFormattedMessage()).contains("reused=true");
        var isolated=new FuturesRpcHttp.TraceFactory().create(null);isolated.connectStart(null,new InetSocketAddress("127.0.0.1",9),Proxy.NO_PROXY);isolated.callFailed(null,new ConnectException("private endpoint"));assertThat(logs()).contains("phase=CONNECT_START","CONNECT_FAILURE").doesNotContain("private endpoint");
    }
    @Test void failureBoundariesKeepLastPhaseAndFixedCategories(){
        var listener=new FuturesRpcHttp.TraceFactory().create(null);
        listener.connectStart(null,new InetSocketAddress("127.0.0.1",7),Proxy.NO_PROXY);
        listener.connectFailed(null,null,null,null,new ConnectException("secret-endpoint"));
        listener.requestHeadersStart(null);listener.requestFailed(null,new SocketException("private-raw"));
        listener.requestBodyEnd(null,42);listener.responseFailed(null,new InterruptedIOException("JWT-body"));
        assertThat(logs()).contains("event=CONNECT_FAILED phase=CONNECT_START","CONNECT_FAILURE","event=REQUEST_FAILED phase=REQUEST_HEADERS","SOCKET_FAILURE","event=RESPONSE_FAILED phase=REQUEST_BODY_COMPLETED","TRANSPORT_TIMEOUT").doesNotContain("secret-endpoint","private-raw","JWT-body");
        assertThat(events.list).allSatisfy(e->assertThat(e.getThrowableProxy()).isNull());
    }
    @Test void snapshotSurvivesCallerScopeAndHeaderCallbackDoesNotClaimReadStart(){
        final okhttp3.EventListener[] listener=new okhttp3.EventListener[1];
        FuturesDiagnostics.work(FuturesDiagnostics.Stage.DRAFT,()->{
            try{return FuturesDiagnostics.io(FuturesDiagnostics.Stage.USER_NONCE,()->{listener[0]=new FuturesRpcHttp.TraceFactory().create(null);return null;});}
            catch(IOException x){throw new AssertionError(x);}
        });
        assertThat(FuturesDiagnostics.context().trace()).isEqualTo("NONE");assertThat(FuturesDiagnostics.context().stage()).isEqualTo("NONE");
        listener[0].responseHeadersStart(null);listener[0].callEnd(null);
        assertThat(logs()).contains("rpcStage=USER_NONCE","phase=RESPONSE_HEADERS_READ_RETURNED").doesNotContain("trace=NONE","WAIT_RESPONSE_HEADERS");
    }
    @Test void delayedLocalHeadersRespectProductionCallDeadline()throws Exception {
        ServerSocket server=new ServerSocket();
        try{server.bind(new InetSocketAddress("127.0.0.1",0));}
        catch(SocketException unavailable){server.close();Assumptions.assumeTrue(false,"LOCAL_LISTENER_UNAVAILABLE_SOCKET_BOUNDARY");return;}
        var received=new java.util.concurrent.CountDownLatch(1);
        var accepted=new java.util.concurrent.atomic.AtomicInteger();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread peer=new Thread(()->{
            try(var socket=server.accept()){
                accepted.incrementAndGet();socket.setSoTimeout(2000);
                var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII));
                String line;while((line=input.readLine())!=null&&!line.isEmpty()){}input.read();input.read();received.countDown();
                Thread.sleep(5000); // Deliberately no response headers before the production 3s call deadline.
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            catch(Throwable problem){failure.set(problem);}
        },"owned-rpc-header-delay-test");peer.setDaemon(true);peer.start();
        var client=FuturesRpcHttp.client().newBuilder().proxy(Proxy.NO_PROXY).build();
        try(server){
            var request=new Request.Builder().url("http://127.0.0.1:"+server.getLocalPort()+"/").post(RequestBody.create("{}",MediaType.get("application/json"))).build();
            assertThatThrownBy(()->{try(var response=client.newCall(request).execute()){throw new AssertionError("DELAYED_HEADERS_MUST_NOT_SUCCEED");}}).isInstanceOf(InterruptedIOException.class);
            assertThat(received.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(failure.get()).isNull();
            assertThat(accepted.get()).isEqualTo(1);
            assertThat(logs()).contains("event=CALL_FAILED","phase=REQUEST_BODY_COMPLETED","TRANSPORT_TIMEOUT").doesNotContain("RESPONSE_HEADERS_READ_RETURNED");
        }finally{peer.interrupt();peer.join(1000);client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
    }
}
