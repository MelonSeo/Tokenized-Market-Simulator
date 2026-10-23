package com.pricetrack.exchange.futures.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.common.config.SecurityConfig;
import com.pricetrack.exchange.futures.read.FuturesReadService;
import com.pricetrack.exchange.user.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT filter and MVC authorization, mocked business dependencies; no DB/RPC/listener. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ContextConfiguration(classes=AuthorizedApiSecurityTest.Config.class)
class AuthorizedApiSecurityTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfig.class,JwtAuthenticationFilter.class})
    static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean JwtTokenProvider jwt(){return new JwtTokenProvider("offline-test-only-abcdefghijklmnopqrstuvwxyz-123456789",60_000);}
        @Bean UserRepository users(){return mock(UserRepository.class);}
        @Bean AuthorizedFuturesService service(){return mock(AuthorizedFuturesService.class);}
        @Bean AuthorizedFuturesStore store(){return mock(AuthorizedFuturesStore.class);}
        @Bean FuturesReadService reads(){return mock(FuturesReadService.class);}
        @Bean AuthorizedFuturesController controller(AuthorizedFuturesService s,AuthorizedFuturesStore a,FuturesReadService r){return new AuthorizedFuturesController(s,a,r);}
    }
    @Autowired WebApplicationContext context; @Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users; @Autowired AuthorizedFuturesService service; @Autowired AuthorizedFuturesStore store;
    MockMvc mvc;String token;
    @BeforeEach void setup(){
        reset(users,service,store);
        User u=mock(User.class);when(u.getId()).thenReturn(7L);when(u.getLoginId()).thenReturn(null);when(u.getRole()).thenReturn(UserRole.USER);
        when(users.findById(7L)).thenReturn(Optional.of(u));token=jwt.createToken(7L,null);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
    }
    @Test void requiresJwtAndUsesInternalSubjectEvenWithoutLocalLoginId()throws Exception{
        mvc.perform(get("/api/futures/authorized/wallet")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
        when(store.wallet(7)).thenReturn("0x0000000000000000000000000000000000000001");
        mvc.perform(get("/api/futures/authorized/wallet").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.wallet").value("0x0000000000000000000000000000000000000001"));
        verify(store).wallet(7);
    }
    @Test void bodyCannotSelectUserAndMalformedSignatureDoesNotReachBusinessLayer()throws Exception{
        String body="{\"signature\":\"invalid\",\"userId\":99}";
        mvc.perform(post("/api/futures/authorized/drafts/draft/submit").header("Authorization","Bearer "+token)
            .header("Idempotency-Key","key").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void forwardsJwtOwnerNotBodyUserAndRequiresWalletOrigin()throws Exception{
        String wallet="0x0000000000000000000000000000000000000001";
        mvc.perform(post("/api/futures/authorized/wallet/challenges").header("Authorization","Bearer "+token)
            .contentType("application/json").content("{\"wallet\":\""+wallet+"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/futures/authorized/wallet/challenges").header("Authorization","Bearer "+token).header("Origin","http://localhost:5173")
            .contentType("application/json").content("{\"wallet\":\""+wallet+"\",\"userId\":99}")).andExpect(status().isOk());
        verify(service).challenge(7,wallet,"http://localhost:5173");
    }
}
