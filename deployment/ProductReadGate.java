import com.pricetrack.exchange.ExchangeApplication;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Test-only read boundary around the real ExchangeApplication, not a replacement application. */
public final class ProductReadGate {
    static boolean allowed(String method,String path){
        if(method.equals("POST"))return Set.of("/api/auth/signup","/api/auth/login","/api/c-futures/wallet/challenges","/api/c-futures/wallet/link").contains(path);
        return method.equals("GET") && (!path.startsWith("/api/") || Set.of("/api/me","/api/markets/mSEC","/api/markets/mSEC/candles","/api/c-futures/config","/api/c-futures/wallet","/api/c-futures/account").contains(path)
            || path.matches("/api/c-futures/positions/0x[0-9a-f]{64}"));
    }
    @Configuration(proxyBeanMethods=false) public static class ReadBoundary {
        @Bean FilterRegistrationBean<OncePerRequestFilter> productReadOnlyFilter(){
            var b=new FilterRegistrationBean<OncePerRequestFilter>();b.setOrder(Ordered.HIGHEST_PRECEDENCE);
            b.setFilter(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws java.io.IOException,ServletException{
                if(!allowed(r.getMethod(),r.getRequestURI())){s.setStatus(403);s.setContentType("application/json");s.getWriter().write("{\"code\":\"READ_GATE_DENIED\"}");return;}c.doFilter(r,s);
            }});return b;
        }
    }
    public static void main(String[] args)throws Exception{
        if(Arrays.equals(args,new String[]{"--self-test"})){
            if(!allowed("GET","/api/c-futures/positions/0x"+"1".repeat(64))||allowed("POST","/api/orders/buy")||allowed("POST","/api/c-futures/direct/intents"))throw new AssertionError();
            System.out.println("PRODUCT_READ_BOUNDARY_SELF_TEST_PASS");return;
        }
        if(!Arrays.equals(args,new String[]{"/gate-config/application.properties","/gate-control","/gate-evidence"}))throw new IllegalArgumentException("FIXED_PATHS");
        var p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
        if(!"8080".equals(p.getProperty("server.port"))||!"0.0.0.0".equals(p.getProperty("server.address")))throw new IllegalStateException("HTTP_BIND_CONFIG");
        for(String f:FullAppReadGate.OFF)if(!"false".equals(p.getProperty(f)))throw new IllegalStateException("OFF_FLAGS");
        if(!"false".equals(p.getProperty("app.c-futures.direct.enabled"))||!"true".equals(p.getProperty("app.c-futures.enabled"))
            ||!"never".equals(p.getProperty("spring.sql.init.mode"))||!"verify-existing".equals(p.getProperty("app.schema.startup-mode")))throw new IllegalStateException("READ_CONFIG");
        for(String k:p.stringPropertyNames())if(k.toLowerCase().contains("private-key")||k.toLowerCase().contains("mnemonic"))throw new IllegalStateException("KEYLESS");
        var app=new SpringApplication(ExchangeApplication.class,ReadBoundary.class);app.setLogStartupInfo(false);
        try(var ctx=app.run("--spring.config.location=classpath:/application.yml,file:"+args[0])){
            for(String k:List.of("app.blockchain.operator-private-key","app.blockchain.price-report.signer-private-key","app.futures.executor-private-key","app.futures.signer-private-key","READ_TEST_KEY_A","READ_TEST_KEY_B"))
                if(!ctx.getEnvironment().getProperty(k,"").isBlank())throw new IllegalStateException("ACTUAL_KEYLESS");
            if(ctx.getBeansOfType(com.pricetrack.exchange.custody.api.CCustodyController.class).size()!=1||!ctx.getBeansOfType(com.pricetrack.exchange.custody.direct.CDirectService.class).isEmpty())throw new IllegalStateException("ACTUAL_BEANS");
            if(!(ctx instanceof org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext web)||web.getWebServer().getPort()!=8080
                ||!"0.0.0.0".equals(ctx.getEnvironment().getProperty("server.address")))throw new IllegalStateException("ACTUAL_HTTP_BIND");
            try(var socket=new java.net.Socket()){socket.connect(new java.net.InetSocketAddress("127.0.0.1",8080),3000);}
            Files.writeString(Path.of(args[2],"ready.json"),"{\"actualExchangeApplication\":true,\"directTrading\":false,\"port\":8080,\"bind\":\"0.0.0.0\",\"localTcpAccepted\":true}",StandardOpenOption.CREATE_NEW);
            long end=System.nanoTime()+Duration.ofMinutes(6).toNanos();
            while(System.nanoTime()<end&&!Files.exists(Path.of(args[1],"STOP")))Thread.sleep(200);
        }
        Files.writeString(Path.of(args[2],"normal-stop.json"),"{\"normalStop\":true}",StandardOpenOption.CREATE_NEW);
    }
}
