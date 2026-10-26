import com.pricetrack.exchange.ExchangeApplication;
import com.pricetrack.exchange.custody.direct.CDirectService;
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

/** Test-only surface restriction around the unchanged product application and product JWT. */
public final class ProductDirectGate {
 static boolean allowed(String method,String path){return ProductReadGate.allowed(method,path)
  ||method.equals("POST")&&(path.equals("/api/c-futures/direct/intents")||path.matches("/api/c-futures/direct/intents/0x[0-9a-f]{64}/execution"))
  ||method.equals("GET")&&(path.matches("/api/c-futures/direct/intents/0x[0-9a-f]{64}(?:/execution)?")||path.matches("/api/c-futures/direct/transactions/0x[0-9a-f]{64}"));}
 @Configuration(proxyBeanMethods=false)public static class Boundary{
  @Bean FilterRegistrationBean<OncePerRequestFilter> directBoundary(){var b=new FilterRegistrationBean<OncePerRequestFilter>();b.setOrder(Ordered.HIGHEST_PRECEDENCE);b.setFilter(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws java.io.IOException,ServletException{if(!allowed(r.getMethod(),r.getRequestURI())){s.setStatus(403);return;}c.doFilter(r,s);}});return b;}
 }
 public static void main(String[] args)throws Exception{
  if(Arrays.equals(args,new String[]{"--self-test"})){if(allowed("POST","/api/orders/buy")||!allowed("POST","/api/c-futures/direct/intents"))throw new AssertionError();return;}
  if(!Arrays.equals(args,new String[]{"/gate-config/application.properties","/gate-control","/gate-evidence"}))throw new IllegalArgumentException();
  var p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}for(String flag:FullAppReadGate.OFF)if(!"false".equals(p.getProperty(flag)))throw new IllegalStateException("OFF_FLAGS");
  if(!"true".equals(p.getProperty("app.c-futures.direct.enabled"))||!"true".equals(p.getProperty("app.c-futures.enabled")))throw new IllegalStateException("DIRECT_FLAGS");
  for(String k:p.stringPropertyNames())if(k.contains("private-key")||k.contains("mnemonic"))throw new IllegalStateException("KEYLESS");
  var app=new SpringApplication(ExchangeApplication.class,Boundary.class);app.setLogStartupInfo(false);
  try(var ctx=app.run("--spring.config.location=classpath:/application.yml,file:"+args[0])){
   for(String k:List.of("app.blockchain.operator-private-key","app.blockchain.price-report.signer-private-key","app.futures.executor-private-key","app.futures.signer-private-key","READ_TEST_KEY_A","READ_TEST_KEY_B"))if(!ctx.getEnvironment().getProperty(k,"").isBlank())throw new IllegalStateException("KEYLESS_ENV");
   if(ctx.getBeansOfType(CDirectService.class).size()!=1)throw new IllegalStateException("DIRECT_BEAN");
   Files.writeString(Path.of(args[2],"ready.json"),"{\"actualExchangeApplication\":true,\"directTrading\":true}",StandardOpenOption.CREATE_NEW);
   long end=System.nanoTime()+Duration.ofMinutes(6).toNanos();while(System.nanoTime()<end&&!Files.exists(Path.of(args[1],"STOP")))Thread.sleep(200);
  }Files.writeString(Path.of(args[2],"normal-stop.json"),"{\"normalStop\":true}",StandardOpenOption.CREATE_NEW);
 }
}
