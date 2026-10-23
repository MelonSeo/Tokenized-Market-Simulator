package com.pricetrack.exchange.futures.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.common.config.SecurityConfig;
import com.pricetrack.exchange.futures.audit.FuturesAuthorizedAuditService;
import com.pricetrack.exchange.user.*;
import java.util.*;
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
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Actual JWT filter/security matcher and MVC; mocked audit service, no DB/RPC/listener. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ContextConfiguration(classes=FuturesAuthorizedAuditSecurityTest.Config.class)
class FuturesAuthorizedAuditSecurityTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @Import({SecurityConfig.class,JwtAuthenticationFilter.class})
    static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean JwtTokenProvider jwt(){return new JwtTokenProvider("offline-test-only-abcdefghijklmnopqrstuvwxyz-123456789",60_000);}
        @Bean UserRepository users(){return mock(UserRepository.class);}
        @Bean FuturesAuthorizedAuditService service(){return mock(FuturesAuthorizedAuditService.class);}
        @Bean FuturesAuthorizedAuditController controller(FuturesAuthorizedAuditService service){return new FuturesAuthorizedAuditController(service);}
    }
    @Autowired WebApplicationContext context;@Autowired JwtTokenProvider jwt;@Autowired UserRepository users;@Autowired FuturesAuthorizedAuditService service;
    MockMvc mvc;String userToken,adminToken;
    @BeforeEach void setup(){
        reset(users,service);for(long id:new long[]{7,8}){var user=mock(User.class);when(user.getId()).thenReturn(id);when(user.getRole()).thenReturn(id==8?UserRole.ADMIN:UserRole.USER);when(users.findById(id)).thenReturn(Optional.of(user));}
        userToken=jwt.createToken(7L,null);adminToken=jwt.createToken(8L,null);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
    }
    @Test void anonymous401AndUser403NeverReachAuditService()throws Exception {
        mvc.perform(post("/api/admin/futures/authorized-audits")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/futures/authorized-audits").header("Authorization","Bearer "+userToken)).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void adminUsesJwtActorNotBodyAndBaselineAcceptsOnlyRegisteredKeyContract()throws Exception {
        mvc.perform(post("/api/admin/futures/authorized-audits").header("Authorization","Bearer "+adminToken).contentType("application/json").content("{\"actorId\":999}" )).andExpect(status().isOk());verify(service).run(8);
        mvc.perform(post("/api/admin/futures/authorized-audits/baselines").header("Authorization","Bearer "+adminToken).contentType("application/json").content("{\"targetKey\":\"registered-key\",\"actorId\":999,\"rpc\":\"ignored\"}")).andExpect(status().isCreated());verify(service).baseline("registered-key",8);
    }
    @Test void adminCanReadTargetsAndBaselinesButUserCannot()throws Exception {
        when(service.targets()).thenReturn(new FuturesAuthorizedAuditService.Targets(List.of(),FuturesAuthorizedAuditService.HISTORY));when(service.baselines()).thenReturn(List.of());
        mvc.perform(get("/api/admin/futures/authorized-audits/targets").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.history").value(FuturesAuthorizedAuditService.HISTORY));
        mvc.perform(get("/api/admin/futures/authorized-audits/baselines").header("Authorization","Bearer "+userToken)).andExpect(status().isForbidden());verify(service,never()).baselines();
    }
}
