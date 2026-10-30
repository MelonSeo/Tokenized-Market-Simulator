package com.pricetrack.exchange.ai.provider;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
class QueryVectorCacheTest {
    @Test void ttlExpiresAtBoundaryWithoutWallClockOrFinancialState(){
        var clock=new AtomicLong();var calls=new AtomicInteger();EmbeddingProvider provider=input->{calls.incrementAndGet();var v=new float[1536];v[0]=1;return List.of(v);};
        var cache=new QueryVectorCache(provider,"m",()->"corpus",clock::get);cache.enabled(true);cache.embed(List.of("q"));
        clock.set(Duration.ofMinutes(5).toNanos()-1);cache.embed(List.of("q"));assertThat(calls.get()).isEqualTo(1);
        clock.incrementAndGet();cache.embed(List.of("q"));assertThat(calls.get()).isEqualTo(2);
    }
    @Test void cacheDoesNotMixCorpusAndReturnsDefensiveCopies(){
        var calls=new AtomicInteger();var corpus=new AtomicReference<>("a");
        EmbeddingProvider provider=input->{calls.incrementAndGet();float[] v=new float[1536];v[0]=1;return List.of(v);};
        var cache=new QueryVectorCache(provider,"model",corpus::get);cache.embed(List.of("q"));cache.enabled(true);
        var cached=cache.embed(List.of("q"));cached.getFirst()[0]=0;
        assertThat(cache.embed(List.of("q")).getFirst()[0]).isEqualTo(1);assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(()->cache.embed(List.of("q"),Duration.ZERO)).hasMessage("AI_PROVIDER_UNAVAILABLE");
        corpus.set("b");cache.embed(List.of("q"));assertThat(calls.get()).isEqualTo(2);
        for(int n=0;n<70;n++)cache.embed(List.of("q"+n),Duration.ofSeconds(1));assertThat(cache.size()).isEqualTo(64);
        int beforeClear=calls.get();cache.clear();assertThat(cache.size()).isZero();cache.embed(List.of("q69"));assertThat(calls.get()).isEqualTo(beforeClear+1);
    }
}
