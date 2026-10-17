package com.pricetrack.exchange.auth.google;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.*;
import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {"app.google.enabled=true", "app.google.client-id=offline-google-client",
        "app.google.allowed-origins=http://localhost:5173", "app.ai.tools.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:google_auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @Import(GoogleTestIdentity.class) @DirtiesContext
class GoogleAuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired GoogleAccountService google;
    @Autowired JwtTokenProvider jwt;
    @Autowired WalletService wallets;
    @Autowired UserBalanceRepository balances;
    @Autowired AuthService local;
    @Autowired JdbcTemplate jdbc;

    @Test void signedGoogleLoginCreatesZeroUserAndPreservesInternalJwtIdentity() throws Exception {
        var attempt = challenge("/api/auth/google/challenges", null, null);
        var user = login(attempt, "new-google-"+UUID.randomUUID());
        String token = user.path("accessToken").asText();
        Long id = jwt.getUserId(token);
        User account = users.findById(id).orElseThrow();
        assertThat(account.getLoginId()).isNull(); assertThat(account.getPasswordHash()).isNull();
        assertThat(account.getEmail()).isNull(); assertThat(account.isEmailVerified()).isFalse();
        assertThat(account.getRole()).isEqualTo(UserRole.USER);
        assertThat(balances.findByUserIdAndSymbol(id,"mKRW").orElseThrow().getAmount()).isZero();
        mvc.perform(get("/api/me").header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.loginId").isEmpty());
        for (String path : List.of("/api/portfolio","/api/orders","/api/trades","/api/wallet/faucet/status","/api/me/auth-methods"))
            mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/faucet").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/tools/getPortfolio").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"arguments\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(post("/api/ai/tools/listAbnormalOrders").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"arguments\":{}}"))
                .andExpect(status().isForbidden());
        wallets.faucet(id,"google-preserve-grant-"+UUID.randomUUID());
        var next = challenge("/api/auth/google/challenges",null,attempt.cookie());
        var result = login(next, account.getGoogleSub());
        assertThat(jwt.getUserId(result.path("accessToken").asText())).isEqualTo(id);
        assertThat(balances.findByUserIdAndSymbol(id,"mKRW").orElseThrow().getAmount()).isEqualByComparingTo("1000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_balances WHERE user_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM faucet_grants WHERE user_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void nonceBindingPurposeAndReplayAreEnforced() throws Exception {
        var attempt = challenge("/api/auth/google/challenges",null,null);
        String sub = "nonce-"+UUID.randomUUID();
        var proof = Map.of("challengeId",attempt.body().path("challengeId").asText(),"credential",GoogleTestIdentity.token(sub,"x".repeat(43)));
        mvc.perform(post("/api/auth/google/login").header("Origin","http://localhost:5173").header("X-Google-Auth","1")
                .cookie(attempt.cookie()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(proof)))
                .andExpect(status().isUnauthorized());
        login(attempt,sub);
        mvc.perform(post("/api/auth/google/login").header("Origin","http://localhost:5173").header("X-Google-Auth","1")
                .cookie(attempt.cookie()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "challengeId",attempt.body().path("challengeId").asText(),"credential",GoogleTestIdentity.token(sub,attempt.body().path("nonce").asText())))))
                .andExpect(status().isUnauthorized());
        assertThat(users.findByGoogleSub(sub)).isPresent();
    }
    @Test void linkingRequiresLocalProofAndDoesNotMergeMatchingEmail() throws Exception {
        String login = "local_"+UUID.randomUUID().toString().substring(0,12);
        var token = local.signup(new AuthController.SignupRequest(login,"fixture-password","Local user"));
        Long id = jwt.getUserId(token.accessToken());
        User existing = users.findById(id).orElseThrow(); existing.setEmail("same-email@example.test"); users.save(existing);
        wallets.faucet(id,"link-grant-"+UUID.randomUUID());
        var before = jdbc.queryForList("SELECT * FROM user_balances WHERE user_id=? ORDER BY symbol",id);
        var grants = jdbc.queryForList("SELECT * FROM faucet_grants WHERE user_id=?",id);
        var attempt = challenge("/api/me/google/challenges",token.accessToken(),null);
        var proof = new HashMap<String,String>(); proof.put("challengeId",attempt.body().path("challengeId").asText());
        proof.put("credential",GoogleTestIdentity.token("link-"+UUID.randomUUID(),attempt.body().path("nonce").asText())); proof.put("password","wrong");
        mvc.perform(post("/api/me/google/link").header("Authorization","Bearer "+token.accessToken()).header("Origin","http://localhost:5173")
                .header("X-Google-Auth","1").cookie(attempt.cookie()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(proof)))
                .andExpect(status().isUnauthorized());
        proof.put("password","fixture-password");
        mvc.perform(post("/api/me/google/link").header("Authorization","Bearer "+token.accessToken()).header("Origin","http://localhost:5173")
                .header("X-Google-Auth","1").cookie(attempt.cookie()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(proof)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForList("SELECT * FROM user_balances WHERE user_id=? ORDER BY symbol",id)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM faucet_grants WHERE user_id=?",id)).isEqualTo(grants);
        assertThat(users.findById(id).orElseThrow().getEmail()).isEqualTo("same-email@example.test");
        var unrelated=challenge("/api/auth/google/challenges",null,null);
        Long separate=jwt.getUserId(login(unrelated,"different-sub-"+UUID.randomUUID()).path("accessToken").asText());
        assertThat(separate).isNotEqualTo(id); assertThat(users.findById(separate).orElseThrow().getEmail()).isNull();
    }
    @Test void rejectsMissingOriginAndDisabledOrCrossOriginRequests() throws Exception {
        mvc.perform(post("/api/auth/google/challenges").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/google/challenges").header("Origin","https://attacker.test").header("X-Google-Auth","1")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/me/google/challenges").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
    @Test void rejectsForeignBrowserExpiredAndWrongPurposeWithoutConsumingValidAttempt() {
        String binding=GoogleAccountService.random();var challenge=google.issue(binding,null);
        String proof=GoogleTestIdentity.token("binding-sub",challenge.nonce());
        assertThatThrownBy(()->google.login(challenge.challengeId(),GoogleAccountService.random(),proof)).isInstanceOf(GoogleAuthFailure.class);
        assertThatThrownBy(()->google.login(challenge.challengeId(),null,proof)).isInstanceOf(GoogleAuthFailure.class);
        jdbc.update("UPDATE google_auth_challenges SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),challenge.challengeId());
        assertThatThrownBy(()->google.login(challenge.challengeId(),binding,proof)).isInstanceOf(GoogleAuthFailure.class);
        assertThat(users.findByGoogleSub("binding-sub")).isEmpty();
        var localUser=local.signup(new AuthController.SignupRequest("purpose_"+UUID.randomUUID().toString().substring(0,8),"fixture-password","Local fixture"));
        Long id=jwt.getUserId(localUser.accessToken());var link=google.issue(binding,id);
        assertThatThrownBy(()->google.login(link.challengeId(),binding,GoogleTestIdentity.token("wrong-purpose",link.nonce())))
                .isInstanceOf(GoogleAuthFailure.class);
        var login=google.issue(binding,null);
        assertThatThrownBy(()->google.link(id,login.challengeId(),binding,GoogleTestIdentity.token("wrong-purpose",login.nonce()),"fixture-password"))
                .isInstanceOf(GoogleAuthFailure.class);
        assertThat(users.findById(id).orElseThrow().getGoogleSub()).isNull();
        var other=local.signup(new AuthController.SignupRequest("foreign_"+UUID.randomUUID().toString().substring(0,8),"fixture-password","Other fixture"));
        Long otherId=jwt.getUserId(other.accessToken());
        assertThatThrownBy(()->google.link(otherId,link.challengeId(),binding,GoogleTestIdentity.token("wrong-principal",link.nonce()),"fixture-password"))
                .isInstanceOf(GoogleAuthFailure.class);
        assertThat(users.findById(otherId).orElseThrow().getGoogleSub()).isNull();
    }
    record Attempt(JsonNode body,Cookie cookie) { }
    private Attempt challenge(String path,String token,Cookie cookie) throws Exception {
        var request=post(path).header("Origin","http://localhost:5173").header("X-Google-Auth","1")
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(cookie!=null)request.cookie(cookie);
        var response=mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        return new Attempt(json.readTree(response.getContentAsString()),response.getCookie("tms_google_binding"));
    }
    private JsonNode login(Attempt attempt,String sub) throws Exception {
        var response=mvc.perform(post("/api/auth/google/login").header("Origin","http://localhost:5173").header("X-Google-Auth","1")
                .cookie(attempt.cookie()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "challengeId",attempt.body().path("challengeId").asText(),"credential",GoogleTestIdentity.token(sub,attempt.body().path("nonce").asText())))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString());
    }
}
