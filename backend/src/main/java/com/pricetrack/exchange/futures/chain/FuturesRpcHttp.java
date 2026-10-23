package com.pricetrack.exchange.futures.chain;

import okhttp3.*;
import okhttp3.EventListener;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.LoggerFactory;

/** B-v2 transport observability only: same deadlines, proxy selection, pool and retry defaults. */
public final class FuturesRpcHttp {
    private FuturesRpcHttp(){}
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(FuturesRpcHttp.class);
    public static OkHttpClient client(){
        var client=new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).eventListenerFactory(new TraceFactory()).build();
        LOG.info("futures_http_settings callMs={} connectMs={} readMs={} writeMs={} connectionRetry={} proxyMode={} poolConnections={} poolIdle={} poolPolicy=DEFAULT",client.callTimeoutMillis(),client.connectTimeoutMillis(),client.readTimeoutMillis(),client.writeTimeoutMillis(),client.retryOnConnectionFailure(),client.proxy()==null?"SELECTOR":client.proxy().type(),client.connectionPool().connectionCount(),client.connectionPool().idleConnectionCount());
        return client;
    }
    public static final class TraceFactory implements EventListener.Factory {
        private final Map<Connection,Boolean> seen=Collections.synchronizedMap(new WeakHashMap<>());
        @Override public EventListener create(Call ignored){return new Trace(seen);}
    }
    private static final class Trace extends EventListener {
        final Map<Connection,Boolean> seen;final String id=UUID.randomUUID().toString();final long start=System.nanoTime();
        final FuturesDiagnostics.Context context=FuturesDiagnostics.context();long phaseStart=start;
        String phase="CALL_CREATED",proxyType="UNOBSERVED";boolean reused;
        Trace(Map<Connection,Boolean> seen){this.seen=seen;}
        private void phase(String value){phase=value;phaseStart=System.nanoTime();}
        public void connectStart(Call c,InetSocketAddress address,Proxy proxy){phase("CONNECT_START");proxyType=proxy.type().name();}
        public void connectEnd(Call c,InetSocketAddress address,Proxy proxy,Protocol protocol){phase("CONNECTED");}
        public void connectionAcquired(Call c,Connection connection){reused=seen.put(connection,Boolean.TRUE)!=null;phase("CONNECTION_ACQUIRED");if(connection.route()!=null)proxyType=connection.route().proxy().type().name();}
        public void requestHeadersStart(Call c){phase("REQUEST_HEADERS");}
        // Body completion does not prove peer receipt, request flush, or response arrival.
        public void requestBodyEnd(Call c,long bytes){phase("REQUEST_BODY_COMPLETED");}
        // OkHttp 4.12 invokes this AFTER readResponseHeaders returns, not before its blocking read.
        public void responseHeadersStart(Call c){phase("RESPONSE_HEADERS_READ_RETURNED");}
        public void responseHeadersEnd(Call c,Response r){phase("RESPONSE_HEADERS_COMPLETED");}
        public void responseBodyEnd(Call c,long bytes){phase("RESPONSE_BODY_COMPLETED");}
        public void connectFailed(Call c,InetSocketAddress address,Proxy proxy,Protocol protocol,IOException x){record("CONNECT_FAILED","FAIL",category(x));}
        public void requestFailed(Call c,IOException x){record("REQUEST_FAILED","FAIL",category(x));}
        public void responseFailed(Call c,IOException x){record("RESPONSE_FAILED","FAIL",category(x));}
        public void callEnd(Call c){record("CALL_END","OK","NONE");}
        public void callFailed(Call c,IOException x){record("CALL_FAILED","FAIL",category(x));}
        private static String category(IOException x){return x instanceof java.io.InterruptedIOException?"TRANSPORT_TIMEOUT":x instanceof ConnectException?"CONNECT_FAILURE":x instanceof SocketException?"SOCKET_FAILURE":"TRANSPORT_IO";}
        private void record(String event,String outcome,String code){
            try{long now=System.nanoTime(),elapsed=Math.max(0,(now-start)/1_000_000),phaseElapsed=Math.max(0,(now-phaseStart)/1_000_000);
                if(outcome.equals("FAIL"))LOG.warn("futures_http call={} trace={} rpcStage={} event={} phase={} reused={} proxy={} outcome={} code={} elapsedMs={} phaseElapsedMs={}",id,context.trace(),context.stage(),event,phase,reused,proxyType,outcome,code,elapsed,phaseElapsed);
                else LOG.info("futures_http call={} trace={} rpcStage={} event={} phase={} reused={} proxy={} outcome={} code={} elapsedMs={} phaseElapsedMs={}",id,context.trace(),context.stage(),event,phase,reused,proxyType,outcome,code,elapsed,phaseElapsed);
            }catch(RuntimeException ignored){/* Observability must not alter financial transport results. */}
        }
    }
}
