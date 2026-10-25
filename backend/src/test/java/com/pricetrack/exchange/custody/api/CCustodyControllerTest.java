package com.pricetrack.exchange.custody.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.custody.auth.CWalletLinkStore;
import com.pricetrack.exchange.custody.auth.CWalletProofService;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.user.UserRole;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Direct controller + controlled H2/Mock RPC. NOT real HTTP/JWT filter/PG/chain acceptance. */
class CCustodyControllerTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC);
    private static AuthenticatedUser user(UserRole role){return new AuthenticatedUser(1L,"controlled-user",role);}
    @Test void everyRouteRejectsMissingPrincipalBeforeRpcOrDatabaseLookup()throws Exception{
        var h=new Harness(false);
        List<Runnable> routes=List.of(()->h.api.config(null),()->h.api.wallet(null),()->h.api.account(null),
            ()->h.api.position(null,hash(77)),()->h.api.claim(null,"1"),
            ()->h.api.challenge(null,h.identity.origin(),new CCustodyController.WalletRequest(h.fixture.owner)),
            ()->h.api.link(null,h.identity.origin(),new CCustodyController.LinkRequest(UUID.randomUUID().toString(),"0x")));
        for(Runnable route:routes)assertEquals(401,assertThrows(CCustodyFailure.class,route::run).status());
        assertEquals(0,h.fixture.calls);
    }
    @Test void jwtOwnedLinkSelectsWalletAndUnpaidIsNeverFree()throws Exception{
        var h=new Harness(true);var out=h.api.account(user(UserRole.USER));
        assertEquals(h.fixture.owner,out.value().owner());assertEquals("100",out.value().free());assertEquals("900",out.value().totalUnpaidProfit());
        assertNotEquals(out.value().free(),new java.math.BigInteger(out.value().free()).add(new java.math.BigInteger(out.value().totalUnpaidProfit())).toString());
        assertEquals(h.fixture.owner,h.api.wallet(user(UserRole.USER)).wallet());assertTrue(h.fixture.calls>0);
    }
    @Test void configDefaultsToReadOnlyAndPreservesPinnedObservationScope()throws Exception{
        var h=new Harness(true);var config=h.api.config(user(UserRole.USER));
        assertEquals("true",config.value().get("readOnly"));assertNotNull(config.block());
        assertFalse(config.completeCoverage());assertFalse(config.globalCanonicalVerified());
    }
    @Test void linkedUserAndAdministratorCannotReadForeignPositionOrClaim()throws Exception{
        var h=new Harness(true);h.fixture.foreignOwner=true;
        for(UserRole role:List.of(UserRole.USER,UserRole.ADMIN)){
            assertEquals(404,assertThrows(CCustodyFailure.class,()->h.api.position(user(role),hash(77))).status());
            assertEquals(404,assertThrows(CCustodyFailure.class,()->h.api.claim(user(role),"1")).status());
        }
    }
    @Test void ownTerminalPositionAndSingleClaimAreReadWithoutScannerCompleteness()throws Exception{
        var h=new Harness(true);var position=h.api.position(user(UserRole.USER),hash(77));var claim=h.api.claim(user(UserRole.USER),"1");
        assertEquals("CLOSED",position.value().status());assertEquals("1000",position.value().historicalCollateral());assertEquals("0",position.value().lockedCollateral());
        assertEquals("900",claim.value().originalAmount());assertEquals("400",claim.value().paidAmount());assertEquals("500",claim.value().remainingAmount());
        assertFalse(position.completeCoverage());assertFalse(claim.completeCoverage());
    }
    @Test void serializedApiSnapshotIncludesScopeAndJavaTimeNotOnlyRecordComponents()throws Exception{
        var h=new Harness(true);ObjectMapper productLike=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode json=productLike.readTree(productLike.writeValueAsBytes(h.api.account(user(UserRole.USER))));
        assertAll(
            ()->assertTrue(json.path("sameProviderHeaderRechecked").isBoolean(),"scope must be present in actual JSON"),
            ()->assertTrue(json.path("sameProviderHeaderRechecked").booleanValue()),
            ()->assertTrue(json.path("completeCoverage").isBoolean()),
            ()->assertFalse(json.path("completeCoverage").booleanValue()),
            ()->assertTrue(json.path("globalCanonicalVerified").isBoolean()),
            ()->assertFalse(json.path("globalCanonicalVerified").booleanValue()),
            ()->assertEquals("SINGLE_PROVIDER_PINNED_BLOCK_HASH_RECHECK_NOT_SCANNER_OR_FINALITY",json.path("verificationScope").asText()),
            ()->assertEquals(CLOCK.instant().toString(),json.path("observedAt").asText()),
            ()->assertTrue(json.path("value").path("free").isTextual()),
            ()->assertEquals("100",json.path("value").path("free").asText()));
    }
    @Test void wrongOriginAndNullBodiesDoNotEnterRpcOrSqlProofFlow()throws Exception{
        var h=new Harness(false);
        assertEquals(403,assertThrows(CCustodyFailure.class,()->h.api.challenge(user(UserRole.USER),"http://other-origin:5173",new CCustodyController.WalletRequest(h.fixture.owner))).status());
        assertEquals(403,assertThrows(CCustodyFailure.class,()->h.api.link(user(UserRole.USER),null,new CCustodyController.LinkRequest(UUID.randomUUID().toString(),"0x"))).status());
        assertEquals(400,assertThrows(CCustodyFailure.class,()->h.api.challenge(user(UserRole.USER),h.identity.origin(),null)).status());
        assertEquals(400,assertThrows(CCustodyFailure.class,()->h.api.link(user(UserRole.USER),h.identity.origin(),null)).status());
        assertEquals(0,h.fixture.calls);
    }
    @Test void safeFailureDtoDoesNotExposeExceptionProviderOrProofDetails()throws Exception{
        var h=new Harness(false);var out=h.api.failure(new CCustodyFailure("C_RPC_TRANSPORT",503));
        assertEquals(503,out.getStatusCode().value());assertEquals(Set.of("code","scope"),out.getBody().keySet());
        assertEquals("C_RPC_TRANSPORT",out.getBody().get("code"));assertEquals("C_READ_WALLET_ONLY",out.getBody().get("scope"));
    }
    @Test void routeContractContainsOnlyOwnReadsAndIdentityProofWrites(){
        assertArrayEquals(new String[]{"/api/c-futures"},CCustodyController.class.getAnnotation(RequestMapping.class).value());
        List<String> gets=new ArrayList<>(),posts=new ArrayList<>();
        for(var method:CCustodyController.class.getDeclaredMethods()){
            if(method.isAnnotationPresent(GetMapping.class))gets.addAll(List.of(method.getAnnotation(GetMapping.class).value()));
            if(method.isAnnotationPresent(PostMapping.class))posts.addAll(List.of(method.getAnnotation(PostMapping.class).value()));
            assertNull(method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class));
            assertNull(method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class));
            assertNull(method.getAnnotation(org.springframework.web.bind.annotation.PatchMapping.class));
        }
        assertEquals(Set.of("/config","/wallet","/account","/positions/{id}","/claims/{id}"),Set.copyOf(gets));assertEquals(5,gets.size());
        assertEquals(Set.of("/wallet/challenges","/wallet/link"),Set.copyOf(posts));assertEquals(2,posts.size());
    }
    private static String hash(int value){return CCustodyIdentityTest.hash(value);}
    private static final class Harness {
        final CCustodyRpcReaderTest.Fixture fixture=new CCustodyRpcReaderTest.Fixture();final CCustodyIdentity identity=CCustodyIdentityTest.sample();final CCustodyController api;
        Harness(boolean linked)throws Exception{
            DataSource ds;
            if(linked){
                JdbcDataSource h2=new JdbcDataSource();h2.setURL("jdbc:h2:mem:c_api_"+UUID.randomUUID().toString().replace("-","")+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");ds=h2;
                try(Connection c=ds.getConnection();var s=c.createStatement()){s.execute("CREATE TABLE public.users(id BIGINT PRIMARY KEY)");s.execute("INSERT INTO public.users VALUES(1),(2)");}
            }else ds=new NoDatabaseAccess();
            CWalletLinkStore store=new CWalletLinkStore(ds,identity,CLOCK);
            if(linked){store.installSchema();seedControlledLink(ds,identity,fixture.owner);}
            CWalletProofService proof=new CWalletProofService(identity,store,owner->fixture.reader().eoa(owner),CLOCK);
            api=new CCustodyController(fixture.reader(),proof);
        }
    }
    private static final class NoDatabaseAccess implements DataSource {
        public Connection getConnection(){throw new AssertionError("C_TEST_UNEXPECTED_DATABASE_ACCESS");}
        public Connection getConnection(String user,String password){return getConnection();}
        public java.io.PrintWriter getLogWriter(){return null;}public void setLogWriter(java.io.PrintWriter p){}public void setLoginTimeout(int n){}public int getLoginTimeout(){return 0;}
        public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getLogger("C_API_NO_DATABASE");}
        public <T>T unwrap(Class<T> type)throws java.sql.SQLException{throw new java.sql.SQLException("C_TEST_NOT_WRAPPED");}public boolean isWrapperFor(Class<?> type){return false;}
    }
    /** Controlled API lookup seed only: NOT proof of an actual wallet signature. Proof tests are separate. */
    private static void seedControlledLink(DataSource ds,CCustodyIdentity identity,String owner)throws Exception{
        String id=UUID.randomUUID().toString(),nonce="1".repeat(64);Instant issued=CLOCK.instant(),expires=issued.plusSeconds(120);
        String message=CWalletProofService.message(identity,id,1,owner,nonce,issued,expires),digest=CWalletLinkStore.digest(message);
        try(Connection c=ds.getConnection();var s=c.prepareStatement("INSERT INTO tms_c_identity.wallet_challenges(id,deployment_id,user_id,wallet,origin,message,message_hash,random_nonce,issued_at,expires_at,consumed) VALUES(?,?,?,?,?,?,?,?,?,?,TRUE)")){
            s.setString(1,id);s.setString(2,identity.deploymentId());s.setLong(3,1);s.setString(4,owner);s.setString(5,identity.origin());s.setString(6,message);s.setString(7,digest);s.setString(8,nonce);s.setTimestamp(9,Timestamp.from(issued));s.setTimestamp(10,Timestamp.from(expires));s.executeUpdate();
        }
        try(Connection c=ds.getConnection();var s=c.prepareStatement("INSERT INTO tms_c_identity.wallet_links(deployment_id,user_id,wallet,challenge_id,proof_hash,linked_at) VALUES(?,?,?,?,?,?)")){
            s.setString(1,identity.deploymentId());s.setLong(2,1);s.setString(3,owner);s.setString(4,id);s.setString(5,digest);s.setTimestamp(6,Timestamp.from(issued));s.executeUpdate();
        }
    }
}
