package com.pricetrack.exchange.futures.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.audit.FuturesAuditDeployment.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Explicit OFF by default. Uses existing runtime RPC clients; never creates keys, senders or workers. */
@Configuration
@ConditionalOnProperty(name={"app.futures.enabled","app.futures.runtime-enabled","app.futures.audit.authorized-enabled"},havingValue="true")
public class FuturesAuthorizedAuditConfig {
    public record TrustedTargets(List<FuturesAuthorizedAuditObserver.Context> contexts,Registry registry){public TrustedTargets{contexts=List.copyOf(contexts);}}
    @Bean TrustedTargets futuresAuthorizedAuditTargets(Environment env,FuturesChainIdentity legacy,FuturesRpcGateway legacyRpc,
            ObjectProvider<AuthorizedFuturesConfig.Runtime> v1,ObjectProvider<AuthorizedV2Config.V2Runtime> v2){
        var contexts=new ArrayList<FuturesAuthorizedAuditObserver.Context>();
        contexts.add(context(env,"app.futures.",legacy,legacyRpc));
        v1.ifAvailable(r->contexts.add(context(env,"app.futures.user-authorization.",r.auditIdentity(),r.auditRpc())));
        v2.ifAvailable(r->contexts.add(context(env,"app.futures.user-authorization-v2.",r.auditIdentity(),r.auditRpc())));
        return new TrustedTargets(contexts,new Registry(contexts.stream().map(FuturesAuthorizedAuditObserver.Context::target).toList()));
    }
    private static FuturesAuthorizedAuditObserver.Context context(Environment e,String prefix,FuturesChainIdentity chain,FuturesRpcGateway rpc){
        String controller=e.getRequiredProperty(prefix+(chain.authorized()?"controller-code-hash":"code-hash"));
        String rules=chain.authorized()?e.getRequiredProperty(prefix+"rules-code-hash"):controller;
        Route route=chain.authorized()?(chain.v2()?Route.B_V2:Route.B_V1):Route.LEGACY;
        var target=FuturesAuditDeployment.target(route,e.getRequiredProperty(prefix+"audit.execution-id"),chain,controller,rules,FuturesLedger.POLICY_HASH);
        return new FuturesAuthorizedAuditObserver.Context(target,rpc);
    }
    @Bean FuturesAuditDeploymentStore futuresAuditDeploymentStore(DataSource source,ObjectMapper mapper,TrustedTargets targets){return new FuturesAuditDeploymentStore(source,mapper,targets.registry());}
    @Bean FuturesAuthorizedAuditObserver futuresAuthorizedAuditObserver(FuturesAuditStore store,FuturesAuditDeploymentStore deployments,TrustedTargets targets){return new FuturesAuthorizedAuditObserver(store,deployments,targets.contexts());}
    @Bean(destroyMethod="close") FuturesAuthorizedAuditService futuresAuthorizedAuditService(FuturesAuthorizedAuditObserver observer,FuturesAuditDeploymentStore store,TrustedTargets targets){return new FuturesAuthorizedAuditService(observer,store,targets.registry());}
}
