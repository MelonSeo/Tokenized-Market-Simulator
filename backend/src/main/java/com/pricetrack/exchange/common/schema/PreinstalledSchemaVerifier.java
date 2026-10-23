package com.pricetrack.exchange.common.schema;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixed PostgreSQL read checks for the two startup installers. No DDL or repair path. */
public final class PreinstalledSchemaVerifier {
    private PreinstalledSchemaVerifier() {}
    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("PREINSTALLED_SCHEMA_MISSING_OR_CONFLICTING");
    }
    public static void role(JdbcTemplate jdbc) {
        require(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
              AND NOT has_schema_privilege(current_user,'public','CREATE')
              AND NOT has_database_privilege(current_user,current_database(),'CREATE')
            FROM pg_roles WHERE rolname=current_user
            """, Boolean.class)));
    }
    private static void columns(JdbcTemplate jdbc, String table, String... expected) {
        var actual = jdbc.query("""
            SELECT a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
            JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='public' AND c.relname=? AND c.relkind='r'
              AND c.relowner<>(SELECT oid FROM pg_roles WHERE rolname=current_user)
              AND a.attnum>0 AND NOT a.attisdropped
            """, (r,n) -> r.getString(1)+"|"+r.getString(2)+"|"+r.getBoolean(3), table);
        require(actual.size()==expected.length && new HashSet<>(actual).equals(Set.of(expected)));
    }
    private static void keys(JdbcTemplate jdbc, String table, String... expected) {
        var actual = jdbc.query("""
            SELECT c.contype::text||'|'||array_to_string(ARRAY(
              SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(id,ord)
              JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.id ORDER BY k.ord),',')
              ||'|'||COALESCE(c.confrelid::regclass::text,'')||'|'||COALESCE(array_to_string(ARRAY(
              SELECT a.attname FROM unnest(c.confkey) WITH ORDINALITY k(id,ord)
              JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.id ORDER BY k.ord),','),'')
            FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
            WHERE n.nspname='public' AND t.relname=? AND c.contype IN ('p','u','f') AND c.convalidated
              AND NOT c.condeferrable
            """, (r,n)->r.getString(1),table);
        // PostgreSQL renders the no-reference OID as "-" rather than a relation name.
        actual=actual.stream().map(s->s.replace("|-|","||").replace("|public.","|")).toList();
        require(actual.size()==expected.length && new HashSet<>(actual).equals(Set.of(expected)));
    }
    public static void faucet(JdbcTemplate jdbc) {
        role(jdbc);
        columns(jdbc,"faucet_grants","id|character varying(36)|true","user_id|bigint|true","amount|numeric(30,18)|true","created_at|timestamp without time zone|true");
        columns(jdbc,"faucet_policy_versions","version|character varying(64)|true","content_hash|character varying(64)|true","content|character varying(512)|true","created_at|timestamp without time zone|true");
        columns(jdbc,"faucet_account_usage","user_id|bigint|true","grant_count|bigint|true","last_day|date|false");
        columns(jdbc,"faucet_budget_usage","id|integer|true","active_version|character varying(64)|true","grant_count|bigint|true","spent|numeric(30,18)|true","initialized|boolean|true");
        columns(jdbc,"faucet_claims","grant_id|character varying(36)|true","user_id|bigint|true","key_fingerprint|character varying(64)|true","request_fingerprint|character varying(64)|true","policy_version|character varying(64)|true","received_amount|numeric(30,18)|true","balance|numeric(30,18)|true","granted_at|timestamp without time zone|true");
        keys(jdbc,"faucet_policy_versions","p|version||");
        keys(jdbc,"faucet_account_usage","p|user_id||");
        keys(jdbc,"faucet_budget_usage","p|id||");
        keys(jdbc,"faucet_grants","p|id||");
        keys(jdbc,"faucet_claims","p|grant_id||","u|user_id,key_fingerprint||","f|grant_id|faucet_grants|id","f|policy_version|faucet_policy_versions|version");
        var function=jdbc.query("""
            SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
            JOIN pg_language l ON l.oid=p.prolang
            WHERE n.nspname='public' AND p.proname='reject_faucet_mutation' AND p.pronargs=0
              AND p.prorettype='pg_catalog.trigger'::regtype AND l.lanname='plpgsql'
              AND p.prokind='f' AND NOT p.prosecdef AND p.proconfig IS NULL AND p.provolatile='v'
              AND p.proowner<>(SELECT oid FROM pg_roles WHERE rolname=current_user)
            """,(r,n)->r.getString(1));
        require(function.size()==1 && function.get(0).strip().equals("BEGIN RAISE EXCEPTION 'append-only faucet evidence'; END"));
        for(String table:List.of("faucet_grants","faucet_policy_versions","faucet_claims")) {
            var triggers=jdbc.query("""
                SELECT g.tgname,g.tgtype,g.tgenabled,p.proname,nf.nspname,g.tgqual IS NULL,
                       g.tgnargs=0 AND length(g.tgargs)=0 AND g.tgattr::text=''
                FROM pg_trigger g JOIN pg_class t ON t.oid=g.tgrelid JOIN pg_namespace nt ON nt.oid=t.relnamespace
                JOIN pg_proc p ON p.oid=g.tgfoid JOIN pg_namespace nf ON nf.oid=p.pronamespace
                WHERE nt.nspname='public' AND t.relname=? AND NOT g.tgisinternal
                """,(r,n)->r.getString(1)+"|"+r.getInt(2)+"|"+r.getString(3)+"|"+r.getString(4)+"|"+r.getString(5)+"|"+r.getBoolean(6)+"|"+r.getBoolean(7),table);
            require(triggers.equals(List.of(table+"_policy_immutable|58|O|reject_faucet_mutation|public|true|true")));
        }
    }
    public static void conditional(JdbcTemplate jdbc) {
        role(jdbc);
        columns(jdbc,"conditional_orders","id|character varying(36)|true","user_id|bigint|true","side|character varying(10)|true","input_amount|numeric(30,18)|true","target_price_e8|numeric(78,0)|true","state|character varying(30)|true","expires_at|timestamp with time zone|true","next_evaluation_at|timestamp with time zone|true","created_at|timestamp with time zone|true","updated_at|timestamp with time zone|true","execution_order_id|bigint|false","worker_owner|character varying(36)|false","reason_code|character varying(80)|false","request_key_hash|character varying(64)|true","request_hash|character varying(64)|true","version|bigint|true");
        keys(jdbc,"conditional_orders","p|id||","u|execution_order_id||","u|user_id,request_key_hash||","f|user_id|users|id","f|execution_order_id|orders|id");
        var indexes=jdbc.query("""
            SELECT ci.relname||'|'||array_to_string(ARRAY(
              SELECT pg_get_indexdef(i.indexrelid,k,true) FROM generate_series(1,i.indnkeyatts) k),',')
            FROM pg_index i JOIN pg_class ct ON ct.oid=i.indrelid JOIN pg_namespace n ON n.oid=ct.relnamespace
            JOIN pg_class ci ON ci.oid=i.indexrelid JOIN pg_am a ON a.oid=ci.relam
            WHERE n.nspname='public' AND ct.relname='conditional_orders'
              AND ci.relname IN ('idx_conditional_due','idx_conditional_owner') AND i.indisvalid AND i.indisready
              AND i.indpred IS NULL AND i.indexprs IS NULL AND a.amname='btree'
            """,(r,n)->r.getString(1));
        require(indexes.size()==2 && new HashSet<>(indexes).equals(Set.of(
                "idx_conditional_due|state,next_evaluation_at,created_at,id","idx_conditional_owner|user_id,created_at,id")));
    }
}
