package com.pricetrack.exchange.futures.audit;

import static org.assertj.core.api.Assertions.*;
import static com.pricetrack.exchange.futures.audit.FuturesAuditDeploymentTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.audit.FuturesAuditDeployment.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** H2-only INSERT/read contract tests, not PostgreSQL trigger or canonical-chain acceptance. */
class FuturesAuditDeploymentStoreTest {
    JdbcTemplate jdbc;FuturesAuditDeploymentStore store;javax.sql.DataSource source;final FuturesAuditDeployment target=target(Route.B_V2);
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:audit-deployments-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        ds.setDriverClassName("org.h2.Driver");jdbc=new JdbcTemplate(ds);
        source=ds;
        jdbc.execute("CREATE TABLE futures_audit_baselines(id INTEGER PRIMARY KEY,payload TEXT)");
        jdbc.update("INSERT INTO futures_audit_baselines VALUES(1,'preserved-legacy')");
        new ResourceDatabasePopulator(new ClassPathResource("futures-audit-deployments-schema.sql")).execute(ds);
        store=new FuturesAuditDeploymentStore(ds,new ObjectMapper().findAndRegisterModules(),new Registry(List.of(target)));
    }
    @Test void absentBaselineStaysAbsentWithoutLegacyAdoption(){assertThat(store.find(target)).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_audit_deployments",Integer.class)).isZero();}
    @Test void insertAndIdenticalRepeatNeverMutateLegacyBaseline(){
        var e=envelope(target);assertThat(store.register(e)).isEqualTo(e);assertThat(store.register(e)).isEqualTo(e);
        assertThat(store.find(target)).contains(e);assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_audit_deployments",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT payload FROM futures_audit_baselines WHERE id=1",String.class)).isEqualTo("preserved-legacy");
    }
    @Test void conflictingBaselineCannotOverwritePreviouslyRegisteredCut(){
        var e=envelope(target);store.register(e);var b=e.baseline();
        var replacement=new Envelope(target,new FuturesAuditModels.Baseline("replacement",b.executionId(),b.chain(),b.codeHash(),b.policyHash(),b.initialHouse(),b.block(),b.dbAt(),b.dbFingerprint(),b.actorId()));
        assertThatThrownBy(()->store.register(replacement)).hasMessageContaining("ALREADY_FIXED");assertThat(store.find(target)).contains(e);
    }
    @Test void unknownRuntimeIdentityIsRejectedBeforeDatabaseRegistration(){
        var renamed=FuturesAuditDeployment.target(target.route(),"different-execution",target.chain(),CODE,RULES,POLICY);
        assertThatThrownBy(()->store.register(envelope(renamed))).hasMessageContaining("NOT_REGISTERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_audit_deployments",Integer.class)).isZero();
    }
    @Test void unreadablePersistedEvidenceIsUnavailableNotMatch(){
        store.register(envelope(target));jdbc.update("UPDATE futures_audit_deployments SET payload='not-json' WHERE deployment_key=?",target.key());
        assertThatThrownBy(()->store.find(target)).hasMessageContaining("UNAVAILABLE");
    }
    @Test void simultaneousIdenticalRegistrationKeepsOneImmutableCut()throws Exception {
        var e=envelope(target);var start=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=workers.submit(()->{start.await();return store.register(e);});
            var second=workers.submit(()->{start.await();return store.register(e);});start.countDown();
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(e);
            assertThat(second.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(e);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_audit_deployments",Integer.class)).isOne();
    }
    @Test void cancelledOrExpiredRequestCannotEnterRegistrationTransaction(){
        var cancelled=new FuturesAuditCancellation(java.util.concurrent.TimeUnit.SECONDS.toNanos(1));cancelled.cancel();
        assertThatThrownBy(()->cancelled.bound(()->store.register(envelope(target)))).hasMessageContaining("CANCELLED_OR_DEADLINE");
        var expired=new FuturesAuditCancellation(0);
        assertThatThrownBy(()->expired.bound(()->store.register(envelope(target)))).hasMessageContaining("CANCELLED_OR_DEADLINE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_audit_deployments",Integer.class)).isZero();
    }
    @Test void cancellationAfterActualInsertRollsBackBeforeTransactionCommit(){
        var cancellation=new FuturesAuditCancellation(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        var intercepted=new org.springframework.jdbc.datasource.DelegatingDataSource(source){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                var actual=super.getConnection();
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    Object result=invoke(method,actual,args);
                    if(method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO futures_audit_deployments")){
                        var statement=(java.sql.PreparedStatement)result;
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.PreparedStatement.class},(p,m,a)->{
                            Object applied=invoke(m,statement,a);if(m.getName().equals("executeUpdate"))cancellation.cancel();return applied;
                        });
                    }
                    return result;
                });
            }
        };
        var guarded=new FuturesAuditDeploymentStore(intercepted,new ObjectMapper().findAndRegisterModules(),new Registry(List.of(target)));
        assertThatThrownBy(()->cancellation.bound(()->guarded.register(envelope(target)))).hasMessageContaining("CANCELLED_OR_DEADLINE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_audit_deployments",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT payload FROM futures_audit_baselines WHERE id=1",String.class)).isEqualTo("preserved-legacy");
    }
    private static Object invoke(java.lang.reflect.Method method,Object target,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
    }
    @Test void cancellationInCommitPreparationIsCaughtByBeforeCommitFence(){
        var cancellation=new FuturesAuditCancellation(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        assertThatThrownBy(()->cancellation.bound(()->transaction.execute(status->{
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public int getOrder(){return Integer.MIN_VALUE;}
                @Override public void beforeCommit(boolean readOnly){cancellation.cancel();}
            });
            return store.register(envelope(target));
        }))).hasMessageContaining("CANCELLED_OR_DEADLINE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_audit_deployments",Integer.class)).isZero();
    }
}
