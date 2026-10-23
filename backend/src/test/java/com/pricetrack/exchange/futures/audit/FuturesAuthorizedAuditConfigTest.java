package com.pricetrack.exchange.futures.audit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.chain.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class FuturesAuthorizedAuditConfigTest {
    @Test void defaultOffDoesNotCreateTargetsStoreOrObserver(){new ApplicationContextRunner().withUserConfiguration(FuturesAuthorizedAuditConfig.class).run(c->{assertThat(c).doesNotHaveBean(FuturesAuthorizedAuditService.class);assertThat(c).doesNotHaveBean(FuturesAuditDeploymentStore.class);});}
    @Test void explicitExecutionIdRequiredNoReserveFallback(){
        var identity=FuturesAuditDeploymentTest.target(FuturesAuditDeployment.Route.LEGACY).chain();var beans=new DefaultListableBeanFactory();
        var env=new MockEnvironment().withProperty("app.futures.code-hash",FuturesAuditDeploymentTest.CODE).withProperty("app.reserve.execution-id","not-authorized-fallback");
        assertThatThrownBy(()->new FuturesAuthorizedAuditConfig().futuresAuthorizedAuditTargets(env,identity,mock(FuturesRpcGateway.class),beans.getBeanProvider(AuthorizedFuturesConfig.Runtime.class),beans.getBeanProvider(AuthorizedV2Config.V2Runtime.class))).isInstanceOf(IllegalStateException.class);
    }
    @Test void currentLegacyAndEnabledV2KeepSeparateCodeHashesAndRuntimeRpc(){
        var legacy=FuturesAuditDeploymentTest.target(FuturesAuditDeployment.Route.LEGACY).chain();
        var v2=new FuturesChainIdentity(legacy.chainId(),FuturesAuditDeploymentTest.address(5),FuturesAuditDeploymentTest.address(6),FuturesAuditDeploymentTest.address(7),FuturesAuditDeploymentTest.address(8),"2");
        var oldRpc=mock(FuturesRpcGateway.class);var newRpc=mock(FuturesRpcGateway.class);var runtime=mock(AuthorizedV2Config.V2Runtime.class);
        when(runtime.auditIdentity()).thenReturn(v2);when(runtime.auditRpc()).thenReturn(newRpc);
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("v2",runtime);
        var env=new MockEnvironment().withProperty("app.futures.code-hash",FuturesAuditDeploymentTest.CODE).withProperty("app.futures.audit.execution-id","legacy-explicit")
                .withProperty("app.futures.user-authorization-v2.controller-code-hash",FuturesAuditDeploymentTest.CODE).withProperty("app.futures.user-authorization-v2.rules-code-hash",FuturesAuditDeploymentTest.RULES)
                .withProperty("app.futures.user-authorization-v2.audit.execution-id","v2-explicit");
        var targets=new FuturesAuthorizedAuditConfig().futuresAuthorizedAuditTargets(env,legacy,oldRpc,beans.getBeanProvider(AuthorizedFuturesConfig.Runtime.class),beans.getBeanProvider(AuthorizedV2Config.V2Runtime.class));
        assertThat(targets.contexts()).hasSize(2);assertThat(targets.contexts().stream().map(FuturesAuthorizedAuditObserver.Context::rpc).toList()).containsExactly(oldRpc,newRpc);
        assertThat(targets.contexts().get(1).target().rulesCodeHash()).isEqualTo(FuturesAuditDeploymentTest.RULES);verifyNoInteractions(oldRpc,newRpc);
    }
}
