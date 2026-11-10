package com.pricetrack.exchange.ai.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.OpenAiAgentProvider;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import java.time.Duration;
import java.util.*;

/** Test-classpath wiring, one shared PG authority, two fresh knowledge stores, same Agent/Tool implementation. */
public final class PilotV2Runtime implements AutoCloseable {
    private record Bundle(AiProperties p,KnowledgeLoader loader,PgKnowledgeStore store,OpenAiProvider provider,RagService rag,AuthorizedKnowledgeRetrieval retrieval){}
    private final Map<String,Bundle> bundles=new HashMap<>();
    private final PilotV2Channel channel;
    private final PgProviderBudget budget;
    private final ObjectMapper json;
    private OpenAiProvider dynamicProvider;
    public PilotV2Runtime(Map<String,AiProperties> knowledge,PgProviderBudget sharedBudget,PilotV2Channel channel,ObjectMapper json){
        if(!knowledge.keySet().equals(Set.of("legacy9","pilot10"))||knowledge.get("legacy9").jdbcUrl().equals(knowledge.get("pilot10").jdbcUrl()))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
        this.channel=Objects.requireNonNull(channel);this.budget=Objects.requireNonNull(sharedBudget);this.json=Objects.requireNonNull(json);
        try{for(var entry:knowledge.entrySet()){
            String id=entry.getKey();var p=entry.getValue();p.validate();
            if(!p.apiKey().isEmpty()||!p.apiBase().equals("guarded:pilot-v2/")||!p.jdbcUrl().matches("jdbc:postgresql://[^/]+/exchange_ai_pilot_[a-z0-9_]+(?:\\?.*)?"))throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");
            var loader=new KnowledgeLoader(p,json);var corpus=loader.load();
            if(corpus.documents().size()!=(id.equals("legacy9")?9:10)||corpus.chunks().size()!=(id.equals("legacy9")?88:93))throw new AiFailure("AI_EVALUATION_CORPUS_MISMATCH");
            var store=new PilotV2RetrievalTrace(p,json);
            try{var provider=channel.provider(p,budget,id);
            var rag=new RagService(p,loader,store,provider,provider);
            // Query vector cache may reduce actual calls; quota never assumes a hit or reuses old corpora's index.
            var cache=new QueryVectorCache(provider,p.embeddingModel(),()->loader.load().fingerprint());cache.enabled(true);
            var retrieval=new AuthorizedKnowledgeRetrieval(p,loader,store,cache);
            bundles.put(id,new Bundle(p,loader,store,provider,rag,retrieval));
            }catch(RuntimeException failure){store.close();throw failure;}
        }dynamicProvider=channel.provider(bundles.get("legacy9").p(),budget,"ACTIVE");}catch(RuntimeException e){close();throw e;}
    }
    private Bundle current(){var b=bundles.get(channel.corpus());if(b==null)throw new AiFailure("AI_EVALUATION_CONFIG_INVALID");return b;}
    public PgProviderBudget sharedBudget(){return budget;}
    public OpenAiAgentProvider model(ObjectMapper json){
        // Models and wire format are identical; corpus is selected only by the private coordinator.
        return new OpenAiAgentProvider(dynamicProvider,json);
    }
    public PilotV2Runtime observe(com.pricetrack.exchange.ai.observability.AiObservability value){
        dynamicProvider.observe(value);bundles.values().forEach(b->{b.provider().observe(value);b.rag().observe(value);b.retrieval().observe(value);});return this;
    }
    public AuthorizedKnowledgeRetrieval retrieval(){
        return new AuthorizedKnowledgeRetrieval(null,null,null,null){
            private Evidence logged(AuthenticatedUser user,String q,Duration remaining,Set<String> domains){
                var b=current();long start=System.nanoTime();Evidence found=null;String code="ERROR";
                try{found=domains==null?b.retrieval().search(user,q,remaining):b.retrieval().searchScoped(user,q,remaining,domains);code="OK";return found;}
                catch(AiFailure e){code=e.code();throw e;}
                finally{var data=new LinkedHashMap<String,Object>();data.put("question",q);data.put("role",user==null?null:user.role());data.put("domains",domains);data.put("topK",b.p().topK());data.put("minimumSimilarity",b.p().minimumSimilarity());data.put("perDocument",Set.of("custody").equals(domains)?b.p().topK():2);data.put("code",code);data.put("elapsedNs",System.nanoTime()-start);data.put("index",found==null?null:found.indexVersion());data.put("selected",found==null?List.of():found.hits());PilotV2RetrievalTrace.write(json,"FINAL",data);}
            }
            @Override public Evidence search(AuthenticatedUser user,String question,Duration remaining){return logged(user,question,remaining,null);}
            @Override public Evidence searchScoped(AuthenticatedUser user,String question,Duration remaining,Set<String> domains){return logged(user,question,remaining,domains);}
            @Override public void verify(String version){current().retrieval().verify(version);}
        };
    }
    /** Existing authenticated ADMIN index controller may delegate here; no public phase/corpus parameter. */
    public RagService rag(){
        return new RagService(null,null,null,null,null){
            @Override public IndexResult ingest(AuthenticatedUser user){
                String corpus=channel.corpus();
                channel.control(Map.of("op","admit","actor",user.userId(),"body",Map.of("indexCorpus",corpus),"corpus",corpus));
                var result=current().rag().ingest(user); // original ADMIN check, actual PG publish and original embedding batches
                channel.control(Map.of("op","index-exit","actor",user.userId()));
                return result;
            }
            @Override public List<com.pricetrack.exchange.ai.store.KnowledgeHit> search(AuthenticatedUser user,String q){throw new AiFailure("AI_EVALUATION_ENDPOINT_NOT_ALLOWED");}
            @Override public Answer answer(AuthenticatedUser user,String q){throw new AiFailure("AI_EVALUATION_ENDPOINT_NOT_ALLOWED");}
        };
    }
    @Override public void close(){bundles.values().forEach(b->b.store().close());bundles.clear();}
}
