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
 static Map<String,Object> failureMetadata(Throwable failure,int status,boolean bearerHeaderPresent){
  var types=new ArrayList<String>();boolean reportTime=false;
  for(int n=0;failure!=null&&n<8;n++,failure=failure.getCause()){
   types.add(failure.getClass().getName());
   reportTime|=failure instanceof com.pricetrack.exchange.custody.proof.CRulesTransitionVerifier.Rejected&&"C_RULES_REPORT_TIME".equals(failure.getMessage());
  }
  return Map.of("statusAtFilter",status,"bearerHeaderPresent",bearerHeaderPresent,"causeTypes",types,"rulesReportTimeRejected",reportTime);
 }
 static void recordFailure(HttpServletRequest r,HttpServletResponse s,Throwable error){
  if(!r.getRequestURI().matches("/api/c-futures/direct/intents/0x[0-9a-f]{64}/execution"))return;
  try{Files.write(Path.of("/gate-evidence/execution-failure.json"),ProductReadDriver.J.writeValueAsBytes(failureMetadata(error,s.getStatus(),r.getHeader("Authorization")!=null)),StandardOpenOption.CREATE_NEW);}catch(Exception ignored){}
 }
 static boolean allowed(String method,String path){return ProductReadGate.allowed(method,path)
  ||method.equals("POST")&&(path.equals("/api/c-futures/direct/intents")||path.matches("/api/c-futures/direct/intents/0x[0-9a-f]{64}/execution"))
  ||method.equals("GET")&&(path.matches("/api/c-futures/direct/intents/0x[0-9a-f]{64}(?:/execution)?")||path.matches("/api/c-futures/direct/transactions/0x[0-9a-f]{64}"));}
 @Configuration(proxyBeanMethods=false)public static class Boundary{
  @Bean FilterRegistrationBean<OncePerRequestFilter> directBoundary(){var b=new FilterRegistrationBean<OncePerRequestFilter>();b.setOrder(Ordered.HIGHEST_PRECEDENCE);b.setFilter(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws java.io.IOException,ServletException{if(!allowed(r.getMethod(),r.getRequestURI())){s.setStatus(403);return;}try{c.doFilter(r,s);if(s.getStatus()>=400)recordFailure(r,s,null);}catch(java.io.IOException|ServletException|RuntimeException|Error e){recordFailure(r,s,e);throw e;}}});return b;}
 }
 public static void main(String[] args)throws Exception{
  if(Arrays.equals(args,new String[]{"--self-test"})){if(allowed("POST","/api/orders/buy")||!allowed("POST","/api/c-futures/direct/intents"))throw new AssertionError();
   var safe=failureMetadata(new ServletException("DO_NOT_EXPORT",new com.pricetrack.exchange.custody.proof.CRulesTransitionVerifier.Rejected("REPORT_TIME")),200,true);
   if(!Boolean.TRUE.equals(safe.get("rulesReportTimeRejected"))||safe.toString().contains("DO_NOT_EXPORT"))throw new AssertionError();return;}
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
