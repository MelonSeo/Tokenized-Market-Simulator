import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ExchangeApplication;
import com.pricetrack.exchange.custody.direct.CDirectOraclePort;
import com.pricetrack.exchange.custody.runtime.CCustodyFailure;
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

/** Owned test wrapper around the actual full application; never a selected/demo application. */
public final class FullAppReadGate {
    public static final Set<String> OFF=Set.of("app.ai.enabled","app.ai.agent.enabled","app.ai.tools.enabled",
        "app.ai.skills.enabled","app.ai.diagnosis.enabled","app.blockchain.enabled","app.blockchain.price-report.enabled",
        "app.futures.enabled","app.futures.runtime-enabled","app.conditional.enabled","app.reserve.enabled",
        "app.trade-audit.enabled","app.google.enabled","app.c-demo.enabled");
    static boolean allowed(String method,String path) {
        if(method.equals("POST"))return Set.of("/api/auth/signup","/api/auth/login",
            "/api/c-futures/wallet/challenges","/api/c-futures/wallet/link").contains(path);
        return method.equals("GET")&&Set.of("/api/me","/api/markets/mSEC","/api/markets/mSEC/candles",
            "/api/c-futures/config","/api/c-futures/wallet","/api/c-futures/account",
            "/api/c-futures/direct/market","/api/admin/faucet","/ws").contains(path);
    }
    static void check(boolean ok,String code){if(!ok)throw new IllegalArgumentException("FULL_READ_"+code);}
    static String timingLine(String stage,long elapsedMillis) {
        check(Set.of("JVM_ENTER","SPRING_RUN_START","SPRING_RUN_DONE","POSTCHECK_DONE","READY","STOP","WATCHDOG","CTX_CLOSED").contains(stage)&&elapsedMillis>=0,"TIMING_STAGE");
        return "{\"stage\":\""+stage+"\",\"elapsedMillis\":"+elapsedMillis+"}\n";
    }
    static void timing(Path evidence,String stage,long began)throws java.io.IOException {
        Files.writeString(evidence.resolve("startup-timing.jsonl"),timingLine(stage,(System.nanoTime()-began)/1_000_000),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    static void verify(Properties p) {
        for(String flag:OFF)check("false".equals(p.getProperty(flag)),"OFF_FLAGS");
        for(String flag:Set.of("app.c-futures.enabled","app.c-futures.direct.enabled"))check("true".equals(p.getProperty(flag)),"C_OPT_IN");
        check("local-read-gate".equals(p.getProperty("spring.profiles.active"))&&"verify-existing".equals(p.getProperty("app.schema.startup-mode")),"LOCAL_MODE");
        check("never".equals(p.getProperty("spring.sql.init.mode"))&&"none".equals(p.getProperty("spring.jpa.hibernate.ddl-auto")),"NO_RUNTIME_DDL");
        check("simulated".equals(p.getProperty("app.price.provider"))&&"0".equals(p.getProperty("app.price.simulation.bootstrap-days")),"SYNTHETIC");
        check("3000".equals(p.getProperty("app.price.update-interval-ms"))&&"".equals(p.getProperty("app.admin.password")),"CADENCE_ADMIN");
        check("org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration".equals(p.getProperty("spring.autoconfigure.exclude")),"SECURITY_AUTO_CONFIG");
        for(String name:p.stringPropertyNames())check(!name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]","").matches(".*(privatekey|mnemonic|seedphrase).*"),"NO_WALLET_KEYS");
    }
    @Configuration(proxyBeanMethods=false)
    public static class ExtraDenials {
        @Bean @Primary CDirectOraclePort noSigning(){return (identity,candidate)->{throw new CCustodyFailure("READ_GATE_ORACLE_DENIED",403);};}
        @Bean FilterRegistrationBean<OncePerRequestFilter> readGateFilter(){
            var bean=new FilterRegistrationBean<OncePerRequestFilter>();
            bean.setFilter(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws java.io.IOException,ServletException{
                    if(!allowed(request.getMethod(),request.getRequestURI())){
                        response.setStatus(403);response.setContentType("application/json");response.getWriter().write("{\"code\":\"READ_GATE_DENIED\"}");return;
                    }
                    chain.doFilter(request,response); // Authentication/roles remain in the unmodified product security chain.
                }
            });bean.setOrder(Ordered.HIGHEST_PRECEDENCE);return bean;
        }
    }
    public static void main(String[] args)throws Exception {
        long began=System.nanoTime();
        if(args.length==1&&args[0].equals("--self-test")){
            var sample=new ObjectMapper().readTree(timingLine("SPRING_RUN_START",123));
            check(sample.size()==2&&sample.path("stage").asText().equals("SPRING_RUN_START")&&sample.path("elapsedMillis").asLong()==123,"TIMING_FIELDS_ONLY");
            boolean badStage=false,badElapsed=false;
            try{timingLine("password=forbidden",1);}catch(IllegalArgumentException expected){badStage=true;}
            try{timingLine("READY",-1);}catch(IllegalArgumentException expected){badElapsed=true;}
            check(badStage&&badElapsed,"TIMING_UNSAFE_REJECTED");
            var settings=new Properties();for(String flag:OFF)settings.setProperty(flag,"false");
            for(String flag:List.of("app.c-futures.enabled","app.c-futures.direct.enabled"))settings.setProperty(flag,"true");
            settings.setProperty("spring.profiles.active","local-read-gate");settings.setProperty("app.schema.startup-mode","verify-existing");
            settings.setProperty("spring.sql.init.mode","never");settings.setProperty("spring.jpa.hibernate.ddl-auto","none");
            settings.setProperty("app.price.provider","simulated");settings.setProperty("app.price.simulation.bootstrap-days","0");
            settings.setProperty("app.price.update-interval-ms","3000");settings.setProperty("app.admin.password","");
            settings.setProperty("spring.autoconfigure.exclude","org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration");verify(settings);
            for(var mutation:Map.of("spring.sql.init.mode","always","app.schema.startup-mode","unknown","app.blockchain.enabled","true","app.admin.password","nonblank").entrySet()){
                var bad=new Properties();bad.putAll(settings);bad.setProperty(mutation.getKey(),mutation.getValue());boolean rejected=false;
                try{verify(bad);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"UNSAFE_CONFIG_REJECTED");
            }
            check(allowed("GET","/api/c-futures/config")&&allowed("GET","/api/admin/faucet")&&allowed("GET","/ws"),"COMMON_SECURITY_PATHS");
            check(allowed("POST","/api/auth/signup")&&allowed("POST","/api/c-futures/wallet/link"),"PROOF_PATHS");
            for(String path:List.of("/api/wallet/faucet","/api/orders/buy","/api/c-futures/direct/intents"))
                check(!allowed("POST",path),"WRITE_DENIAL");
            for(var method:ExtraDenials.class.getDeclaredMethods())if(!method.isSynthetic())for(var parameter:method.getParameters())check(parameter.isNamePresent(),"PARAMETERS");
            System.out.println("FULL_READ_WRAPPER_NO_SOCKET_SELF_TEST_PASS");return;
        }
        check(Arrays.equals(args,new String[]{"/gate-config/application.properties","/gate-control","/gate-evidence"}),"FIXED_PATHS");
        Path evidence=Path.of(args[2]);timing(evidence,"JVM_ENTER",began);
        var p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}verify(p);
        // Both immutable product YAML defaults (including security exclusion) and explicit gate overrides are loaded.
        var app=new SpringApplication(ExchangeApplication.class,ExtraDenials.class);
        app.setLogStartupInfo(false);
        timing(evidence,"SPRING_RUN_START",began);
        var ctx=app.run("--spring.config.location=classpath:/application.yml,file:"+args[0]);
        try {
            timing(evidence,"SPRING_RUN_DONE",began);
            check(ctx.getBeansOfType(ExchangeApplication.class).size()==1,"ACTUAL_APPLICATION_BEAN");
            for(String flag:OFF)check("false".equals(ctx.getEnvironment().getProperty(flag)),"ACTUAL_OFF_FLAGS");
            check(ctx.getBean(CDirectOraclePort.class)==ctx.getBean("noSigning"),"DENY_ORACLE_PRIMARY");
            for(String key:List.of("app.blockchain.operator-private-key","app.blockchain.price-report.signer-private-key","app.futures.executor-private-key","app.futures.signer-private-key"))
                check(ctx.getEnvironment().getProperty(key,"").isBlank(),"NO_ACTUAL_WALLET_KEYS");
            try(var connection=ctx.getBean(javax.sql.DataSource.class).getConnection();var statement=connection.createStatement();
                var r=statement.executeQuery("SELECT current_user,NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole,(SELECT count(*) FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user")){
                check(r.next()&&r.getString(1).equals(p.getProperty("spring.datasource.username"))&&r.getBoolean(2)&&r.getLong(3)==0,"ACTUAL_API_DATASOURCE");
            }
            for(String name:List.of("com.pricetrack.exchange.auth.AuthController","com.pricetrack.exchange.websocket.config.WebSocketConfig",
                "com.pricetrack.exchange.custody.api.CCustodyController","com.pricetrack.exchange.custody.direct.CDirectMarketController"))
                check(ctx.getBeansOfType(Class.forName(name,false,FullAppReadGate.class.getClassLoader())).size()==1,"ACTUAL_BEANS");
            timing(evidence,"POSTCHECK_DONE",began);
            var json=new ObjectMapper();Files.write(Path.of(args[2],"ready.json"),json.writeValueAsBytes(Map.of("actualExchangeApplication",true,"scope","FULL_APP_READ_GATE_NOT_UI_OR_TRADING")),StandardOpenOption.CREATE_NEW);
            timing(evidence,"READY",began);
            long end=System.nanoTime()+Duration.ofSeconds(180).toNanos();
            while(!Files.isRegularFile(Path.of(args[1],"STOP"))&&System.nanoTime()<end)Thread.sleep(100);
            timing(evidence,Files.isRegularFile(Path.of(args[1],"STOP"))?"STOP":"WATCHDOG",began);
        } finally {ctx.close();timing(evidence,"CTX_CLOSED",began);}
        Files.writeString(Path.of(args[2],"normal-stop.json"),"{\"normalStop\":true}",StandardOpenOption.CREATE_NEW);
    }
}
