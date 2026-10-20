package com.pricetrack.exchange.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.ai.AiFailure;
import java.sql.*;

/** No generic DB/schema reset. A random run-owned database must already exist. */
final class EvaluationIndexLease {
    static String claim(Connection c,JsonNode m,boolean create)throws SQLException {
        c.setAutoCommit(false);
        try {
            try(var target=query(c,"select current_database(),current_user")){
                if(!target.next()||!target.getString(1).equals(m.path("dbName").asText())
                        ||!target.getString(2).equals(m.path("dbUser").asText()))throw new AiFailure("AI_EVALUATION_TARGET_INVALID");
            }
            // Serialize competing claim/begin/finish attempts in this database.
            try(var s=c.createStatement()){s.setQueryTimeout(5);s.execute("select pg_advisory_xact_lock(502099)");}
            if(create){
                try(var objects=query(c,"select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')")){
                    objects.next();if(objects.getInt(1)!=0)throw new AiFailure("AI_EVALUATION_DATABASE_NOT_EMPTY");
                }
                try(var s=c.createStatement()){
                    s.execute("create table public.ai_evaluation_owner(run_id text primary key, source_hash text not null, corpus_hash text not null, state text not null)");
                }
                try(var s=c.prepareStatement("insert into public.ai_evaluation_owner values(?,?,?,'PREPARED')")){
                    s.setString(1,m.path("runId").asText());s.setString(2,m.path("sourceHash").asText());s.setString(3,m.path("corpusHash").asText());s.executeUpdate();
                }
            }
            String state;
            try(var row=query(c,"select run_id,source_hash,corpus_hash,state from public.ai_evaluation_owner")){
                if(!row.next()||!row.getString(1).equals(m.path("runId").asText())
                        ||!row.getString(2).equals(m.path("sourceHash").asText())
                        ||!row.getString(3).equals(m.path("corpusHash").asText()))throw new AiFailure("AI_EVALUATION_INDEX_IDENTITY_INVALID");
                state=row.getString(4);
                if(row.next()||!java.util.Set.of("PREPARED","READY").contains(state))throw new AiFailure("AI_EVALUATION_INDEX_INCOMPLETE");
            }
            c.commit();return state;
        }catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
    }
    static void begin(Connection c)throws SQLException {
        try(var s=c.createStatement()){
            s.setQueryTimeout(5);
            if(s.executeUpdate("update public.ai_evaluation_owner set state='INDEXING' where state='PREPARED'")!=1)
                throw new AiFailure("AI_EVALUATION_INDEX_INCOMPLETE");
        }
        c.commit();
    }
    static void verifyReady(Connection c,JsonNode m,int documents,int chunks,boolean finish)throws SQLException {
        c.setAutoCommit(false);
        try{
            try(var row=query(c,"select id,model,dimensions,active from ai.indexes")){
                if(!row.next()||!row.getString(1).equals(m.path("corpusHash").asText())||!row.getString(2).equals("text-embedding-3-small")
                        ||row.getInt(3)!=1536||!row.getBoolean(4)||row.next())throw new AiFailure("AI_EVALUATION_INDEX_INCOMPLETE");
            }
            for(var count:java.util.Map.of("ai.documents",documents,"ai.chunks",chunks).entrySet()){
                try(var row=query(c,"select count(*) from "+count.getKey())){
                    row.next();if(row.getInt(1)!=count.getValue())throw new AiFailure("AI_EVALUATION_INDEX_INCOMPLETE");
                }
            }
            if(finish)try(var s=c.createStatement()){
                if(s.executeUpdate("update public.ai_evaluation_owner set state='READY' where state='INDEXING'")!=1)
                    throw new AiFailure("AI_EVALUATION_INDEX_INCOMPLETE");
            }
            c.commit();
        }catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
    }
    private static ResultSet query(Connection c,String sql)throws SQLException {
        var s=c.createStatement();s.closeOnCompletion();s.setQueryTimeout(5);return s.executeQuery(sql);
    }
}
