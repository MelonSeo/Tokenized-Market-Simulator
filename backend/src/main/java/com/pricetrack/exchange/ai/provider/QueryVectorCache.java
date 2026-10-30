package com.pricetrack.exchange.ai.provider;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Process-local immutable query embeddings only. Never stores Tool/DB/RPC facts or retrieval results. */
public final class QueryVectorCache implements EmbeddingProvider {
    private record Key(String model,String corpus,String question){}
    private record Entry(float[] vector,long expires){}
    private final EmbeddingProvider delegate;private final String model;private final Supplier<String> corpus;
    private final java.util.function.LongSupplier clock;
    private final Map<Key,Entry> entries=new LinkedHashMap<>(64,.75f,true);
    private volatile boolean enabled;
    public QueryVectorCache(EmbeddingProvider delegate,String model,Supplier<String> corpus){this(delegate,model,corpus,System::nanoTime);}
    QueryVectorCache(EmbeddingProvider delegate,String model,Supplier<String> corpus,java.util.function.LongSupplier clock){this.delegate=delegate;this.model=model;this.corpus=corpus;this.clock=clock;}
    public void enabled(boolean value){enabled=value;}
    public synchronized int size(){return entries.size();}
    /** Drops only immutable query vectors, never index/Tool/financial state. */
    public synchronized void clear(){entries.clear();}
    @Override public List<float[]> embed(List<String> input){return embed(input,Duration.ofSeconds(20));}
    @Override public List<float[]> embed(List<String> input,Duration timeout){
        var deadline=new CallDeadline(timeout);
        if(input.size()!=1)return delegate.embed(input,deadline.remaining());
        var key=new Key(model,corpus.get(),input.getFirst());long now=clock.getAsLong();
        if(enabled)synchronized(this){var e=entries.get(key);if(e!=null&&e.expires()>now){deadline.remaining();return List.of(e.vector().clone());}}
        var result=delegate.embed(input,deadline.remaining());float[] value=result.getFirst();
        com.pricetrack.exchange.ai.store.PgKnowledgeStore.vector(value);
        deadline.remaining();
        synchronized(this){entries.put(key,new Entry(value.clone(),clock.getAsLong()+Duration.ofMinutes(5).toNanos()));while(entries.size()>64)entries.remove(entries.keySet().iterator().next());}
        deadline.remaining();return result;
    }
}
