import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.common.config.SecurityConfig;
import com.pricetrack.exchange.common.exception.*;
import com.pricetrack.exchange.custody.api.CCustodyController;
import com.pricetrack.exchange.custody.direct.*;
import com.pricetrack.exchange.custody.runtime.CCustodyRuntimeConfig;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.provider.simulated.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;
import com.pricetrack.exchange.websocket.publisher.WebSocketEventPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.*;
import org.springframework.web.bind.annotation.*;

/** New local-only C demo entry point. Existing TMS security/financial startup is not changed. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ConditionalOnProperty(name="app.c-demo.enabled",havingValue="true")
@EnableScheduling
@EnableJpaRepositories(basePackageClasses={UserRepository.class,MarketCandleRepository.class},
    excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern=".*\\.PriceTickRepository"))
@Import({CCustodyRuntimeConfig.class,CCustodyController.class,CDirectController.class,CDirectRuntimeConfig.class,CDirectOracleConfig.class,
    CDirectReceiptController.class,JwtTokenProvider.class,JwtAuthenticationFilter.class,SecurityConfig.class,
    SimulatedPriceProvider.class,SimulatedCandleProvider.class,MarketPriceService.class,
    MarketWebSocketPublisher.class,WebSocketEventPublisher.class,CDirectDemoApplication.DemoController.class,
    CDirectDemoApplication.DemoAdvice.class})
public class CDirectDemoApplication {
    public static final Set<String> MANAGED_TYPES=Set.of(User.class.getName(),MarketCandleEntity.class.getName(),SyntheticMarketState.class.getName());

    /** Avoid scanning unrelated price-tick/trade entities into this owned demo database. */
    @Bean PersistenceManagedTypes cDemoManagedTypes(){return PersistenceManagedTypes.of(MANAGED_TYPES.toArray(String[]::new));}

    /** Only static GETs bypass login. Every /api request keeps the unmodified product security chain. */
    @Bean @Order(0) SecurityFilterChain cDemoStaticResources(HttpSecurity http)throws Exception {
        var matcher=new OrRequestMatcher(new AntPathRequestMatcher("/c-demo.html","GET"),
            new AntPathRequestMatcher("/assets/**","GET"),new AntPathRequestMatcher("/c-demo/**","GET"));
        return http.securityMatcher(matcher).csrf(c->c.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
    }

    @Bean ApplicationRunner cDemoOwnedDatabase(DataSource ds,UserRepository users,
            @Value("${app.c-demo.run-id}")String run,@Value("${app.c-demo.source-identity}")String source,
            @Value("${app.c-demo.db-name}")String database,@Value("${app.c-demo.api-user}")String apiUser,
            @Value("${app.c-demo.marker}")String marker){
        return args->{
            check(run.matches("cdemo-[0-9a-f]{16}")&&source.matches("[0-9a-f]{64}")&&marker.matches("[0-9a-f]{64}"),"IDENTITY");
            String suffix=run.substring(6);
            check(database.equals("cdemo_"+suffix)&&apiUser.equals("cdapi_"+suffix),"DATABASE_NAMES");
            try(Connection c=ds.getConnection();Statement s=c.createStatement()){
                s.setQueryTimeout(3);
                try(ResultSet r=s.executeQuery("SELECT current_database(),current_user")){check(r.next()&&r.getString(1).equals(database)&&r.getString(2).equals(apiUser)&&!r.next(),"DATABASE_IDENTITY");}
                try(ResultSet r=s.executeQuery("SELECT run_id,marker FROM public.c_direct_demo_identity")){check(r.next()&&r.getString(1).equals(run)&&r.getString(2).equals(marker)&&!r.next(),"DATABASE_MARKER");}
                try(ResultSet r=s.executeQuery("SELECT rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname=current_user")){check(r.next()&&!r.getBoolean(1)&&!r.getBoolean(2)&&!r.getBoolean(3),"DATABASE_ROLE");}
            }
            User user=users.findById(1L).orElseThrow(()->new IllegalStateException("C_DIRECT_DEMO_USER_MISSING"));
            check(user.getLoginId().equals("c-demo-user")&&user.getRole()==UserRole.USER&&user.supportsLocalLogin(),"JPA_USER");
        };
    }

    /** Trial-account login only: no signup, faucet, legacy balance or key handling. */
    @RestController
    @ConditionalOnProperty(name="app.c-demo.enabled",havingValue="true")
    public static final class DemoController {
        private final UserRepository users;private final PasswordEncoder passwords;private final JwtTokenProvider tokens;private final MarketPriceService market;
        public DemoController(UserRepository users,PasswordEncoder passwords,JwtTokenProvider tokens,MarketPriceService market){this.users=users;this.passwords=passwords;this.tokens=tokens;this.market=market;}
        public record LoginRequest(@NotBlank @Size(max=50)String loginId,@NotBlank @Size(max=72)String password){}
        public record LoginResponse(String accessToken,String tokenType,long expiresIn){}
        @PostMapping("/api/auth/login") public LoginResponse login(@Valid @RequestBody LoginRequest request){
            String name=request.loginId().trim().toLowerCase(Locale.ROOT);
            User user=users.findByLoginId(name).filter(User::supportsLocalLogin).orElseThrow(DemoLoginFailure::new);
            if(!passwords.matches(request.password(),user.getPasswordHash()))throw new DemoLoginFailure();
            return new LoginResponse(tokens.createToken(user.getId(),user.getLoginId()),"Bearer",tokens.getValiditySeconds());
        }
        @GetMapping("/api/me") public Map<String,String> me(@AuthenticationPrincipal AuthenticatedUser principal){
            if(principal==null||principal.userId()==null||principal.userId()<=0)throw new DemoLoginFailure();
            User user=users.findById(principal.userId()).orElseThrow(DemoLoginFailure::new);
            return Map.of("id",user.getId().toString(),"loginId",user.getLoginId()==null?"":user.getLoginId(),"role",user.getRole().name());
        }
        @GetMapping("/api/c-futures/direct/market") public Map<String,String> market(){
            var p=market.current();return Map.of("symbol",p.symbol(),"price",p.price().toPlainString(),
                "provider",p.provider(),"observedAt",p.observedAt().toString(),
                "marketStatus",p.marketStatus().name(),"priceStatus",p.priceStatus().name());
        }
    }
    private static final class DemoLoginFailure extends RuntimeException {}
    @RestControllerAdvice public static final class DemoAdvice {
        @ExceptionHandler({InvalidTokenException.class,DemoLoginFailure.class})
        public ResponseEntity<Map<String,String>> authentication(RuntimeException ignored){return ResponseEntity.status(401).body(Map.of("code","C_DEMO_AUTHENTICATION_FAILED"));}
        @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
        public ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().body(Map.of("code","C_DEMO_INVALID_REQUEST"));}
    }

    public static void main(String[] args){
        if(args.length==1&&args[0].equals("--self-test")){selfTest();System.out.println("C_DIRECT_DEMO_APP_SELF_TEST_PASS");return;}
        if(args.length!=3||!args[0].equals("/demo-config/application.properties")||!args[1].equals("/demo-control")||!args[2].equals("/demo-evidence"))throw new IllegalArgumentException("C_DIRECT_DEMO_ARGS");
        org.springframework.context.ConfigurableApplicationContext context=null;String stage="CONFIG";
        try{
            Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
            verifyConfig(p);
            int lifetime=Integer.parseInt(p.getProperty("app.c-demo.lifetime-seconds","900"));
            SpringApplication app=new SpringApplication(CDirectDemoApplication.class);app.setBannerMode(Banner.Mode.OFF);app.setLogStartupInfo(false);
            stage="SPRING_START";context=app.run("--spring.config.location=file:"+args[0]);
            var mapper=new ObjectMapper();var ready=mapper.createObjectNode().put("runId",p.getProperty("app.c-demo.run-id"))
                .put("sourceIdentity",p.getProperty("app.c-demo.source-identity")).put("dbName",p.getProperty("app.c-demo.db-name"))
                .put("dbUser",p.getProperty("app.c-demo.api-user")).put("markerSha256",sha(p.getProperty("app.c-demo.marker")))
                .put("scope","LOCAL_C_DIRECT_SYNTHETIC_SELECTED_APP_NOT_FULL_TMS_OR_AUDIT")
                .put("userPrivateKeys",false).put("oraclePrivateKeys",false).put("relayerPrivateKeys",false)
                .put("productSyntheticCommitPath",true).put("actualJpaRepository",true);
            ready.put("syntheticTickMs",Integer.parseInt(p.getProperty("app.price.update-interval-ms","1000")));
            ready.put("sessionSeconds",lifetime).put("jwtValidityMs",Long.parseLong(p.getProperty("app.jwt.validity-ms")));
            Files.write(Path.of(args[2],"server-ready.json"),mapper.writeValueAsBytes(ready),StandardOpenOption.CREATE_NEW);
            stage="BOUNDED_SERVICE";long end=System.nanoTime()+Duration.ofSeconds(lifetime).toNanos();
            while(!Files.isRegularFile(Path.of(args[1],"STOP"))&&System.nanoTime()<end)Thread.sleep(100);
            boolean requested=Files.isRegularFile(Path.of(args[1],"STOP"));
            context.close();context=null;
            Files.writeString(Path.of(args[2],"server-normal-stop.json"),mapper.writeValueAsString(Map.of("reason",requested?"OWNED_STOP_MARKER":"BOUNDED_LIFETIME","scope","SERVER_STOP_ONLY_NOT_CHAIN_OR_TRANSACTION_RESULT")),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            System.out.println("C_DIRECT_DEMO_APP_NORMAL_STOP");System.exit(0);
        }catch(Exception safe){
            try{var mapper=new ObjectMapper();var failure=mapper.createObjectNode().put("stage",stage);var types=failure.putArray("causeTypes");Throwable next=safe;for(int n=0;next!=null&&n<8;n++,next=next.getCause()){types.add(next.getClass().getName());if(next instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}"))failure.put("sqlState",sql.getSQLState());}Files.write(Path.of(args[2],"server-failure.json"),mapper.writeValueAsBytes(failure),StandardOpenOption.CREATE_NEW);}catch(Exception ignored){}
            if(context!=null)context.close();System.err.println("C_DIRECT_DEMO_APP_STOP_NO_RETRY");System.exit(1);
        }
    }
    static void verifyConfig(Properties p){
        for(String key:p.stringPropertyNames()){
            String normalized=key.toLowerCase(Locale.ROOT).replaceAll("[^a-z]","");
            check(!normalized.contains("privatekey")&&!normalized.contains("mnemonic")&&!normalized.contains("seedphrase"),"NO_PRIVATE_KEYS");
        }
        check("true".equals(p.getProperty("app.c-demo.enabled"))&&"true".equals(p.getProperty("app.c-futures.enabled"))&&"true".equals(p.getProperty("app.c-futures.direct.enabled")),"EXPLICIT_OPT_IN");
        check("simulated".equals(p.getProperty("app.price.provider"))&&"0".equals(p.getProperty("app.price.simulation.bootstrap-days","0")),"PRODUCT_SYNTHETIC_ONLY");
        check(Set.of("1000","3000").contains(p.getProperty("app.price.update-interval-ms","1000")),"PRODUCT_TICK_INTERVAL");
        check("validate".equals(p.getProperty("spring.jpa.hibernate.ddl-auto"))&&"never".equals(p.getProperty("spring.sql.init.mode")),"NO_AUTOMATIC_SCHEMA");
        String extended=p.getProperty("app.c-demo.extended-user-session","false");
        check(Set.of("true","false").contains(extended),"SESSION_FLAG");
        int lifetime=Integer.parseInt(p.getProperty("app.c-demo.lifetime-seconds","900"));
        check(lifetime>=30&&lifetime<=(extended.equals("true")?3600:1800),"LIFETIME");
        check("file:/demo-web/".equals(p.getProperty("spring.web.resources.static-locations")),"OWNED_STATIC_ROOT");
    }
    static void selfTest(){
        // Execute the real startup guard, not only class/annotation metadata checks.
        Properties valid=new Properties();
        for(String flag:List.of("app.c-demo.enabled","app.c-futures.enabled","app.c-futures.direct.enabled"))valid.setProperty(flag,"true");
        valid.setProperty("app.price.provider","simulated");valid.setProperty("app.price.simulation.bootstrap-days","0");
        valid.setProperty("spring.jpa.hibernate.ddl-auto","validate");valid.setProperty("spring.sql.init.mode","never");
        valid.setProperty("spring.web.resources.static-locations","file:/demo-web/");
        for(String cadence:List.of("1000","3000")){valid.setProperty("app.price.update-interval-ms",cadence);verifyConfig(valid);}
        valid.setProperty("app.c-demo.lifetime-seconds","3600");
        try{verifyConfig(valid);throw new AssertionError("C_DIRECT_DEMO_UNOPTED_LONG_SESSION");}
        catch(IllegalStateException expected){check(expected.getMessage().equals("C_DIRECT_DEMO_LIFETIME"),"SESSION_GUARD");}
        valid.setProperty("app.c-demo.extended-user-session","true");verifyConfig(valid);
        valid.setProperty("app.c-demo.lifetime-seconds","3601");
        try{verifyConfig(valid);throw new AssertionError("C_DIRECT_DEMO_UNBOUNDED_SESSION");}
        catch(IllegalStateException expected){check(expected.getMessage().equals("C_DIRECT_DEMO_LIFETIME"),"SESSION_GUARD");}
        valid.setProperty("app.c-demo.lifetime-seconds","900");
        for(String cadence:List.of("0","100","5000","30000")){
            valid.setProperty("app.price.update-interval-ms",cadence);
            try{verifyConfig(valid);throw new AssertionError("C_DIRECT_DEMO_BAD_CADENCE_ACCEPTED");}
            catch(IllegalStateException expected){check(expected.getMessage().equals("C_DIRECT_DEMO_PRODUCT_TICK_INTERVAL"),"CADENCE_GUARD");}
        }
        check(MANAGED_TYPES.size()==3&&new CDirectDemoApplication().cDemoManagedTypes().getManagedClassNames().size()==3,"EXACT_MANAGED_TYPES");
        check(CDirectDemoApplication.class.getAnnotation(ConditionalOnProperty.class).name()[0].equals("app.c-demo.enabled"),"DEFAULT_OFF");
        int pathParameters=0;
        for(Class<?> controller:List.of(CCustodyController.class,CDirectController.class,CDirectReceiptController.class))
            for(var method:controller.getDeclaredMethods())for(var parameter:method.getParameters()){
                PathVariable binding=parameter.getAnnotation(PathVariable.class);if(binding==null)continue;
                pathParameters++;
                String explicit=binding.name().isEmpty()?binding.value():binding.name();
                check(parameter.isNamePresent()&&!parameter.getName().matches("arg[0-9]+")
                    &&(explicit.isEmpty()||explicit.matches("[A-Za-z][A-Za-z0-9_]*")),"PATH_PARAMETER_METADATA");
            }
        check(pathParameters>=2,"PATH_PARAMETER_COUNT");
        try{
            var method=DemoController.class.getDeclaredMethod("me",AuthenticatedUser.class);
            check(Arrays.asList(method.getAnnotation(GetMapping.class).value()).contains("/api/me")
                &&method.getParameters()[0].isAnnotationPresent(AuthenticationPrincipal.class),"CURRENT_USER_ROUTE");
        }catch(ReflectiveOperationException absent){throw new IllegalStateException("C_DIRECT_DEMO_CURRENT_USER_ROUTE");}
        for(String forbidden:List.of("CAnvilContractGate","CCustodyLiveDriver","org.junit.jupiter.api.Test"))try{Class.forName(forbidden);throw new IllegalStateException("C_DIRECT_DEMO_SERVER_CLASS_SEPARATION");}catch(ClassNotFoundException expected){}
        for(String key:List.of("userPrivateKey","oracle-private-key","relayer_private_key","wallet.mnemonic","wallet.seed-phrase")){
            Properties p=new Properties();p.setProperty(key,"forbidden");try{verifyConfig(p);throw new AssertionError("C_DIRECT_DEMO_PRIVATE_KEY_ACCEPTED");}catch(IllegalStateException expected){check(expected.getMessage().equals("C_DIRECT_DEMO_NO_PRIVATE_KEYS"),"KEY_GUARD");}
        }
        for(String name:List.of("c-wallet-schema.sql","c-consent-schema.sql","c-direct-execution-schema.sql","c-direct-demo-synthetic-schema.sql"))
            try(var in=CDirectDemoApplication.class.getResourceAsStream("/"+name)){
                check(in!=null,"SQL_RESOURCE_MISSING");byte[] value=in.readNBytes(65537);check(value.length>0&&value.length<=65536,"SQL_RESOURCE_SIZE");
                System.out.println("C_DIRECT_DEMO_RESOURCE "+name+" "+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)));
            }catch(Exception failure){throw new IllegalStateException("C_DIRECT_DEMO_SQL_CLASSPATH_SELF_TEST");}
    }
    private static String sha(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static void check(boolean condition,String code){if(!condition)throw new IllegalStateException("C_DIRECT_DEMO_"+code);}
}
