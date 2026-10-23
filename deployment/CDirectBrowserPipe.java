import java.io.*;
import java.net.*;
import java.util.*;

/** Opaque bounded stdio transport only. Execute inside the owned keyless App container. */
public final class CDirectBrowserPipe {
    static InetSocketAddress destination(String role) {
        return switch(role) {
            case "APP" -> new InetSocketAddress("127.0.0.1",8080);
            case "RPC" -> InetSocketAddress.createUnresolved("anvil",8545);
            default -> throw new IllegalArgumentException("C_BROWSER_PIPE_ROLE");
        };
    }
    static int lifetime(String value) {
        int seconds=Integer.parseInt(value);
        if(seconds<1||seconds>120)throw new IllegalArgumentException("C_BROWSER_PIPE_LIFETIME");
        return seconds;
    }
    public static void main(String[] args) {
        try {
            if(args.length==1&&args[0].equals("--self-test")) {
                for(var method:CDirectBrowserPipe.class.getDeclaredMethods())if(!method.isSynthetic())for(var parameter:method.getParameters())if(!parameter.isNamePresent())throw new AssertionError("C_BROWSER_PARAMETER_METADATA");
                if(destination("APP").getPort()!=8080||destination("RPC").getPort()!=8545)throw new AssertionError();
                for(String bad:List.of("PG","http://outside","anvil:8545","")) {
                    try{destination(bad);throw new AssertionError();}catch(IllegalArgumentException expected){}
                }
                for(String bad:List.of("0","121","-1")) {
                    try{lifetime(bad);throw new AssertionError();}catch(IllegalArgumentException expected){}
                }
                System.out.println("C_BROWSER_PIPE_NO_SOCKET_SELF_TEST_PASS");return;
            }
            if(args.length!=2)throw new IllegalArgumentException("C_BROWSER_PIPE_ARGUMENTS");
            InetSocketAddress target=destination(args[0]);int seconds=lifetime(args[1]);
            // Name resolution only for the fixed internal alias, never a caller-provided hostname.
            if(target.isUnresolved())target=new InetSocketAddress(target.getHostString(),target.getPort());
            try(Socket socket=new Socket()) {
                socket.connect(target,3000);socket.setSoTimeout(Math.min(30000,seconds*1000));
                Timer deadline=new Timer(true);
                deadline.schedule(new TimerTask(){public void run(){try{socket.close();}catch(IOException ignored){}}},seconds*1000L);
                Thread upload=new Thread(()->{
                    try{System.in.transferTo(socket.getOutputStream());socket.shutdownOutput();}
                    catch(IOException ignored){try{socket.close();}catch(IOException alsoIgnored){}}
                },"c-browser-opaque-input");upload.setDaemon(true);upload.start();
                try{socket.getInputStream().transferTo(System.out);System.out.flush();}finally{deadline.cancel();}
            }
        } catch(Exception safe) {System.err.println("C_BROWSER_PIPE_STOP");System.exit(1);}
    }
}
