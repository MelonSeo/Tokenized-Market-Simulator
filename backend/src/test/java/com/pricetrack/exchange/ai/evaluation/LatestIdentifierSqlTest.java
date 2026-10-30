package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.retrieval.IdentifierQuery;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual existing owned test index, read-only SQL. No provider, new embeddings or paid ledger mutation. */
@EnabledIfEnvironmentVariable(named="AI_LATEST_SQL_TESTS",matches="true")
class LatestIdentifierSqlTest {
    @Test void exactIdentifiersAndAuthorizationOnRealApprovedIndex()throws Exception {
        var json=new ObjectMapper();Path run=Path.of(System.getenv("AI_LATEST_STUDY_RUN")).toRealPath();
        var m=json.readTree(run.resolve("run.json").toFile());
        assertThat(m.path("finalAcceptance").asBoolean()).isTrue();assertThat(Files.exists(run.resolve("STOP"))).isTrue();
        var password=new Properties();try(var in=Files.newInputStream(run.resolve("db.env"))){password.load(in);}
        String url="jdbc:postgresql://127.0.0.1:"+m.path("dbPort").asInt()+"/"+m.path("dbName").asText();
        var p=new AiProperties(true,url,m.path("dbUser").asText(),password.getProperty("POSTGRES_PASSWORD"),"../docs/ai-knowledge","../docs/ai/ingest-manifest.json","","disabled:","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        var corpus=new KnowledgeLoader(p,json).load();assertThat(corpus.fingerprint()).isEqualTo(m.path("corpusHash").asText());
        try(var c=DriverManager.getConnection(url,p.dbUser(),p.dbPassword())){
            assertThat(EvaluationIndexLease.claim(c,m,false)).isEqualTo("READY");EvaluationIndexLease.verifyReady(c,m,9,88,false);
        }
        var rows=new ArrayList<>();
        try(var store=new PgKnowledgeStore(p)){
            var vectors=store.cached(p.embeddingModel());assertThat(vectors).hasSize(88);
            // A corpus vector tests SQL extraction/filtering, NOT semantic quality for these user questions.
            var probe=vectors.values().iterator().next();
            var queries=json.readTree(Path.of("src/test/resources/ai/retrieval-supplement.json").toFile()).path("identifiers");
            for(var q:queries){
                var terms=IdentifierQuery.terms(q.path("question").asText());assertThat(terms).isNotEmpty();
                var hits=store.searchIdentifiers(corpus.fingerprint(),probe,terms,UserRole.ADMIN,null,Duration.ofSeconds(5));
                var expected=new HashSet<String>();q.path("expected").forEach(n->expected.add(n.asText()));
                assertThat(hits).anyMatch(h->expected.contains(h.path()));
                rows.add(Map.of("identifier",q.path("question").asText(),"expectedDocumentPresent",true,"matches",hits.size()));
            }
            var admin=store.searchIdentifiers(corpus.fingerprint(),probe,List.of("BlockchainTransactionSender"),UserRole.ADMIN,null,Duration.ofSeconds(5));
            var user=store.searchIdentifiers(corpus.fingerprint(),probe,List.of("BlockchainTransactionSender"),UserRole.USER,null,Duration.ofSeconds(5));
            assertThat(admin).isNotEmpty().anyMatch(h->h.role().equals("ADMIN"));assertThat(user).isEmpty();
            var support=store.searchIdentifiers(corpus.fingerprint(),probe,List.of("MinimumOutputNotMet"),UserRole.USER,Set.of("support"),Duration.ofSeconds(5));
            assertThat(support).isNotEmpty().allMatch(h->h.role().equals("USER")&&h.path().equals("error-and-status-guide.md"));
            assertThat(store.searchIdentifiers(corpus.fingerprint(),probe,List.of("MinimumOutputNotMet"),UserRole.USER,Set.of("market"),Duration.ofSeconds(5))).isEmpty();
            assertThat(store.active(corpus.fingerprint())).isTrue();
        }
        Files.writeString(run.resolve("identifier-sql-component.private.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
            "status","PASS","scope","SQL_IDENTIFIER_FILTER_COMPONENT_NOT_QUERY_RETRIEVAL_QUALITY","queries",rows,
            "adminCanaryBlockedForUser",true,"domainFiltering",true,"paidCalls",0,"queryEmbedding","EXISTING_CORPUS_VECTOR_NOT_USER_QUERY")),StandardOpenOption.CREATE_NEW);
    }
}
