package com.pricetrack.exchange.ai.retrieval;

import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.EmbeddingProvider;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Server principal -> SQL candidate role filter. No USER-to-ADMIN principal substitution. */
public class AuthorizedKnowledgeRetrieval {
    private final AiProperties p;
    private final KnowledgeLoader loader;
    private final KnowledgeStore store;
    private final EmbeddingProvider embedding;
    public enum Stage { CORPUS_LOAD, INDEX_ACTIVE, EMBEDDING_BUDGET, EMBEDDING, VECTOR_VALIDATION,
        CANDIDATE_BUDGET, CANDIDATE_SEARCH, FILTER_RESULTS, APPROVAL_VERIFY, DEADLINE_CHECK }
    /** Fixed metadata only: never question, vectors, documents, exception text, URLs or credentials. */
    public record Diagnostic(Stage stage, boolean success, String code, long elapsedMillis, long remainingMillis, long budgetMillis) {}
    private Consumer<Diagnostic> diagnostics = ignored -> {};
    public AuthorizedKnowledgeRetrieval diagnose(Consumer<Diagnostic> sink) { diagnostics=java.util.Objects.requireNonNull(sink);return this; }
    private static final Set<String> SAFE_CODES=Set.of("AI_INDEX_NOT_READY","AI_QUERY_TIMEOUT","AI_APPROVAL_MISMATCH",
        "AI_DATABASE_UNAVAILABLE","AI_ROLE_FILTER_INVALID","AI_DOMAIN_FILTER_INVALID","AI_DOMAIN_FILTER_UNAVAILABLE",
        "AI_VECTOR_INVALID","AI_PROVIDER_UNAVAILABLE","AI_PROVIDER_RESPONSE_INVALID","AI_MANIFEST_INVALID","AI_SOURCE_INVALID",
        "AI_KNOWLEDGE_INVALID","AI_EVALUATION_TRANSPORT_UNCERTAIN","AI_EVALUATION_GUARD_REJECTED","AI_EVALUATION_STOPPED");
    private <T>T step(Stage stage,long end,Duration budget,Supplier<T> work) {
        long start=System.nanoTime();boolean success=false;String code="OTHER_FAILURE";
        try { T result=work.get();success=true;code="OK";return result; }
        catch(RuntimeException e) { if(e instanceof AiFailure f && SAFE_CODES.contains(f.code()))code=f.code();throw e; }
        finally { try { diagnostics.accept(new Diagnostic(stage,success,code,Math.max(0,(System.nanoTime()-start)/1_000_000),
            Math.max(0,(end-System.nanoTime())/1_000_000),Math.max(0,budget.toMillis()))); } catch(RuntimeException ignored) { /* Preserve original result. */ } }
    }
    private com.pricetrack.exchange.ai.observability.AiObservability observation;
    public AuthorizedKnowledgeRetrieval observe(com.pricetrack.exchange.ai.observability.AiObservability value){observation=value;return this;}
    public AuthorizedKnowledgeRetrieval(AiProperties p, KnowledgeLoader loader, KnowledgeStore store, EmbeddingProvider embedding) {
        this.p=p; this.loader=loader; this.store=store; this.embedding=embedding;
    }
    public record Evidence(String indexVersion, List<KnowledgeHit> hits) {}
    public Evidence search(AuthenticatedUser principal, String question, Duration budget) {
        return search(principal,question,budget,true);
    }
    public Evidence searchScoped(AuthenticatedUser principal,String question,Duration budget,Set<String> domains) {
        if(domains==null || domains.isEmpty())throw new AiFailure("AI_DOMAIN_FILTER_INVALID");
        return search(principal,question,budget,true,Set.copyOf(domains));
    }
    /** Preserves the established Basic RAG provider/store contract for its ADMIN-only wrapper. */
    public Evidence searchLegacyAdmin(AuthenticatedUser principal,String question) {
        if(principal==null || principal.role()!=UserRole.ADMIN)throw new org.springframework.security.access.AccessDeniedException("ADMIN required");
        return search(principal,question,Duration.ofSeconds(p.timeoutSeconds()+5L),false);
    }
    private Evidence search(AuthenticatedUser principal,String question,Duration budget,boolean bounded) {
        return search(principal,question,budget,bounded,null);
    }
    private Evidence search(AuthenticatedUser principal,String question,Duration budget,boolean bounded,Set<String> domains) {
        long start=System.nanoTime();Evidence result=null;String code="OTHER_FAILURE";
        try{result=searchInternal(principal,question,budget,bounded,domains);code="OK";return result;}
        catch(RuntimeException e){code=e.getMessage();throw e;}
        finally{if(observation!=null)observation.record("RETRIEVAL","search",null,start,result!=null,code,result==null?0:result.hits().size(),null,null);}
    }
    private Evidence searchInternal(AuthenticatedUser principal,String question,Duration budget,boolean bounded,Set<String> requestedDomains) {
        if (principal == null || principal.userId() == null || principal.userId() <= 0 || principal.role() == null)
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        if (question == null || question.isBlank() || question.length() > 1000) throw new AiFailure("AI_INPUT_LIMIT");
        long end = System.nanoTime() + budget.toNanos();
        var corpus=step(Stage.CORPUS_LOAD,end,budget,loader::load);
        // C policy is opt-in by a server-selected C read route; never reinterpret legacy DB facts as Vault assets.
        final Set<String> domains=requestedDomains;
        final boolean legacyAssets=bounded&&domains==null&&corpus.documents().stream().anyMatch(d->d.metadata().get("domain").equals("custody"));
        step(Stage.INDEX_ACTIVE,end,budget,()->{if(!store.active(corpus.fingerprint()))throw new AiFailure("AI_INDEX_NOT_READY");return true;});
        if(bounded)step(Stage.EMBEDDING_BUDGET,end,budget,()->remaining(end));
        // Recompute immediately before calling dependencies: diagnostic I/O must not grant extra time.
        float[] vector=step(Stage.EMBEDDING,end,budget,()->(bounded?embedding.embed(List.of(question),remaining(end)):embedding.embed(List.of(question))).getFirst());
        step(Stage.VECTOR_VALIDATION,end,budget,()->PgKnowledgeStore.vector(vector));
        if(domains!=null || bounded)step(Stage.CANDIDATE_BUDGET,end,budget,()->remaining(end));
        var candidates=step(Stage.CANDIDATE_SEARCH,end,budget,()->domains!=null
                ? store.searchForScope(corpus.fingerprint(),vector,Math.max(40,p.topK()*8),p.minimumSimilarity(),principal.role(),domains,remaining(end))
                : legacyAssets?store.searchLegacyAssets(corpus.fingerprint(),vector,Math.max(40,p.topK()*8),p.minimumSimilarity(),principal.role(),remaining(end)):!bounded
                ? store.search(corpus.fingerprint(),vector,Math.max(40,p.topK()*8),p.minimumSimilarity())
                : store.searchForRole(corpus.fingerprint(),vector,Math.max(40,p.topK()*8),p.minimumSimilarity(),principal.role(),remaining(end)));
        var hits=step(Stage.FILTER_RESULTS,end,budget,()->{
        var ranked=new java.util.ArrayList<KnowledgeHit>();
        var identifiers=IdentifierQuery.terms(question);
        if(!identifiers.isEmpty())ranked.addAll(legacyAssets?store.searchLegacyIdentifiers(corpus.fingerprint(),vector,identifiers,principal.role(),remaining(end)):store.searchIdentifiers(corpus.fingerprint(),vector,identifiers,principal.role(),domains,remaining(end)));
        ranked.addAll(candidates);
        var selected=EvidenceSelector.select(ranked,p.topK(),Set.of("custody").equals(domains)?p.topK():2);
        if(legacyAssets&&selected.stream().anyMatch(h->corpus.documents().stream().anyMatch(d->d.path().equals(h.path())&&d.metadata().get("domain").equals("custody"))))throw new AiFailure("AI_DOMAIN_FILTER_INVALID");
        // Defense in depth; primary protection is SQL before top-K.
        if (principal.role() == UserRole.USER && selected.stream().anyMatch(h -> !"USER".equals(h.role())))
            throw new AiFailure("AI_ROLE_FILTER_INVALID");
        if(domains!=null && selected.stream().anyMatch(h -> corpus.documents().stream().noneMatch(d ->
                d.path().equals(h.path()) && domains.contains(d.metadata().get("domain")))))throw new AiFailure("AI_DOMAIN_FILTER_INVALID");
        return selected;});
        step(Stage.APPROVAL_VERIFY,end,budget,()->{verify(corpus.fingerprint());return true;});
        step(Stage.DEADLINE_CHECK,end,budget,()->remaining(end));
        return new Evidence(corpus.fingerprint(), hits);
    }
    public void verify(String version) {
        if (!loader.load().fingerprint().equals(version) || !store.active(version)) throw new AiFailure("AI_APPROVAL_MISMATCH");
    }
    private static Duration remaining(long end) {
        long value=end-System.nanoTime();
        if(value<=0 || Thread.currentThread().isInterrupted()) throw new AiFailure("AI_QUERY_TIMEOUT");
        return Duration.ofNanos(value);
    }
}
