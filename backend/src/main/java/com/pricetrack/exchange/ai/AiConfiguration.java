package com.pricetrack.exchange.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** AI가 꺼지면 pool/provider도 생성하지 않는다. AI DB 초기화는 명시적 ingest에서만 수행한다. */
@Configuration
@ConditionalOnProperty(name="app.ai.enabled",havingValue="true")
public class AiConfiguration {
    @Bean com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval authorizedKnowledgeRetrieval(AiProperties p,
            KnowledgeLoader loader,PgKnowledgeStore store,OpenAiProvider provider,com.pricetrack.exchange.ai.observability.AiObservability observation,
            @org.springframework.beans.factory.annotation.Value("${app.ai.query-vector-cache:true}") boolean cacheEnabled){
        com.pricetrack.exchange.ai.provider.EmbeddingProvider embedding=provider;
        if(cacheEnabled){var cache=new com.pricetrack.exchange.ai.provider.QueryVectorCache(provider,p.embeddingModel(),()->loader.load().fingerprint());cache.enabled(true);embedding=cache;}
        return new com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval(p,loader,store,embedding).observe(observation);
    }
    @Bean com.pricetrack.exchange.ai.agent.AgentModelProvider agentModelProvider(OpenAiProvider provider,ObjectMapper json){
        return new com.pricetrack.exchange.ai.agent.OpenAiAgentProvider(provider,json);
    }
    @Bean(destroyMethod="close") PgKnowledgeStore aiKnowledgeStore(AiProperties p) {
        p.validate(); return new PgKnowledgeStore(p);
    }
    @Bean KnowledgeLoader aiKnowledgeLoader(AiProperties p,ObjectMapper json) { return new KnowledgeLoader(p,json); }
    @Bean(destroyMethod="close") com.pricetrack.exchange.ai.provider.PgProviderBudget providerBudget(AiProperties p,
        @org.springframework.beans.factory.annotation.Value("${app.ai.budget.approval:}") String approval,
        @org.springframework.beans.factory.annotation.Value("${app.ai.budget.total-nusd:0}") long total,
        @org.springframework.beans.factory.annotation.Value("${app.ai.budget.daily-nusd:0}") long daily,
        @org.springframework.beans.factory.annotation.Value("${app.ai.budget.llm-calls:0}") int llm,
        @org.springframework.beans.factory.annotation.Value("${app.ai.budget.embedding-calls:0}") int embedding,
        @org.springframework.beans.factory.annotation.Value("${app.ai.budget.actor-daily-requests:20}") int actorDaily,
        @org.springframework.beans.factory.annotation.Value("${app.ai.budget.actor-minute-requests:5}") int actorMinute){
        return new com.pricetrack.exchange.ai.provider.PgProviderBudget(p,new com.pricetrack.exchange.ai.provider.PgProviderBudget.Limits(approval,total,daily,llm,embedding,actorDaily,actorMinute),java.time.Clock.systemUTC());
    }
    @Bean OpenAiProvider aiProvider(AiProperties p,ObjectMapper json,com.pricetrack.exchange.ai.observability.AiObservability observation,com.pricetrack.exchange.ai.provider.PgProviderBudget budget) { return new OpenAiProvider(p,json).observe(observation).budget(budget); }
    @Bean RagService ragService(AiProperties p,KnowledgeLoader loader,PgKnowledgeStore store,OpenAiProvider provider,com.pricetrack.exchange.ai.observability.AiObservability observation) {
        return new RagService(p,loader,store,provider,provider).observe(observation);
    }
}
