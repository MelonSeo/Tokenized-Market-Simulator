import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.common.config.SecurityConfig;
import com.pricetrack.exchange.common.exception.*;
import com.pricetrack.exchange.custody.api.CCustodyController;
import com.pricetrack.exchange.custody.runtime.CCustodyRuntimeConfig;
import com.pricetrack.exchange.user.*;
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
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Test-only selected real Spring/JWT/JPA/C context. No whole-product component scan or key generator. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@EntityScan(basePackageClasses=User.class)
@EnableJpaRepositories(basePackageClasses=UserRepository.class)
@Import({CCustodyRuntimeConfig.class,CCustodyController.class,JwtTokenProvider.class,JwtAuthenticationFilter.class,
    SecurityConfig.class,CCustodyLiveApplication.TokenAdvice.class})
public class CCustodyLiveApplication {
    @RestControllerAdvice
    public static class TokenAdvice {
        /** Narrow fixture advice; the full product GlobalExceptionHandler is intentionally NOT loaded. */
        @ExceptionHandler(InvalidTokenException.class)
        public ResponseEntity<ApiErrorResponse> invalid(InvalidTokenException ignored){return ResponseEntity.status(401).body(ApiErrorResponse.of(401,"INVALID_TOKEN","인증 토큰을 확인할 수 없습니다.","/api/c-futures"));}
    }
    @Bean ApplicationRunner verifyOwnedDatabase(DataSource ds,UserRepository users,
            @Value("${app.c-gate.run-id}")String run,@Value("${app.c-gate.source-identity}")String source,
            @Value("${app.c-gate.db-name}")String database,@Value("${app.c-gate.api-user}")String apiUser,
            @Value("${app.c-gate.marker}")String marker){
        return args->{
            check(run.matches("c-live-[0-9a-f]{16}")&&source.matches("[0-9a-f]{64}")&&marker.matches("[0-9a-f]{64}"),"APP_IDENTITY");
            try(Connection c=ds.getConnection();Statement s=c.createStatement()){
                s.setQueryTimeout(3);
                try(ResultSet r=s.executeQuery("SELECT current_database(),current_user")){check(r.next()&&r.getString(1).equals(database)&&r.getString(2).equals(apiUser),"DATABASE_OWNER");}
                try(ResultSet r=s.executeQuery("SELECT run_id,marker FROM public.c_live_fixture_identity")){check(r.next()&&r.getString(1).equals(run)&&r.getString(2).equals(marker)&&!r.next(),"DATABASE_MARKER");}
                try(ResultSet r=s.executeQuery("SELECT rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname=current_user")){check(r.next()&&!r.getBoolean(1)&&!r.getBoolean(2)&&!r.getBoolean(3),"DATABASE_ROLE_PRIVILEGE");}
            }
            // This is the actual generated Spring Data JPA repository, not a fake bean or JDBC substitute.
            User user=users.findById(1L).orElseThrow(()->new IllegalStateException("C_LIVE_APP_USER_MISSING"));
            check(user.getLoginId().equals("c-live-user-1")&&user.getRole()==UserRole.USER,"JPA_USER");
        };
    }
    public static void main(String[] args){
        if(args.length==1&&args[0].equals("--self-test")){selfTest();System.out.println("C_LIVE_APP_OFFLINE_SELF_TEST_PASS");return;}
        if(args.length!=3||!args[0].equals("/server-config/application.properties")||!args[1].equals("/server-control")||!args[2].equals("/server-evidence"))throw new IllegalArgumentException("C_LIVE_APP_ARGS");
        org.springframework.context.ConfigurableApplicationContext context=null;String stage="APP_CONFIG";
        try{
            Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
            check(!p.containsKey("userPrivateKey")&&!p.containsKey("relayerPrivateKey")&&!p.containsKey("oraclePrivateKey"),"APP_PRIVATE_KEY_SETTING");
            SpringApplication app=new SpringApplication(CCustodyLiveApplication.class);app.setBannerMode(Banner.Mode.OFF);app.setLogStartupInfo(false);
            stage="APP_START_SELECTED_CONTEXT";
            context=app.run("--spring.config.location=file:"+args[0]);
            var result=new ObjectMapper().createObjectNode().put("runId",p.getProperty("app.c-gate.run-id"))
                .put("sourceIdentity",p.getProperty("app.c-gate.source-identity")).put("dbName",p.getProperty("app.c-gate.db-name"))
                .put("dbUser",p.getProperty("app.c-gate.api-user")).put("markerSha256",sha(p.getProperty("app.c-gate.marker")))
                .put("scope","SELECTED_PRODUCTION_C_SPRING_CONTEXT_NOT_FULL_TMS_OR_BROWSER").put("actualJpaRepository",true);
            Files.write(Path.of(args[2],"server-ready.json"),new ObjectMapper().writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
            stage="APP_WAIT_NORMAL_STOP";
            long end=System.nanoTime()+Duration.ofSeconds(240).toNanos();
            while(!Files.isRegularFile(Path.of(args[1],"STOP"))){check(System.nanoTime()<end,"APP_LIFETIME");Thread.sleep(100);}
            context.close();context=null;System.out.println("C_LIVE_APP_NORMAL_STOP");System.exit(0);
        }catch(Exception safe){
            try{var mapper=new ObjectMapper();var failure=mapper.createObjectNode().put("stage",stage);var causes=failure.putArray("causeTypes");Throwable next=safe;for(int depth=0;next!=null&&depth<8;depth++,next=next.getCause()){causes.add(next.getClass().getName());if(next instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}"))failure.put("sqlState",sql.getSQLState());}Files.write(Path.of(args[2],"server-failure.json"),mapper.writeValueAsBytes(failure),StandardOpenOption.CREATE_NEW);}catch(Exception ignored){}
            if(context!=null)context.close();System.err.println("C_LIVE_APP_STOP_NO_RETRY");System.exit(1);
        }
    }
    static void selfTest(){
        check(Set.of(CCustodyLiveApplication.class.getAnnotation(EnableAutoConfiguration.class).exclude()).equals(Set.of(org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)),"JWT_ONLY_NO_DEFAULT_PASSWORD");
        check(Set.of(CCustodyLiveApplication.class.getAnnotation(Import.class).value()).equals(Set.of(CCustodyRuntimeConfig.class,CCustodyController.class,JwtTokenProvider.class,JwtAuthenticationFilter.class,SecurityConfig.class,TokenAdvice.class)),"SELECTIVE_IMPORTS");
        check(CCustodyLiveApplication.class.getAnnotation(EntityScan.class).basePackageClasses()[0].equals(User.class),"REAL_USER_ENTITY");
        check(CCustodyLiveApplication.class.getAnnotation(EnableJpaRepositories.class).basePackageClasses()[0].equals(UserRepository.class),"REAL_JPA_REPOSITORY");
        for(String method:List.of("position","claim"))try{
            var parameter=CCustodyController.class.getDeclaredMethod(method,AuthenticatedUser.class,String.class).getParameters()[1];
            PathVariable binding=parameter.getAnnotation(PathVariable.class);check(binding!=null,"PATH_VARIABLE_ANNOTATION");
            String explicit=binding.name().isEmpty()?binding.value():binding.name();
            check(explicit.isEmpty()?parameter.isNamePresent()&&parameter.getName().equals("id"):explicit.equals("id"),"PATH_PARAMETER_METADATA");
        }catch(ReflectiveOperationException missing){throw new IllegalStateException("C_LIVE_PATH_PARAMETER_METADATA");}
        for(String forbidden:List.of("CAnvilContractGate","CCustodyLiveDriver","org.junit.jupiter.api.Test"))try{Class.forName(forbidden);throw new IllegalStateException("C_LIVE_SERVER_CLASS_SEPARATION");}catch(ClassNotFoundException expected){}
    }
    private static String sha(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static void check(boolean condition,String code){if(!condition)throw new IllegalStateException("C_LIVE_"+code);}
}
