package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** JDBC contract simulation only, NOT PostgreSQL/pgvector E2E. */
class EvaluationIndexLeaseTest {
    Connection c;Statement s;JsonNode meta;
    final String run="eval_"+"a".repeat(32),db="exchange_ai_eval_"+"a".repeat(32);
    @BeforeEach void setup()throws Exception {
        c=mock(Connection.class);s=mock(Statement.class);when(c.createStatement()).thenReturn(s);
        meta=new ObjectMapper().valueToTree(Map.of("runId",run,"dbName",db,"dbUser",db,"sourceHash","b".repeat(64),"corpusHash","c".repeat(64)));
        stub("select current_database(),current_user",db,db);
    }
    ResultSet row(Object... values)throws Exception {
        var r=mock(ResultSet.class);when(r.next()).thenReturn(true,false);
        for(int i=0;i<values.length;i++){
            int n=i+1;Object v=values[i];
            if(v instanceof String str)when(r.getString(n)).thenReturn(str);
            if(v instanceof Integer number)when(r.getInt(n)).thenReturn(number);
            if(v instanceof Boolean flag)when(r.getBoolean(n)).thenReturn(flag);
        }
        return r;
    }
    void stub(String sql,Object... values)throws Exception {
        ResultSet result=row(values);when(s.executeQuery(sql)).thenReturn(result);
    }
    void owner(String source,String state)throws Exception {
        stub("select current_database(),current_user",db,db);
        stub("select run_id,source_hash,corpus_hash,state from public.ai_evaluation_owner",run,source,"c".repeat(64),state);
    }
    @Test void freshEmptyOwnedDatabaseClaimsWithoutTouchingProvider()throws Exception {
        stub("select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')",0);
        when(c.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));owner("b".repeat(64),"PREPARED");
        assertThat(EvaluationIndexLease.claim(c,meta,true)).isEqualTo("PREPARED");verify(c).commit();verify(c,never()).rollback();
        verify(s).execute(startsWith("create table public.ai_evaluation_owner"));
    }
    @Test void wrongDatabaseStopsBeforeAnyDdl()throws Exception {
        stub("select current_database(),current_user","exchange_ai",db);
        assertThatThrownBy(()->EvaluationIndexLease.claim(c,meta,true)).hasMessage("AI_EVALUATION_TARGET_INVALID");
        verify(s,never()).execute(startsWith("create"));verify(c).rollback();
    }
    @Test void nonEmptyDatabaseCannotBeAdopted()throws Exception {
        stub("select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')",1);
        assertThatThrownBy(()->EvaluationIndexLease.claim(c,meta,true)).hasMessage("AI_EVALUATION_DATABASE_NOT_EMPTY");
        verify(s,never()).execute(startsWith("create"));
    }
    @Test void resumeRequiresSameSourceAndCompletedOrPreparedState()throws Exception {
        owner("d".repeat(64),"READY");
        assertThatThrownBy(()->EvaluationIndexLease.claim(c,meta,false)).hasMessage("AI_EVALUATION_INDEX_IDENTITY_INVALID");
        owner("b".repeat(64),"INDEXING");
        assertThatThrownBy(()->EvaluationIndexLease.claim(c,meta,false)).hasMessage("AI_EVALUATION_INDEX_INCOMPLETE");
        owner("b".repeat(64),"READY");assertThat(EvaluationIndexLease.claim(c,meta,false)).isEqualTo("READY");
    }
    @Test void indexingClaimMustCommitBeforeAnyEmbeddingAndRejectRace()throws Exception {
        when(s.executeUpdate(anyString())).thenReturn(1,0);
        EvaluationIndexLease.begin(c);verify(c).commit();
        assertThatThrownBy(()->EvaluationIndexLease.begin(c)).hasMessage("AI_EVALUATION_INDEX_INCOMPLETE");
    }
    void index(String fingerprint,int chunks)throws Exception {
        stub("select id,model,dimensions,active from ai.indexes",fingerprint,"text-embedding-3-small",1536,true);
        stub("select count(*) from ai.documents",9);
        stub("select count(*) from ai.chunks",chunks);
    }
    @Test void readyIndexMustMatchFingerprintModelDimensionsAndCounts()throws Exception {
        index("c".repeat(64),54);when(s.executeUpdate(anyString())).thenReturn(1);
        EvaluationIndexLease.verifyReady(c,meta,9,54,true);verify(c).commit();
        index("c".repeat(64),53);
        assertThatThrownBy(()->EvaluationIndexLease.verifyReady(c,meta,9,54,false)).hasMessage("AI_EVALUATION_INDEX_INCOMPLETE");
    }
    @Test void differentIndexCannotBeReusedOrSilentlyReindexed()throws Exception {
        index("e".repeat(64),54);
        assertThatThrownBy(()->EvaluationIndexLease.verifyReady(c,meta,9,54,false)).hasMessage("AI_EVALUATION_INDEX_INCOMPLETE");
        verify(s,never()).executeUpdate(contains("READY"));verify(c).rollback();
    }
}
