package com.pricetrack.exchange.futures.audit;

import com.pricetrack.exchange.futures.chain.FuturesChainIdentity;
import java.util.*;
import org.web3j.crypto.Hash;

/** Public, explicitly trusted runtime target. Never constructed from an ADMIN request address. */
public record FuturesAuditDeployment(String key, Route route, String executionId, FuturesChainIdentity chain,
        String controllerCodeHash, String rulesCodeHash, String policyHash) {
    public enum Route { LEGACY, B_V1, B_V2 }
    public FuturesAuditDeployment {
        Objects.requireNonNull(route); Objects.requireNonNull(chain);
        if(executionId==null || executionId.isBlank() || executionId.length()>200) throw invalid();
        controllerCodeHash=hash(controllerCodeHash);rulesCodeHash=hash(rulesCodeHash);policyHash=hash(policyHash);
        Route actual=chain.authorized()?(chain.v2()?Route.B_V2:Route.B_V1):Route.LEGACY;
        if(route!=actual || (route==Route.LEGACY && !controllerCodeHash.equals(rulesCodeHash)))throw invalid();
        String expected=identityKey(route,executionId,chain,controllerCodeHash,rulesCodeHash,policyHash);
        if(key==null)key=expected;
        else if(!key.equals(expected))throw invalid();
    }
    public static FuturesAuditDeployment target(Route route,String executionId,FuturesChainIdentity chain,
            String controllerCodeHash,String rulesCodeHash,String policyHash) {
        return new FuturesAuditDeployment(null,route,executionId,chain,controllerCodeHash,rulesCodeHash,policyHash);
    }
    public String priceDomainName(){return "TokenizedMarketFutures";}
    public String priceDomainVersion(){return "1";}
    public String userDomainName(){return chain.authorized()?"TokenizedMarketFuturesUserIntent":null;}
    public String userDomainVersion(){return chain.authorized()?chain.authorizationVersion():null;}
    private static String identityKey(Route route,String execution,FuturesChainIdentity c,String controller,String rules,String policy){
        var out=new StringBuilder();
        for(String value:List.of("TMS_FUTURES_AUDIT_DEPLOYMENT_V1",route.name(),execution,c.chainId().toString(),
                c.engine(),c.executor(),c.priceSigner(),c.priceContract(),c.authorizationVersion(),controller,rules,policy,
                "TokenizedMarketFutures","1",c.authorized()?"TokenizedMarketFuturesUserIntent":"",c.authorized()?c.authorizationVersion():""))
            out.append(value.length()).append(':').append(value);
        return Hash.sha3String(out.toString());
    }
    private static String hash(String value){
        if(value==null || !value.matches("0x[0-9a-fA-F]{64}") || value.equals("0x"+"0".repeat(64)))throw invalid();
        return value.toLowerCase(Locale.ROOT);
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_FUTURES_AUDIT_DEPLOYMENT");}

    /** Baseline.codeHash always identifies priceContract/rules, not the authorized controller. */
    public record Envelope(FuturesAuditDeployment deployment,FuturesAuditModels.Baseline baseline) {
        public Envelope {
            Objects.requireNonNull(deployment);Objects.requireNonNull(baseline);
            if(!deployment.chain().equals(baseline.chain()) || !deployment.executionId().equals(baseline.executionId())
                    || !deployment.rulesCodeHash().equals(baseline.codeHash()) || !deployment.policyHash().equals(baseline.policyHash())
                    || baseline.id()==null || baseline.id().isBlank() || baseline.block()==null || baseline.block().number()<0
                    || baseline.block().hash()==null || !baseline.block().hash().matches("0x[0-9a-fA-F]{64}")
                    || baseline.dbAt()==null || baseline.dbFingerprint()==null || baseline.dbFingerprint().isBlank()
                    || baseline.initialHouse()==null || !baseline.initialHouse().matches("0|[1-9][0-9]*") || baseline.actorId()<=0)
                throw new IllegalArgumentException("FUTURES_AUDIT_BASELINE_IDENTITY_CONFLICT");
        }
    }

    /** Allowlist supplied by trusted runtime configuration; includes historical targets explicitly. */
    public static final class Registry {
        private final Map<String,FuturesAuditDeployment> targets;
        public Registry(Collection<FuturesAuditDeployment> configured) {
            Objects.requireNonNull(configured);var keys=new LinkedHashMap<String,FuturesAuditDeployment>();var addresses=new HashSet<String>();
            for(var target:configured){
                Objects.requireNonNull(target);
                if(keys.putIfAbsent(target.key(),target)!=null)throw invalid();
                for(String address:target.chain().authorized()?List.of(target.chain().engine(),target.chain().rules()):List.of(target.chain().engine()))
                    if(!addresses.add(target.chain().chainId()+":"+address))throw invalid();
            }
            targets=Map.copyOf(keys);
        }
        public Collection<FuturesAuditDeployment> targets(){return targets.values();}
        public Optional<FuturesAuditDeployment> find(String key){return Optional.ofNullable(targets.get(key));}
        public void require(FuturesAuditDeployment target){
            if(target==null || !target.equals(targets.get(target.key())))throw new IllegalArgumentException("FUTURES_AUDIT_TARGET_NOT_REGISTERED");
        }
    }
}
