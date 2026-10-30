package com.pricetrack.exchange.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.observability.AiObservability;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;

class AiQueryCacheConfigurationTest {
    final AiProperties props=new AiProperties(true,"jdbc:postgresql://127.0.0.1:1/exchange_ai_unit","unit","unit",".","manifest","unit","https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(Dependencies.class)
        .withBean(AiProperties.class,()->props).withBean(ObjectMapper.class,ObjectMapper::new).withBean(AiObservability.class,AiObservability::new)
        .withPropertyValues("app.ai.enabled=true");}
    @Configuration
    static class Dependencies extends AiConfiguration {
        @Bean @Override PgKnowledgeStore aiKnowledgeStore(AiProperties p){return mock(PgKnowledgeStore.class);}
        @Bean @Override KnowledgeLoader aiKnowledgeLoader(AiProperties p,ObjectMapper j){return mock(KnowledgeLoader.class);}
        @Bean @Override OpenAiProvider aiProvider(AiProperties p,ObjectMapper j,AiObservability o){return mock(OpenAiProvider.class);}
    }
    @Test void measuredCacheDefaultIsWiredWithoutCallingProvider(){context().run(c->{
        assertThat(c).hasNotFailed();assertThat(ReflectionTestUtils.getField(c.getBean(AuthorizedKnowledgeRetrieval.class),"embedding")).isInstanceOf(QueryVectorCache.class);
        verifyNoInteractions(c.getBean(OpenAiProvider.class),c.getBean(PgKnowledgeStore.class));
    });}
    @Test void explicitOffKeepsUncachedProvider(){context().withPropertyValues("app.ai.query-vector-cache=false").run(c->{
        assertThat(c).hasNotFailed();assertThat(ReflectionTestUtils.getField(c.getBean(AuthorizedKnowledgeRetrieval.class),"embedding")).isSameAs(c.getBean(OpenAiProvider.class));
        verifyNoInteractions(c.getBean(OpenAiProvider.class),c.getBean(PgKnowledgeStore.class));
    });}
    @Test void aiDisabledStillCreatesNoAiResources(){new ApplicationContextRunner().withUserConfiguration(AiConfiguration.class).run(c->{
        assertThat(c).hasNotFailed().doesNotHaveBean(AuthorizedKnowledgeRetrieval.class).doesNotHaveBean(PgKnowledgeStore.class).doesNotHaveBean(OpenAiProvider.class);
    });}
}
