package com.pricetrack.exchange.custody.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.pricetrack.exchange.custody.auth.CWalletLinkStore;
import com.pricetrack.exchange.custody.auth.CWalletProofService;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Bean-construction tests, not an HTTP/JWT/real RPC/real PostgreSQL acceptance. */
class CCustodyRuntimeConfigTest {
    @Test void absentFlagAndExplicitFalseCreateNoClientStoreOrDatabaseConnection(){
        for(String[] properties:List.of(new String[0],new String[]{"app.c-futures.enabled=false"})){
            CountingDataSource source=new CountingDataSource();
            new ApplicationContextRunner().withUserConfiguration(CCustodyRuntimeConfig.class)
                .withBean(DataSource.class,()->source).withPropertyValues(properties).run(context->{
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(CCustodyRpcClient.class).isEmpty());
                    assertTrue(context.getBeansOfType(CCustodyRpcReader.class).isEmpty());
                    assertTrue(context.getBeansOfType(CWalletLinkStore.class).isEmpty());
                    assertTrue(context.getBeansOfType(CWalletProofService.class).isEmpty());
                    assertEquals(0,source.connections.get());
                });
        }
    }
    @Test void enabledValidConfigurationConstructsOnlyKeylessBeansAndNeverInstallsSchema(){
        CountingDataSource source=new CountingDataSource();
        new ApplicationContextRunner().withUserConfiguration(CCustodyRuntimeConfig.class)
            .withBean(DataSource.class,()->source).withPropertyValues(properties()).run(context->{
                assertNull(context.getStartupFailure());
                assertEquals(CCustodyIdentityTest.sample(),context.getBean(CCustodyIdentity.class));
                assertNotNull(context.getBean(CCustodyRpcClient.class));assertNotNull(context.getBean(CCustodyRpcReader.class));
                assertNotNull(context.getBean(CWalletLinkStore.class));assertNotNull(context.getBean(CWalletProofService.class));
                // Even a schema existence query would fail this DataSource. No application method is called.
                assertEquals(0,source.connections.get());
            });
        assertEquals(0,source.connections.get());
    }
    @Test void invalidIdentityIsFailClosedWithoutConnectingToDatabase(){
        CountingDataSource source=new CountingDataSource();
        new ApplicationContextRunner().withUserConfiguration(CCustodyRuntimeConfig.class)
            .withBean(DataSource.class,()->source).withPropertyValues(properties())
            .withPropertyValues("app.c-futures.symbol-hash=not-a-hash").run(context->{
                assertNotNull(context.getStartupFailure());
                assertTrue(messages(context.getStartupFailure()).contains("C_CONFIG_IDENTITY_INVALID"));
                assertEquals(0,source.connections.get());
            });
    }
    @Test void unsafeRpcUriIsRejectedWithoutLeakingUriOrInstallingSchema(){
        CountingDataSource source=new CountingDataSource();
        new ApplicationContextRunner().withUserConfiguration(CCustodyRuntimeConfig.class)
            .withBean(DataSource.class,()->source).withPropertyValues(properties())
            .withPropertyValues("app.c-futures.rpc-uri=http://private-sentinel@anvil:8545").run(context->{
                assertNotNull(context.getStartupFailure());String messages=messages(context.getStartupFailure());
                assertTrue(messages.contains("C_CONFIG_RPC_INVALID"));assertFalse(messages.contains("private-sentinel"));
                assertEquals(0,source.connections.get());
            });
    }
    private static String[] properties(){
        var identity=CCustodyIdentityTest.sample();List<String> p=new ArrayList<>(List.of(
            "app.c-futures.enabled=true","app.c-futures.chain-id="+identity.chainId(),"app.c-futures.genesis-hash="+identity.genesisHash(),
            "app.c-futures.relayer="+identity.relayer(),"app.c-futures.oracle="+identity.oracle(),"app.c-futures.pause-admin="+identity.pauseAdmin(),
            "app.c-futures.symbol-hash="+identity.symbolHash(),"app.c-futures.policy-hash="+identity.policyHash(),
            "app.c-futures.origin="+identity.origin(),"app.c-futures.rpc-uri=http://anvil:8545"));
        for(var item:List.of(java.util.Map.entry("token",identity.token()),java.util.Map.entry("controller",identity.controller()),
                java.util.Map.entry("rules",identity.rules()),java.util.Map.entry("vault",identity.vault()))){
            p.add("app.c-futures."+item.getKey()+"="+item.getValue().address());p.add("app.c-futures."+item.getKey()+"-code-hash="+item.getValue().codeHash());
        }
        return p.toArray(String[]::new);
    }
    private static String messages(Throwable failure){StringBuilder out=new StringBuilder();for(Throwable t=failure;t!=null;t=t.getCause())out.append(t.getMessage()).append('\n');return out.toString();}
    private static final class CountingDataSource implements DataSource {
        final AtomicInteger connections=new AtomicInteger();
        public Connection getConnection()throws SQLException{connections.incrementAndGet();throw new SQLException("C_TEST_UNEXPECTED_DATABASE_ACCESS");}
        public Connection getConnection(String u,String p)throws SQLException{return getConnection();}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter writer){}public void setLoginTimeout(int n){}public int getLoginTimeout(){return 0;}
        public Logger getParentLogger(){return Logger.getLogger("C_TEST_NO_DATABASE");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("C_TEST_NOT_WRAPPED");}public boolean isWrapperFor(Class<?> type){return false;}
    }
}
