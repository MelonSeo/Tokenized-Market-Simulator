package com.pricetrack.exchange.futures.audit;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.chain.FuturesChainIdentity;
import com.pricetrack.exchange.futures.audit.FuturesAuditDeployment.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FuturesAuditDeploymentTest {
    static final String POLICY="0x"+"a".repeat(64), CODE="0x"+"b".repeat(64), RULES="0x"+"c".repeat(64);
    static String address(int n){return "0x"+Integer.toHexString(n).repeat(40);}
    static FuturesAuditDeployment target(Route route){
        boolean auth=route!=Route.LEGACY;
        var chain=new FuturesChainIdentity(BigInteger.valueOf(31337),address(1),address(2),address(3),auth?address(4):null,auth?(route==Route.B_V2?"2":"1"):"LEGACY");
        return FuturesAuditDeployment.target(route,"isolated-execution",chain,CODE,auth?RULES:CODE,POLICY);
    }
    static Envelope envelope(FuturesAuditDeployment target){
        return new Envelope(target,new FuturesAuditModels.Baseline("baseline",target.executionId(),target.chain(),target.rulesCodeHash(),POLICY,
                "10000000000000000000000000",new FuturesAuditModels.Block(1,"0x"+"d".repeat(64)),Instant.EPOCH,"fingerprint",1));
    }
    @Test void routesAndDomainsAreExplicitAndKeyIsRecomputed(){
        var legacy=target(Route.LEGACY);var v1=target(Route.B_V1);var v2=target(Route.B_V2);
        assertThat(Set.of(legacy.key(),v1.key(),v2.key())).hasSize(3);
        assertThat(legacy.userDomainVersion()).isNull();assertThat(v1.userDomainVersion()).isEqualTo("1");assertThat(v2.userDomainVersion()).isEqualTo("2");
        assertThat(v2.priceDomainVersion()).isEqualTo("1");
        assertThatThrownBy(()->new FuturesAuditDeployment("0x"+"f".repeat(64),v2.route(),v2.executionId(),v2.chain(),CODE,RULES,POLICY)).hasMessageContaining("INVALID");
        assertThatThrownBy(()->FuturesAuditDeployment.target(Route.B_V1,"x",v2.chain(),CODE,RULES,POLICY)).hasMessageContaining("INVALID");
    }
    @Test void registryRejectsDuplicateControllerAndCrossRoleAddressAcrossDifferentExecutionIds(){
        var first=target(Route.B_V2);
        var renamed=FuturesAuditDeployment.target(first.route(),"renamed-execution",first.chain(),CODE,RULES,POLICY);
        assertThatThrownBy(()->new Registry(List.of(first,renamed))).hasMessageContaining("INVALID");
        var crossedChain=new FuturesChainIdentity(BigInteger.valueOf(31337),address(4),address(2),address(3),address(5),"2");
        var crossed=FuturesAuditDeployment.target(Route.B_V2,"other",crossedChain,CODE,RULES,POLICY);
        assertThatThrownBy(()->new Registry(List.of(first,crossed))).hasMessageContaining("INVALID");
    }
    @Test void absentTargetIsNotSilentlyAdopted(){var registry=new Registry(List.of());assertThat(registry.find(target(Route.B_V2).key())).isEmpty();assertThatThrownBy(()->registry.require(target(Route.B_V2))).hasMessageContaining("NOT_REGISTERED");}
    @Test void envelopeMustBindRulesCodeHashAndEntireRuntimeIdentity()throws Exception {
        var target=target(Route.B_V2);var good=envelope(target);var b=good.baseline();
        assertThatThrownBy(()->new Envelope(target,new FuturesAuditModels.Baseline(b.id(),b.executionId(),b.chain(),CODE,b.policyHash(),b.initialHouse(),b.block(),b.dbAt(),b.dbFingerprint(),b.actorId()))).hasMessageContaining("IDENTITY_CONFLICT");
        var json=new ObjectMapper().findAndRegisterModules();assertThat(json.readValue(json.writeValueAsString(good),Envelope.class)).isEqualTo(good);
    }
}
