package com.pricetrack.exchange.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.util.*;

/** Free, live-run-owned database check. No provider/key/ledger access or embeddings. */
final class EvaluationDatabasePreflight {
    private static Connection connect(JsonNode meta,String password)throws SQLException {
        String name=meta.path("dbName").asText();int port=meta.path("dbPort").asInt();
        if(!name.matches("exchange_ai_eval_[0-9a-f]{32}")||!name.equals(meta.path("dbUser").asText())||port<49152||port>65535)
            throw new SQLException("TARGET_INVALID","08004");
        var options=new Properties();options.setProperty("user",name);options.setProperty("password",password);
        options.setProperty("connectTimeout","3");options.setProperty("socketTimeout","5");
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+port+"/"+name,options);
    }
    static Map<String,Object> prepare(JsonNode meta,String password)throws Exception {
        // Correct login and fresh DB ownership verified before any DDL.
        try(var c=connect(meta,password)) {
            if(!EvaluationIndexLease.claim(c,meta,true).equals("PREPARED"))throw new SQLException("LEASE_INVALID","55000");
            try(var s=c.createStatement()) {
                s.setQueryTimeout(5);s.execute("create extension if not exists vector");
                try(var r=s.executeQuery("select vector_dims('[1,0]'::vector)")){if(!r.next()||r.getInt(1)!=2)throw new SQLException("VECTOR_INVALID","55000");}
            }
            c.commit();
        }
        // SCRAM requirement is tested through the SAME relay path, never bypassed.
        try(var ignored=connect(meta,"offline-invalid-password-not-a-secret")) {
            throw new IllegalStateException("SCRAM_AUTHENTICATION_NOT_ENFORCED");
        }catch(SQLException expected){if(!"28P01".equals(expected.getSQLState()))throw expected;}
        try(var c=connect(meta,password)) {
            if(!EvaluationIndexLease.claim(c,meta,false).equals("PREPARED"))throw new SQLException("LEASE_INVALID","55000");
        }
        return Map.of("jdbc","PASS","scram","28P01","pgvector","PASS","lease","PREPARED_RESUME_VERIFIED","paidCalls",0);
    }
}
