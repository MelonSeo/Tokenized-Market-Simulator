import com.pricetrack.exchange.ExchangeApplication;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

/** Separate AI C-read profile. Existing ProductReadGate/OFF assertions are unchanged. */
public final class ProductAiCReadGate {
    static boolean allowed(String method,String path){return ProductReadGate.allowed(method,path)||method.equals("POST")&&Set.of("/api/ai/tools/getCAccount","/api/ai/tools/getCPosition","/api/ai/agent/answers").contains(path);}
    @Configuration(proxyBeanMethods=false) public static class Boundary {
        @Bean FilterRegistrationBean<OncePerRequestFilter> aiCReadBoundary(){var b=new FilterRegistrationBean<OncePerRequestFilter>();b.setOrder(Integer.MIN_VALUE);b.setFilter(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws java.io.IOException,ServletException{if(!allowed(r.getMethod(),r.getRequestURI())){s.setStatus(403);s.setContentType("application/json");s.getWriter().write("{\"code\":\"AI_C_READ_GATE_DENIED\"}");return;}c.doFilter(r,s);}});return b;}
    }
    public static void main(String[] args)throws Exception{
        if(Arrays.equals(args,new String[]{"--self-test"})){if(!allowed("POST","/api/ai/tools/getCAccount")||allowed("POST","/api/ai/index")||allowed("POST","/api/c-futures/direct/intents")||allowed("POST","/api/orders/buy"))throw new AssertionError();System.out.println("AI_C_READ_BOUNDARY_SELF_TEST_PASS");return;}
        if(!Arrays.equals(args,new String[]{"/gate-config/application.properties","/gate-control","/gate-evidence"}))throw new IllegalArgumentException("FIXED_PATHS");
        var p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
        if(!"8080".equals(p.getProperty("server.port"))||!"0.0.0.0".equals(p.getProperty("server.address")))throw new IllegalStateException("HTTP_BIND_CONFIG");
        for(String flag:FullAppReadGate.OFF){boolean on=Set.of("app.ai.enabled","app.ai.agent.enabled","app.ai.tools.enabled").contains(flag);if(!Boolean.toString(on).equals(p.getProperty(flag)))throw new IllegalStateException("AI_C_READ_FLAGS");}
        if(!"false".equals(p.getProperty("app.c-futures.direct.enabled"))||!"true".equals(p.getProperty("app.c-futures.enabled"))||!"verify-existing".equals(p.getProperty("app.schema.startup-mode"))||!"never".equals(p.getProperty("spring.sql.init.mode")))throw new IllegalStateException("AI_C_READ_CONFIG");
        for(String key:p.stringPropertyNames())if(key.toLowerCase().contains("private-key")||key.toLowerCase().contains("mnemonic"))throw new IllegalStateException("KEYLESS");
        var app=new SpringApplication(ExchangeApplication.class,Boundary.class);app.setLogStartupInfo(false);
        try(var ctx=app.run("--spring.config.location=classpath:/application.yml,file:"+args[0])){
            for(String k:List.of("app.blockchain.operator-private-key","app.blockchain.price-report.signer-private-key","app.futures.executor-private-key","app.futures.signer-private-key","READ_TEST_KEY_A","READ_TEST_KEY_B"))if(!ctx.getEnvironment().getProperty(k,"").isBlank())throw new IllegalStateException("ACTUAL_KEYLESS");
            if(!ctx.getEnvironment().getProperty("app.ai.api-key","").isBlank()||!ctx.getBeansOfType(com.pricetrack.exchange.custody.direct.CDirectService.class).isEmpty())throw new IllegalStateException("NO_EXTERNAL_PROVIDER_OR_TRADING");
            if(ctx.getBeansOfType(com.pricetrack.exchange.custody.api.CCustodyController.class).size()!=1||!(ctx instanceof org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext web)||web.getWebServer().getPort()!=8080)throw new IllegalStateException("ACTUAL_READ_APP");
            try(var socket=new java.net.Socket()){socket.connect(new java.net.InetSocketAddress("127.0.0.1",8080),3000);}
            Files.writeString(Path.of(args[2],"ready.json"),"{\"actualExchangeApplication\":true,\"directTrading\":false,\"aiCReadOnly\":true}",StandardOpenOption.CREATE_NEW);
            long end=System.nanoTime()+Duration.ofMinutes(6).toNanos();while(System.nanoTime()<end&&!Files.exists(Path.of(args[1],"STOP")))Thread.sleep(200);
        }
        Files.writeString(Path.of(args[2],"normal-stop.json"),"{\"normalStop\":true}",StandardOpenOption.CREATE_NEW);
    }
}
