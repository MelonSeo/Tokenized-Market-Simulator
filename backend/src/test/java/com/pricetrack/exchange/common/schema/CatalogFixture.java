package com.pricetrack.exchange.common.schema;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.mock.env.MockEnvironment;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** JDBC transport fixture only. Actual stores/verifier run; no PostgreSQL compatibility claim. */
public final class CatalogFixture {
    public final List<String> sql = new ArrayList<>();
    public final Map<String,List<Object[]>> columns = new HashMap<>(), keys = new HashMap<>();
    public final Set<String> apiOwnedTables = new HashSet<>();
    public boolean roleSafe=true, configured=true, consistent=true;
    public long budgetRows=1;
    public String body=" BEGIN RAISE EXCEPTION 'append-only faucet evidence'; END ";
    public String triggerFunction="reject_faucet_mutation",triggerEnabled="O";
    public int triggerType=58;
    public boolean triggerPresent=true,indexesPresent=true;
    public String product="PostgreSQL";
    public CatalogFixture() {
        cols("faucet_grants","id|character varying(36)|true;user_id|bigint|true;amount|numeric(30,18)|true;created_at|timestamp without time zone|true");
        key("faucet_grants","p|id|-|");
        cols("faucet_policy_versions","version|character varying(64)|true;content_hash|character varying(64)|true;content|character varying(512)|true;created_at|timestamp without time zone|true");
        cols("faucet_account_usage","user_id|bigint|true;grant_count|bigint|true;last_day|date|false");
        cols("faucet_budget_usage","id|integer|true;active_version|character varying(64)|true;grant_count|bigint|true;spent|numeric(30,18)|true;initialized|boolean|true");
        cols("faucet_claims","grant_id|character varying(36)|true;user_id|bigint|true;key_fingerprint|character varying(64)|true;request_fingerprint|character varying(64)|true;policy_version|character varying(64)|true;received_amount|numeric(30,18)|true;balance|numeric(30,18)|true;granted_at|timestamp without time zone|true");
        cols("conditional_orders","id|character varying(36)|true;user_id|bigint|true;side|character varying(10)|true;input_amount|numeric(30,18)|true;target_price_e8|numeric(78,0)|true;state|character varying(30)|true;expires_at|timestamp with time zone|true;next_evaluation_at|timestamp with time zone|true;created_at|timestamp with time zone|true;updated_at|timestamp with time zone|true;execution_order_id|bigint|false;worker_owner|character varying(36)|false;reason_code|character varying(80)|false;request_key_hash|character varying(64)|true;request_hash|character varying(64)|true;version|bigint|true");
        key("faucet_policy_versions","p|version|-|");key("faucet_account_usage","p|user_id|-|");key("faucet_budget_usage","p|id|-|");
        key("faucet_claims","p|grant_id|-|","u|user_id,key_fingerprint|-|","f|grant_id|faucet_grants|id","f|policy_version|faucet_policy_versions|version");
        key("conditional_orders","p|id|-|","u|execution_order_id|-|","u|user_id,request_key_hash|-|","f|user_id|users|id","f|execution_order_id|orders|id");
    }
    private void cols(String table,String values) {
        List<Object[]> rows=new ArrayList<>();
        for(String value:values.split(";")) { var p=value.split("\\|");rows.add(new Object[]{p[0],p[1],Boolean.valueOf(p[2])}); }
        columns.put(table,rows);
    }
    private void key(String table,String... values) {
        List<Object[]> rows=new ArrayList<>();for(String value:values)rows.add(new Object[]{value});keys.put(table,rows);
    }
    public static MockEnvironment local() {
        var environment=new MockEnvironment().withProperty("spring.sql.init.mode","never")
                .withProperty("spring.jpa.hibernate.ddl-auto","none");
        environment.setActiveProfiles("local-read-gate");return environment;
    }
    public DataSource source() throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenAnswer(i->product);
        when(connection.createStatement()).thenAnswer(i->{var statement=mock(Statement.class);
            when(statement.executeQuery(anyString())).thenAnswer(call->result(call.getArgument(0),null));return statement;});
        when(connection.prepareStatement(anyString())).thenAnswer(i->{
            String text=i.getArgument(0);var parameters=new HashMap<Integer,Object>();var statement=mock(PreparedStatement.class);
            doAnswer(call->{parameters.put(call.getArgument(0),call.getArgument(1));return null;}).when(statement).setString(anyInt(),anyString());
            doAnswer(call->{parameters.put(call.getArgument(0),call.getArgument(1));return null;}).when(statement).setObject(anyInt(),any());
            when(statement.executeQuery()).thenAnswer(call->result(text,parameters.get(1) instanceof String value?value:null));return statement;});
        return source;
    }
    private ResultSet result(String text,String table) throws Exception {
        sql.add(text);if(!text.stripLeading().startsWith("SELECT"))throw new AssertionError("MUTATING SQL: "+text);
        List<Object[]> rows;
        if(text.stripLeading().startsWith("SELECT NOT rolsuper"))rows=one(roleSafe);
        else if(text.contains("pg_catalog.pg_attribute"))rows=apiOwnedTables.contains(table)?List.of():columns.getOrDefault(table,List.of());
        else if(text.contains("FROM pg_constraint"))rows=keys.getOrDefault(table,List.of());
        else if(text.contains("FROM pg_proc"))rows=body==null?List.of():one(body);
        else if(text.contains("FROM pg_trigger"))rows=triggerPresent?List.<Object[]>of(new Object[]{table+"_policy_immutable",triggerType,triggerEnabled,triggerFunction,"public",true,true}):List.of();
        else if(text.contains("FROM pg_index"))rows=indexesPresent?List.<Object[]>of(new Object[]{"idx_conditional_due|state,next_evaluation_at,created_at,id"},new Object[]{"idx_conditional_owner|user_id,created_at,id"}):List.of();
        else if(text.equals("SELECT count(*) FROM faucet_budget_usage"))rows=one(budgetRows);
        else if(text.contains("WHERE b.id=1 AND b.initialized=TRUE"))rows=one(configured?1L:0L);
        else if(text.contains("SELECT a.last_day"))rows=List.of();
        else if(text.contains("FROM faucet_budget_usage b WHERE b.id=1"))rows=one(consistent?1L:0L);
        else if(text.startsWith("SELECT count(*)"))rows=one(0L);
        else throw new AssertionError("UNEXPECTED SQL: "+text);
        var result=mock(ResultSet.class);var meta=mock(ResultSetMetaData.class);int[] index={-1};
        when(result.next()).thenAnswer(i->++index[0]<rows.size());when(result.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(rows.isEmpty()?1:rows.get(0).length);
        when(result.getString(anyInt())).thenAnswer(i->String.valueOf(rows.get(index[0])[(Integer)i.getArgument(0)-1]));
        when(result.getInt(anyInt())).thenAnswer(i->((Number)rows.get(index[0])[(Integer)i.getArgument(0)-1]).intValue());
        when(result.getLong(anyInt())).thenAnswer(i->((Number)rows.get(index[0])[(Integer)i.getArgument(0)-1]).longValue());
        when(result.getBoolean(anyInt())).thenAnswer(i->(Boolean)rows.get(index[0])[(Integer)i.getArgument(0)-1]);
        return result;
    }
    private static List<Object[]> one(Object value) { return List.<Object[]>of(new Object[]{value}); }
}
