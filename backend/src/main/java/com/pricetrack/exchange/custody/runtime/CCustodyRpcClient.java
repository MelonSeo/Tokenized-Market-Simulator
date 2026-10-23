package com.pricetrack.exchange.custody.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Dedicated keyless, bounded C read transport. No retry, transaction sender or generic HTTP endpoint. */
public final class CCustodyRpcClient implements CReadRpc, AutoCloseable {
    static final int MAX_BODY_BYTES=1_048_576, MAX_REQUEST_BYTES=16_384;
    static final Set<String> METHODS=Set.of("eth_chainId","eth_getBlockByNumber","eth_getCode","eth_call","eth_getTransactionByHash","eth_getTransactionReceipt");
    private static final Logger LOG=Logger.getLogger(CCustodyRpcClient.class.getName());
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static {JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24)
        .maxStringLength(MAX_BODY_BYTES).maxNumberLength(128).build());}
    private static final MediaType CONTENT_TYPE=MediaType.get("application/json; charset=utf-8");
    private final URI endpoint;
    private final Call.Factory calls;
    private final OkHttpClient ownedClient;
    private final AtomicLong ids=new AtomicLong();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<Call> active=ConcurrentHashMap.newKeySet();

    public CCustodyRpcClient(URI endpoint){this(validateEndpoint(endpoint),buildClient());}
    private CCustodyRpcClient(URI endpoint,OkHttpClient client){this(endpoint,client,client);}
    /** Package-private no-socket transport injection for tests, never wired by the production config. */
    CCustodyRpcClient(URI endpoint,Call.Factory calls){this(endpoint,calls,null);}
    private CCustodyRpcClient(URI endpoint,Call.Factory calls,OkHttpClient ownedClient){
        this.endpoint=validateEndpoint(endpoint);
        if(calls==null)throw new IllegalArgumentException("C_RPC_FACTORY");
        this.calls=calls;this.ownedClient=ownedClient;
    }
    static URI validateEndpoint(URI endpoint){
        if(endpoint==null||!Set.of("http","https").contains(endpoint.getScheme())||endpoint.getHost()==null
            ||endpoint.getHost().isBlank()||endpoint.getRawUserInfo()!=null||endpoint.getRawQuery()!=null
            ||endpoint.getRawFragment()!=null||endpoint.toASCIIString().length()>2048
            ||endpoint.getPort()==0||endpoint.getPort()>65535)throw new IllegalArgumentException("C_RPC_ENDPOINT");
        return endpoint;
    }
    static OkHttpClient buildClient(){
        return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).retryOnConnectionFailure(false)
            .connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).writeTimeout(3,TimeUnit.SECONDS)
            .callTimeout(3,TimeUnit.SECONDS).addNetworkInterceptor(CCustodyRpcClient::singleExchange)
            .eventListenerFactory(call->new EventListener(){
                @Override public void connectStart(Call c,InetSocketAddress ignored,Proxy proxy){
                    Attempt attempt=c.request().tag(Attempt.class);
                    if(attempt==null||attempt.connects.incrementAndGet()>1){
                        if(attempt!=null)attempt.failure.set("C_RPC_RETRY_BLOCKED");
                        c.cancel();
                    }
                }
                @Override public void requestHeadersStart(Call c){
                    Attempt attempt=c.request().tag(Attempt.class);
                    if(attempt==null||attempt.headers.incrementAndGet()>1){
                        if(attempt!=null)attempt.failure.set("C_RPC_RETRY_BLOCKED");
                        c.cancel();
                    }
                }
            }).build();
    }
    /** Reject non-200 before OkHttp's RetryAndFollowUpInterceptor can consider 503/Retry-After:0. */
    static Response singleExchange(Interceptor.Chain chain)throws IOException{
        Attempt attempt=chain.request().tag(Attempt.class);
        if(attempt==null||!attempt.exchanges.compareAndSet(0,1)){
            if(attempt!=null)attempt.failure.set("C_RPC_RETRY_BLOCKED");
            throw new IOException("C_RPC_RETRY_BLOCKED");
        }
        Response response=chain.proceed(chain.request());
        if(response.code()!=200){
            attempt.failure.set("C_RPC_HTTP_STATUS");response.close();throw new IOException("C_RPC_HTTP_STATUS");
        }
        return response;
    }
    static final class Attempt {
        final AtomicInteger exchanges=new AtomicInteger(),connects=new AtomicInteger(),headers=new AtomicInteger();
        final AtomicReference<String> failure=new AtomicReference<>("C_RPC_TRANSPORT");
    }

    @Override public JsonNode request(String method,List<Object> params,long timeoutMillis){
        validateRequest(method,params);
        if(timeoutMillis<1||timeoutMillis>3000)throw new CCustodyFailure("C_RPC_DEADLINE_INVALID",400);
        if(closed.get())throw new CCustodyFailure("C_RPC_CLOSED",503);
        long start=System.nanoTime(),deadline=start+TimeUnit.MILLISECONDS.toNanos(timeoutMillis),id=ids.incrementAndGet();
        if(id<=0)throw new CCustodyFailure("C_RPC_REQUEST_ID_EXHAUSTED",503);
        String correlation=UUID.randomUUID().toString();
        Attempt attempt=new Attempt();CompletableFuture<JsonNode> result=new CompletableFuture<>();Call call=null;
        String outcome="C_RPC_OK";
        try{
            byte[] body=JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params));
            if(body.length>MAX_REQUEST_BYTES)throw new CCustodyFailure("C_RPC_REQUEST_TOO_LARGE",400);
            Request request=new Request.Builder().url(endpoint.toASCIIString()).tag(Attempt.class,attempt)
                .post(RequestBody.create(body,CONTENT_TYPE)).header("Accept","application/json").build();
            if(System.nanoTime()>=deadline)throw new TimeoutException();
            call=calls.newCall(request);call.timeout().timeout(timeoutMillis,TimeUnit.MILLISECONDS);active.add(call);
            if(closed.get()){call.cancel();throw new CCustodyFailure("C_RPC_CLOSED",503);}
            final Call pending=call;
            call.enqueue(new Callback(){
                @Override public void onFailure(Call ignored,IOException privateError){
                    result.completeExceptionally(new CCustodyFailure(attempt.failure.get(),503));
                }
                @Override public void onResponse(Call ignored,Response response){
                    // Also closes late responses after the caller timed out/cancelled; no second request is made.
                    try(response){
                        if(result.isDone()||pending.isCanceled())return;
                        if(response.code()!=200)throw new CCustodyFailure("C_RPC_HTTP_STATUS",503);
                        byte[] bytes=readBody(response.body(),deadline);
                        JsonNode node=parse(bytes,id);
                        if(System.nanoTime()>=deadline)throw new CCustodyFailure("C_RPC_TIMEOUT",503);
                        result.complete(node);
                    }catch(CCustodyFailure safe){result.completeExceptionally(safe);}
                    catch(Exception privateError){result.completeExceptionally(new CCustodyFailure("C_RPC_RESPONSE_INVALID",503));}
                }
            });
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new TimeoutException();
            return result.get(remaining,TimeUnit.NANOSECONDS);
        }catch(CCustodyFailure safe){outcome=safe.code();throw safe;}
        catch(TimeoutException expired){outcome="C_RPC_TIMEOUT";throw new CCustodyFailure(outcome,503);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();outcome="C_RPC_INTERRUPTED";throw new CCustodyFailure(outcome,503);}
        catch(ExecutionException failed){
            if(failed.getCause() instanceof CCustodyFailure safe){outcome=safe.code();throw safe;}
            outcome="C_RPC_TRANSPORT";throw new CCustodyFailure(outcome,503);
        }catch(Exception privateError){outcome="C_RPC_TRANSPORT";throw new CCustodyFailure(outcome,503);}
        finally{
            result.cancel(false);
            if(call!=null){call.cancel();active.remove(call);}
            String record="C_READ_RPC correlation="+correlation+" method="+method+" stage=HTTP_BODY_JSON elapsedMs="
                +TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+" code="+outcome;
            if(outcome.equals("C_RPC_OK"))LOG.fine(record);else LOG.warning(record);
        }
    }
    static byte[] readBody(ResponseBody body,long deadline)throws IOException{
        if(body==null)throw new CCustodyFailure("C_RPC_RESPONSE_INVALID",503);
        if(body.contentLength()>MAX_BODY_BYTES||body.contentLength()<-1)throw new CCustodyFailure("C_RPC_RESPONSE_TOO_LARGE",503);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=body.byteStream()){
            byte[] chunk=new byte[8192];int n;
            while((n=in.read(chunk))!=-1){
                if(System.nanoTime()>=deadline)throw new CCustodyFailure("C_RPC_TIMEOUT",503);
                if(n>MAX_BODY_BYTES-bytes.size())throw new CCustodyFailure("C_RPC_RESPONSE_TOO_LARGE",503);
                bytes.write(chunk,0,n);
            }
        }
        return bytes.toByteArray();
    }
    static JsonNode parse(byte[] bytes,long id){
        if(bytes==null||bytes.length==0||bytes.length>MAX_BODY_BYTES)throw new CCustodyFailure("C_RPC_RESPONSE_INVALID",503);
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode value=JSON.readTree(text);
            if(value==null||!value.isObject()||value.size()!=3||!value.path("jsonrpc").isTextual()
                ||!value.path("jsonrpc").asText().equals("2.0")||!value.path("id").isIntegralNumber()
                ||!value.path("id").bigIntegerValue().equals(BigInteger.valueOf(id))||value.has("result")==value.has("error"))
                throw new CCustodyFailure("C_RPC_RESPONSE_INVALID",503);
            if(value.has("error"))throw new CCustodyFailure("C_RPC_REMOTE_ERROR",503);
            return value.get("result");
        }catch(CCustodyFailure safe){throw safe;}
        catch(Exception privateError){throw new CCustodyFailure("C_RPC_RESPONSE_INVALID",503);}
    }
    static void validateRequest(String method,List<Object> params){
        if(method==null||!METHODS.contains(method))throw new CCustodyFailure("C_RPC_METHOD_NOT_ALLOWED",400);
        if(params==null)throw new CCustodyFailure("C_RPC_PARAMS_INVALID",400);
        boolean valid=switch(method){
            case "eth_chainId"->params.isEmpty();
            case "eth_getBlockByNumber"->params.size()==2&&params.get(0) instanceof String block
                &&(block.equals("latest")||block.matches("0x(?:0|[1-9a-fA-F][0-9a-fA-F]{0,63})"))&&Boolean.FALSE.equals(params.get(1));
            case "eth_getCode"->params.size()==2&&address(params.get(0))&&pinned(params.get(1));
            case "eth_call"->params.size()==2&&callParams(params.get(0))&&pinned(params.get(1));
            case "eth_getTransactionByHash","eth_getTransactionReceipt"->params.size()==1
                &&params.get(0) instanceof String txHash&&txHash.matches("0x[0-9a-fA-F]{64}");
            default->false;
        };
        if(!valid)throw new CCustodyFailure("C_RPC_PARAMS_INVALID",400);
    }
    private static boolean address(Object value){return value instanceof String s&&s.matches("0x[0-9a-fA-F]{40}");}
    private static boolean pinned(Object value){return value instanceof Map<?,?> map&&map.size()==2
        &&map.get("blockHash") instanceof String hash&&hash.matches("0x[0-9a-fA-F]{64}")&&Boolean.TRUE.equals(map.get("requireCanonical"));}
    private static boolean callParams(Object value){return value instanceof Map<?,?> map&&map.size()==2&&address(map.get("to"))
        &&map.get("data") instanceof String data&&data.length()<=MAX_REQUEST_BYTES&&data.matches("0x(?:[0-9a-fA-F]{2}){4,}");}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        active.forEach(Call::cancel);
        if(ownedClient!=null){ownedClient.dispatcher().cancelAll();ownedClient.connectionPool().evictAll();ownedClient.dispatcher().executorService().shutdown();}
    }
}
