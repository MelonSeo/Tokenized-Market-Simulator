package com.pricetrack.exchange.ai;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.evaluation.GuardedAiEvaluationRunner;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.retrieval.*;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Same real vectors/DB/corpus across retrieval alternatives; no extra LLM calls. */
public final class LatestRetrievalBenchmark {
    public static void run(GuardedAiEvaluationRunner runner,String password,Path folder,ObjectMapper json)throws Exception {
        var old=runner.properties();
        var p=new AiProperties(true,old.jdbcUrl(),old.dbUser(),password,old.knowledgeRoot(),old.manifest(),"",old.apiBase(),old.embeddingModel(),old.chatModel(),1200,60,5,.25,20);
        var loader=new KnowledgeLoader(p,json);var corpus=loader.load();
        var queries=new ArrayList<Map<String,Object>>();
        var golden=json.readTree(Path.of("src/test/resources/ai/retrieval-golden.json").toFile());
        var supplement=json.readTree(Path.of("src/test/resources/ai/retrieval-supplement.json").toFile());
        for(String group:List.of("golden","identifiers","negative"))for(var n:group.equals("golden")?golden:supplement.path(group)){
            var expected=new ArrayList<String>();n.path("expected").forEach(v->expected.add(v.asText()));
            queries.add(Map.of("group",group,"question",n.isTextual()?n.asText():n.path("question").asText(),"expected",expected));
        }
        for(String q:List.of("MinimumOutputNotMet 오류가 의미하는 바를 알려주세요","QuoteAlreadyUsed가 나오면 재사용 가능한가요?","서명 견적은 언제 만료되나요?","마진콜 유예 중 회복되면 어떻게 되나요?"))
            queries.add(Map.of("group","additional","question",q,"expected",q.contains("NotMet")||q.contains("AlreadyUsed")?List.of("error-and-status-guide.md"):q.contains("견적")?List.of("signed-quote-policy.md"):List.of("onchain-settlement-policy.md")));
        var vectors=new ArrayList<float[]>();
        for(int i=0;i<queries.size();i+=16)vectors.addAll(runner.provider().embed(queries.subList(i,Math.min(i+16,queries.size())).stream().map(n->(String)n.get("question")).toList(),Duration.ofSeconds(25)));
        var results=new ArrayList<>();
        try(var store=new PgKnowledgeStore(p)){
            var adminHits=store.searchIdentifiers(corpus.fingerprint(),vectors.getFirst(),List.of("BlockchainTransactionSender"),UserRole.ADMIN,null,Duration.ofSeconds(5));
            var userHits=store.searchIdentifiers(corpus.fingerprint(),vectors.getFirst(),List.of("BlockchainTransactionSender"),UserRole.USER,null,Duration.ofSeconds(5));
            if(adminHits.stream().noneMatch(h->h.role().equals("ADMIN"))||userHits.stream().anyMatch(h->!h.role().equals("USER")))throw new IllegalStateException("IDENTIFIER_ROLE_CANARY_FAILED");
            Files.writeString(folder.resolve("identifier-authorization.private.json"),json.writeValueAsString(Map.of("adminPrivateMatches",adminHits.stream().filter(h->h.role().equals("ADMIN")).count(),"userPrivateMatches",0)));
            for(int i=0;i<queries.size();i++){
                var q=queries.get(i);String text=(String)q.get("question");var v=vectors.get(i);
                var all=store.searchForRole(corpus.fingerprint(),v,500,-1,UserRole.ADMIN,Duration.ofSeconds(5));
                var semantic=all.stream().filter(h->h.similarity()>=.25).limit(40).toList();
                var exact=new ArrayList<KnowledgeHit>();var terms=IdentifierQuery.terms(text);
                if(!terms.isEmpty())exact.addAll(store.searchIdentifiers(corpus.fingerprint(),v,terms,UserRole.ADMIN,null,Duration.ofSeconds(5)));
                exact.addAll(semantic);
                var variants=new LinkedHashMap<String,List<KnowledgeHit>>();variants.put("semantic",semantic);variants.put("selectiveIdentifier",exact);
                variants.put("globalHybrid",KeywordExperiment.fuse(p,corpus.fingerprint(),text,all,.25));
                variants.put("threshold035",all.stream().filter(h->h.similarity()>=.35).limit(40).toList());
                for(var variant:variants.entrySet()){
                    var hits=EvidenceSelector.select(variant.getValue(),5,2);int rank=0;
                    for(int k=0;k<hits.size();k++)if(((List<?>)q.get("expected")).contains(hits.get(k).path())){rank=k+1;break;}
                    results.add(Map.of("query",q,"variant",variant.getKey(),"rank",rank,"hits",hits));
                }
                // Actual SQL role/domain filtering before rank for the new identifier boundary.
                if(!terms.isEmpty()){
                    var scoped=store.searchIdentifiers(corpus.fingerprint(),v,terms,UserRole.USER,Set.of("support"),Duration.ofSeconds(5));
                    if(scoped.stream().anyMatch(h->!h.role().equals("USER")||corpus.documents().stream().noneMatch(d->d.path().equals(h.path())&&d.metadata().get("domain").equals("support"))))throw new IllegalStateException("IDENTIFIER_AUTHORIZATION_FAILED");
                }
                Files.writeString(folder.resolve("retrieval-comparison.private.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
            }
        }
    }
}
