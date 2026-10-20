package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** REAL PostgreSQL/pgvector through loopback exec relay. NO provider/ingest/ledger. */
@EnabledIfEnvironmentVariable(named="AI_DATABASE_PROBE",matches="true")
class EvaluationDatabaseProbeTest {
    final ObjectMapper json=new ObjectMapper();
    Path folder;JsonNode meta;Properties credentials;
    Connection connect(String password)throws Exception {
        var options=new Properties();options.setProperty("user",meta.path("dbUser").asText());options.setProperty("password",password);
        options.setProperty("connectTimeout","3");options.setProperty("socketTimeout","5");
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+meta.path("dbPort").asInt()+"/"+meta.path("dbName").asText(),options);
    }
    Connection connect()throws Exception {return connect(credentials.getProperty("POSTGRES_PASSWORD"));}
    void write(String name,Object value)throws Exception {Files.write(folder.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);}

    @Test void realJavaPgvectorLeaseLifecycleWithoutPaidTransport()throws Exception {
        folder=Path.of(Objects.requireNonNull(System.getenv("AI_DATABASE_PROBE_FOLDER"))).toRealPath();
        meta=json.readTree(folder.resolve("fixture.json").toFile());
        assertThat(meta.path("mode").asText()).isEqualTo("offline-database-probe");
        assertThat(meta.path("runId").asText()).matches("ai_db_probe_[0-9a-f]{32}");
        assertThat(meta.path("dbName").asText()).isEqualTo("exchange_ai_eval_"+meta.path("runId").asText().substring(12));
        assertThat(meta.path("dbUser").asText()).isEqualTo(meta.path("dbName").asText());
        assertThat(meta.path("dbPort").asInt()).isBetween(49152,65535);
        assertThat(Files.exists(folder.resolve("approval.json"))).isFalse();
        assertThat(Files.exists(folder.resolve("budget.sqlite"))).isFalse();
        credentials=new Properties();try(var input=Files.newInputStream(folder.resolve("db.env"))){credentials.load(input);}
        String phase="CONNECT";
        try {
            try(var c=connect()) {
                assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
                try(var s=c.createStatement();var r=s.executeQuery("select current_database(),current_user")) {
                    r.next();assertThat(r.getString(1)).isEqualTo(meta.path("dbName").asText());assertThat(r.getString(2)).isEqualTo(meta.path("dbUser").asText());
                }
                phase="LEASE_CLAIM";
                var wrong=meta.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrong).put("dbName","exchange_ai_wrong");
                assertThatThrownBy(()->EvaluationIndexLease.claim(c,wrong,true)).hasMessage("AI_EVALUATION_TARGET_INVALID");
                assertThat(EvaluationIndexLease.claim(c,meta,true)).isEqualTo("PREPARED");
            }
            try(var c=connect()){assertThat(EvaluationIndexLease.claim(c,meta,false)).isEqualTo("PREPARED");EvaluationIndexLease.begin(c);}
            try(var c=connect()){assertThatThrownBy(()->EvaluationIndexLease.claim(c,meta,false)).hasMessage("AI_EVALUATION_INDEX_INCOMPLETE");}
            phase="LOCAL_VECTOR_FIXTURE";
            var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:"+meta.path("dbPort").asInt()+"/"+meta.path("dbName").asText(),
                meta.path("dbUser").asText(),credentials.getProperty("POSTGRES_PASSWORD"),"../docs/ai-knowledge","../docs/ai/ingest-manifest.json",
                "","disabled:","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
            var corpus=new KnowledgeLoader(p,json).load();assertThat(corpus.fingerprint()).isEqualTo(meta.path("corpusHash").asText());
            var vectors=new HashMap<String,float[]>();
            for(var chunk:corpus.chunks()){var vector=new float[1536];vector[0]=1;vectors.put(chunk.id(),vector);}
            try(var store=new PgKnowledgeStore(p)){store.initialize();store.publish(corpus,vectors,p.embeddingModel());}
            phase="LEASE_READY";
            try(var c=connect()){EvaluationIndexLease.verifyReady(c,meta,corpus.documents().size(),corpus.chunks().size(),true);}
            // New connection, as after process restart. No ingest, provider, or lease reset.
            try(var c=connect()) {
                assertThat(EvaluationIndexLease.claim(c,meta,false)).isEqualTo("READY");
                EvaluationIndexLease.verifyReady(c,meta,9,88,false);
                var wrong=meta.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrong).put("sourceHash","0".repeat(64));
                assertThatThrownBy(()->EvaluationIndexLease.claim(c,wrong,false)).hasMessage("AI_EVALUATION_INDEX_IDENTITY_INVALID");
                var wrongCorpus=meta.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongCorpus).put("corpusHash","0".repeat(64));
                assertThatThrownBy(()->EvaluationIndexLease.claim(c,wrongCorpus,false)).hasMessage("AI_EVALUATION_INDEX_IDENTITY_INVALID");
                assertThatThrownBy(()->EvaluationIndexLease.claim(c,meta,true)).hasMessage("AI_EVALUATION_DATABASE_NOT_EMPTY");
                assertThatThrownBy(()->EvaluationIndexLease.verifyReady(c,meta,9,87,false)).hasMessage("AI_EVALUATION_INDEX_INCOMPLETE");
                try(var s=c.createStatement();var r=s.executeQuery("select extversion from pg_extension where extname='vector'")) {
                    assertThat(r.next()).isTrue();assertThat(r.getString(1)).isNotBlank();
                }
            }
            phase="SAFE_SQL_DIAGNOSTIC";
            try{connect("deliberately-wrong-offline-password").close();fail("Wrong password must fail");}
            catch(SQLException expected) {
                var safe=EvaluationFailureDiagnostic.safe(expected);assertThat(safe).containsEntry("sqlState","28P01");write("authentication-diagnostic.json",safe);
            }
            write("java-database-result.json",Map.of("status","PASS","documents",9,"chunks",88,"dimensions",1536,
                "embeddings","LOCAL_SYNTHETIC_VECTORS_NOT_REAL_EMBEDDINGS","lease","PREPARED_INDEXING_READY_RESUME_AND_MISMATCH_REJECTION",
                "paidCalls",0,"ledgerCreated",false));
        } catch(Exception | AssertionError failure) {
            write("java-database-failure.json",Map.of("phase",phase,"diagnostic",EvaluationFailureDiagnostic.safe(failure)));
            throw new IllegalStateException("DATABASE_PROBE_FAILED_SAFE");
        }
    }
}
