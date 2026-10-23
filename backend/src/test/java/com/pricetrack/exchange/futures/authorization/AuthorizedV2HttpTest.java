package com.pricetrack.exchange.futures.authorization;
import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.futures.read.FuturesReadService;
import com.pricetrack.exchange.user.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC/JWT filter + mocked V2 service; no server listener, DB/RPC or wallet. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ContextConfiguration(classes=AuthorizedV2HttpTest.Config.class)
class AuthorizedV2HttpTest {
    @Configuration @Import(AuthorizedApiSecurityTest.Config.class) static class Config {
        @Bean AuthorizedV2Service v2Service(){return mock(AuthorizedV2Service.class);}
        @Bean AuthorizedV2Controller v2Controller(AuthorizedV2Service s,AuthorizedFuturesService proof,AuthorizedFuturesStore store,FuturesReadService reads){var chain=new com.pricetrack.exchange.futures.chain.FuturesChainIdentity(java.math.BigInteger.valueOf(31337),"0x"+"2".repeat(40),"0x"+"4".repeat(40),"0x"+"5".repeat(40),"0x"+"3".repeat(40),"2");return new AuthorizedV2Controller(new AuthorizedV2Config.V2Runtime(new AuthorizedFuturesConfig.Runtime(chain,null,null,null),proof,s),store,reads);}
    }
    @Autowired WebApplicationContext context;@Autowired UserRepository users;@Autowired JwtTokenProvider jwt;@Autowired AuthorizedV2Service service;
    MockMvc mvc;String token;
    @BeforeEach void setup(){reset(users,service);var user=mock(User.class);when(user.getId()).thenReturn(9L);when(user.getRole()).thenReturn(UserRole.USER);when(user.getLoginId()).thenReturn(null);when(users.findById(9L)).thenReturn(Optional.of(user));token=jwt.createToken(9L,null);mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();}
    @Test void anonymousCannotUseV2Api()throws Exception{mvc.perform(get("/api/futures/authorized-v2/nonce")).andExpect(status().isUnauthorized());verifyNoInteractions(service);}
    @Test void browserConfigIsAuthenticatedExactIdentityAndNotCustodyOrAuditClaim()throws Exception{
        mvc.perform(get("/api/futures/authorized-v2/config")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/futures/authorized-v2/config").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.chainId").value("31337")).andExpect(jsonPath("$.controller").value("0x"+"2".repeat(40))).andExpect(jsonPath("$.relayer").value("0x"+"4".repeat(40))).andExpect(jsonPath("$.maxUserTtlSeconds").value("120")).andExpect(jsonPath("$.custody").value("POSTGRESQL_MOCK_COLLATERAL")).andExpect(jsonPath("$.auditCoverage").value("INCONCLUSIVE"));
        verifyNoInteractions(service);
    }
    @Test void browserPositionVersionAndEpochRemainExactStringsForSigning()throws Exception{
        var store=context.getBean(AuthorizedFuturesStore.class);var reads=context.getBean(FuturesReadService.class);
        var chain=new com.pricetrack.exchange.futures.chain.FuturesChainIdentity(java.math.BigInteger.valueOf(31337),"0x"+"2".repeat(40),"0x"+"4".repeat(40),"0x"+"5".repeat(40),"0x"+"3".repeat(40),"2");
        when(store.position("test-position",9L)).thenReturn(new UserIntentCodec.Authorization(chain,"0x"+"1".repeat(40),null,null));
        var p=mock(FuturesReadService.PositionView.class);when(p.version()).thenReturn(9007199254740993L);when(p.callEpoch()).thenReturn(9007199254740995L);when(reads.position(9L,"test-position")).thenReturn(p);
        mvc.perform(get("/api/futures/authorized-v2/positions/test-position").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.positionVersion").value("9007199254740993")).andExpect(jsonPath("$.callEpoch").value("9007199254740995"));
    }
    @Test void googleOnlySubjectAndIntegerStringsReachV2ServiceWithoutBodyUserId()throws Exception{
        String body="{\"action\":\"OPEN\",\"expectedVersion\":0,\"side\":\"LONG\",\"leverage\":10,\"margin\":\"100000000000000000000000\",\"priceMinE8\":\"7400000000000\",\"priceMaxE8\":\"7600000000000\",\"quantityMin\":\"13157894736842105263\",\"quantityMax\":\"13513513513513513513\",\"ttlSeconds\":120,\"userId\":99}";
        mvc.perform(post("/api/futures/authorized-v2/drafts").header("Authorization","Bearer "+token).header("Idempotency-Key","key").contentType("application/json").content(body)).andExpect(status().isOk());
        verify(service).draft(eq(9L),eq("key"),argThat(r->r.quantityMin().toString().equals("13157894736842105263")&&r.margin().toString().equals("100000000000000000000000")));
    }
    @Test void missingExplicitBoundsCannotBeDefaultedByApi()throws Exception{
        mvc.perform(post("/api/futures/authorized-v2/drafts").header("Authorization","Bearer "+token).header("Idempotency-Key","key").contentType("application/json").content("{\"action\":\"OPEN\",\"side\":\"LONG\",\"leverage\":10,\"margin\":\"100\",\"ttlSeconds\":120}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
}
