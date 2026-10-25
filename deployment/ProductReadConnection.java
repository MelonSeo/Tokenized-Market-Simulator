import java.net.*;
import java.net.http.*;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.*;

/** Test-only fixed destination. No headers, bodies, tokens or exception messages in logs. */
public final class ProductReadConnection {
    static final String BASE="http://app:8080", ORIGIN="http://127.0.0.1:32171";
    static String classify(Throwable failure){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        String result="HTTP_IO_FAILURE";
        for(Throwable t=failure;t!=null&&seen.add(t);t=t.getCause()){
            if(t instanceof UnknownHostException||t instanceof UnresolvedAddressException)return "DNS_FAILURE";
            if(t instanceof HttpConnectTimeoutException)return "CONNECT_TIMEOUT";
            if(t instanceof HttpTimeoutException)result="RESPONSE_TIMEOUT";
            if(t instanceof ConnectException){
                String m=t.getMessage();
                result=m!=null&&m.toLowerCase(Locale.ROOT).contains("refused")?"CONNECTION_REFUSED":"CONNECT_FAILURE";
            }
        }
        return result;
    }
    static FullAppReadDriver.Reply http(HttpClient client,String method,String path,String token,Object body)throws Exception{
        if(!Set.of("GET","POST").contains(method)||!path.startsWith("/api/")||path.contains("#")||path.contains("\r")||path.contains("\n"))throw new IllegalArgumentException("HTTP_FIXED_TARGET");
        long start=System.nanoTime();
        try{
            var request=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(10)).header("Origin",ORIGIN).header("Content-Type","application/json");
            if(token!=null)request.header("Authorization","Bearer "+token);
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(ProductReadDriver.J.writeValueAsBytes(body)));
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var in=response.body()){
                byte[] bytes=in.readNBytes(65537);ProductReadDriver.check(bytes.length<=65536,"HTTP_BODY_LIMIT");
                System.out.println("PRODUCT_READ_HTTP_RESPONSE status="+response.statusCode()+" elapsedMs="+(System.nanoTime()-start)/1_000_000);
                return new FullAppReadDriver.Reply(response.statusCode(),ProductReadDriver.J.readTree(bytes));
            }
        }catch(java.io.IOException failure){
            String code=classify(failure);
            System.err.println("PRODUCT_READ_HTTP_FAILURE code="+code+" elapsedMs="+(System.nanoTime()-start)/1_000_000);
            throw new IllegalStateException("HTTP_"+code);
        }
    }
}
