import java.net.*;
import java.net.http.*;
import java.nio.channels.UnresolvedAddressException;

/** Pure classification/configuration checks: no listeners, DNS, HTTP or Docker. */
public final class ProductReadConnectionTest {
    static int count;
    static void expect(String expected,Throwable t){
        if(!expected.equals(ProductReadConnection.classify(t)))throw new AssertionError(expected);
        count++;
    }
    public static void main(String[] args){
        expect("DNS_FAILURE",new UnknownHostException("not logged"));
        expect("DNS_FAILURE",new ConnectException("outer").initCause(new UnresolvedAddressException()));
        expect("DNS_FAILURE",new RuntimeException(new UnknownHostException("secret: not logged")));
        expect("CONNECTION_REFUSED",new ConnectException("Connection refused"));
        expect("CONNECT_FAILURE",new ConnectException());
        expect("CONNECT_TIMEOUT",new HttpConnectTimeoutException("not logged"));
        expect("RESPONSE_TIMEOUT",new HttpTimeoutException("not logged"));
        expect("HTTP_IO_FAILURE",new java.io.IOException("secret: not logged"));
        var target=URI.create(ProductReadConnection.BASE);
        if(!target.getHost().equals("app")||target.getPort()!=8080||!ProductReadConnection.ORIGIN.equals("http://127.0.0.1:32171"))throw new AssertionError();
        System.out.println("PRODUCT_READ_CONNECTION_PURE_PASS cases="+(count+1));
    }
}
