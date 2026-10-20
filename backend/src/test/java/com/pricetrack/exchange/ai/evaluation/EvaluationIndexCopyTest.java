package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.store.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual new PostgreSQL only, no provider, no parent connection or lease mutation. */
@EnabledIfEnvironmentVariable(named="AI_INDEX_COPY_RUN",matches=".+")
class EvaluationIndexCopyTest {
    final ObjectMapper json=EvaluationJson.mapper();
    static final String EXPORT="""
        select json_build_object('indexes',(select json_agg(x order by id) from ai.indexes x),
          'documents',(select json_agg(x order by path) from ai.documents x),
          'chunks',(select json_agg(x order by id) from
          (select index_id,id,path,heading,content,embedding::text as embedding from ai.chunks) x))
        """;
    @Test void copyPreservedVectorsToNewImmutableOwnerAndVerifyActualPgvector()throws Exception {
        Path run=Path.of(System.getenv("AI_INDEX_COPY_RUN")).toRealPath();
        var m=json.readTree(run.resolve("run.json").toFile());
        var provenance=json.readTree(run.resolve("copy-provenance.json").toFile());
        byte[] raw=Files.readAllBytes(run.resolve("source-index.json"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)))
            .isEqualTo(provenance.path("sourceArtifactSha256").asText());
        var snapshot=json.readTree(raw);var originalOwner=snapshot.path("owner");
        assertThat(originalOwner.path("state").asText()).isEqualTo("READY");
        assertThat(originalOwner.path("run_id").asText()).isEqualTo(provenance.path("parentRunId").asText()).isNotEqualTo(m.path("runId").asText());
        assertThat(originalOwner.path("source_hash").asText()).isEqualTo(provenance.path("parentSourceHash").asText());
        assertThat(originalOwner.path("corpus_hash").asText()).isEqualTo(m.path("corpusHash").asText());
        var env=new Properties();try(var in=Files.newInputStream(run.resolve("db.env"))){env.load(in);}
        String db=m.path("dbName").asText();int port=m.path("dbPort").asInt();
        assertThat(db).matches("exchange_ai_eval_[0-9a-f]{32}");assertThat(db).isEqualTo(m.path("dbUser").asText());assertThat(port).isBetween(49152,65535);
        var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:"+port+"/"+db,db,env.getProperty("POSTGRES_PASSWORD"),
            "../docs/ai-knowledge","../docs/ai/ingest-manifest.json","","guarded:stdio/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        var corpus=new KnowledgeLoader(p,json).load();validateSnapshot(snapshot,corpus);
        var wrongPassword=new AiProperties(true,p.jdbcUrl(),p.dbUser(),"offline-invalid-password-not-a-secret",p.knowledgeRoot(),p.manifest(),"",p.apiBase(),p.embeddingModel(),p.chatModel(),1200,60,5,.25,20);
        try(var ignored=connect(wrongPassword)){throw new AssertionError("SCRAM_REQUIRED");}catch(SQLException expected){assertThat(expected.getSQLState()).isEqualTo("28P01");}
        var tampered=snapshot.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)tampered.path("documents").get(0)).put("minimum_role","WRONG");
        assertThatThrownBy(()->validateSnapshot(tampered,corpus)).isInstanceOf(AssertionError.class);
        var badVector=snapshot.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)badVector.path("chunks").get(0)).put("embedding","[1,0]");
        assertThatThrownBy(()->validateSnapshot(badVector,corpus)).hasMessage("AI_VECTOR_INVALID");
        try(var c=connect(p)){
            assertThat(EvaluationIndexLease.claim(c,m,true)).isEqualTo("PREPARED");
            EvaluationIndexLease.begin(c);
            try(var store=new PgKnowledgeStore(p)){store.initialize();}
            insert(c,"ai.indexes",List.of("id","model","dimensions","active","created_at"),snapshot.path("indexes"));
            insert(c,"ai.documents",List.of("index_id","path","source_hash","version","title","minimum_role","domain","type","updated_at"),snapshot.path("documents"));
            insert(c,"ai.chunks",List.of("index_id","id","path","heading","content","embedding"),snapshot.path("chunks"));
            try(var s=c.createStatement();var r=s.executeQuery("select count(*) from ai.chunks where vector_dims(embedding)=1536")){r.next();assertThat(r.getInt(1)).isEqualTo(88);}
            JsonNode copied;try(var s=c.createStatement();var r=s.executeQuery(EXPORT)){r.next();copied=json.readTree(r.getString(1));}
            // JDBC startup TimeZone follows JVM; compare timestamps as the same instant,
            // not UTC vs Asia/Seoul formatting. Vector/metadata bytes remain exact.
            for(var value:List.of(snapshot,copied))for(var index:value.path("indexes"))
                ((com.fasterxml.jackson.databind.node.ObjectNode)index).put("created_at",java.time.OffsetDateTime.parse(index.path("created_at").asText()).toInstant().toString());
            for(String table:List.of("indexes","documents","chunks"))assertThat(copied.path(table)).as(table).isEqualTo(snapshot.path(table));
            EvaluationIndexLease.verifyReady(c,m,corpus.documents().size(),corpus.chunks().size(),true);
            assertThat(EvaluationIndexLease.claim(c,m,false)).isEqualTo("READY");
            var wrong=m.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrong).put("sourceHash","0".repeat(64));
            assertThatThrownBy(()->EvaluationIndexLease.claim(c,wrong,false)).hasMessage("AI_EVALUATION_INDEX_IDENTITY_INVALID");
            // Actual SQL cosine search and USER/ADMIN filters, using existing vectors (no embedding call).
            float[] vector=parse(snapshot.path("chunks").get(0).path("embedding").asText());
            try(var store=new PgKnowledgeStore(p)){
                var hits=store.searchForRole(corpus.fingerprint(),vector,40,-1,com.pricetrack.exchange.user.UserRole.USER,java.time.Duration.ofSeconds(5));
                assertThat(hits).isNotEmpty().allMatch(h->h.role().equals("USER"));
                assertThat(store.cached("text-embedding-3-small")).hasSize(88);
            }
            String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(copied)));
            Files.writeString(run.resolve("index-copy-verification.json"),json.writeValueAsString(Map.of("status","PASS","documents",9,"chunks",88,"dimensions",1536,"dataSha256",checksum,
                "newOwner",m.path("runId").asText(),"parentArtifactSha256",provenance.path("sourceArtifactSha256").asText(),"providerCalls",0)),StandardOpenOption.CREATE_NEW);
        }
    }
    static Connection connect(AiProperties p)throws SQLException {
        var v=new Properties();v.setProperty("user",p.dbUser());v.setProperty("password",p.dbPassword());v.setProperty("connectTimeout","3");v.setProperty("socketTimeout","10");
        return DriverManager.getConnection(p.jdbcUrl(),v);
    }
    static float[] parse(String text){String[] parts=text.substring(1,text.length()-1).split(",");float[] v=new float[parts.length];for(int i=0;i<v.length;i++)v[i]=Float.parseFloat(parts[i]);PgKnowledgeStore.vector(v);return v;}
    static void validateSnapshot(JsonNode n,KnowledgeCorpus corpus){
        assertThat(n.path("indexes").size()).isEqualTo(1);var idx=n.path("indexes").get(0);
        assertThat(idx.path("id").asText()).isEqualTo(corpus.fingerprint());assertThat(idx.path("model").asText()).isEqualTo("text-embedding-3-small");assertThat(idx.path("dimensions").asInt()).isEqualTo(1536);assertThat(idx.path("active").asBoolean()).isTrue();
        assertThat(n.path("documents").size()).isEqualTo(corpus.documents().size());assertThat(n.path("chunks").size()).isEqualTo(corpus.chunks().size());
        var paths=new HashSet<String>();for(var d:n.path("documents")){
            assertThat(paths.add(d.path("path").asText())).isTrue();
            var expected=corpus.documents().stream().filter(x->x.path().equals(d.path("path").asText())).findFirst().orElseThrow();
            assertThat(d.path("index_id").asText()).isEqualTo(corpus.fingerprint());assertThat(d.path("source_hash").asText()).isEqualTo(expected.hash());
            for(String key:List.of("version","title","minimum_role","domain","type","updated_at"))assertThat(d.path(key).asText()).isEqualTo(expected.metadata().get(key));
        }
        var ids=new HashSet<String>();for(var ch:n.path("chunks")){
            assertThat(ids.add(ch.path("id").asText())).isTrue();var expected=corpus.chunks().stream().filter(x->x.id().equals(ch.path("id").asText())).findFirst().orElseThrow();
            assertThat(ch.path("index_id").asText()).isEqualTo(corpus.fingerprint());assertThat(ch.path("path").asText()).isEqualTo(expected.path());
            assertThat(ch.path("heading").asText()).isEqualTo(expected.heading());assertThat(ch.path("content").asText()).isEqualTo(expected.content());parse(ch.path("embedding").asText());
        }
    }
    static void insert(Connection c,String table,List<String> columns,JsonNode rows)throws SQLException {
        String values=String.join(",",columns.stream().map(x->x.equals("embedding")?"?::vector":x.equals("created_at")?"?::timestamptz":"?").toList());
        try(var s=c.prepareStatement("insert into "+table+"("+String.join(",",columns)+") values("+values+")")){
            for(var r:rows){for(int i=0;i<columns.size();i++){var v=r.path(columns.get(i));if(v.isBoolean())s.setBoolean(i+1,v.asBoolean());else if(v.isIntegralNumber())s.setInt(i+1,v.asInt());else s.setString(i+1,v.asText());}s.addBatch();}s.executeBatch();
        }
    }
}
