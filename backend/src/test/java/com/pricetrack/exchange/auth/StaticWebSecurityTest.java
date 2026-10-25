package com.pricetrack.exchange.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.common.config.SecurityConfig;
import com.pricetrack.exchange.user.UserRepository;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real product JWT/security chain; controlled MVC handlers, no DB/RPC/listener. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ContextConfiguration(classes=StaticWebSecurityTest.Config.class)
class StaticWebSecurityTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @Import({SecurityConfig.class,JwtAuthenticationFilter.class})
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean JwtTokenProvider jwt(){return new JwtTokenProvider("offline-static-web-test-only-abcdefghijklmnopqrstuvwxyz-123456789",60_000);}
        @Bean UserRepository users(){return (UserRepository)Proxy.newProxyInstance(UserRepository.class.getClassLoader(),new Class<?>[]{UserRepository.class},(p,m,a)->{
            if(m.getDeclaringClass()==Object.class)return switch(m.getName()){case "toString"->"NoDatabaseAccess";case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->throw new AssertionError();};
            throw new AssertionError("Anonymous static reads must not access a user database");
        });}
        @Bean Probe controller(){return new Probe();}
    }
    @RestController static class Probe {
        @GetMapping({"/","/index.html","/assets/probe.js"}) String asset(){return "public UI resource";}
    }
    @Autowired WebApplicationContext context;MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();}
    @Test void onlyGetBuiltWebAssetsArePublic()throws Exception{
        for(String p:new String[]{"/","/index.html","/assets/probe.js"})mvc.perform(get(p)).andExpect(status().isOk());
        mvc.perform(post("/assets/probe.js")).andExpect(status().isUnauthorized());
    }
    @Test void privateAssetsApisAndNonWebResourcesStillRequireJwt()throws Exception{
        for(String p:new String[]{"/api/c-futures/account","/api/c-futures/positions/0x"+"1".repeat(64),"/api/portfolio","/api/admin/faucet","/application.yml","/schema.sql"})mvc.perform(get(p)).andExpect(status().isUnauthorized());
    }
}
