package com.pricetrack.exchange.ai.pilot;
import java.sql.*;
import java.util.*;

/** Read-only snapshot of ONE owned budget database, never a refund/reconciliation writer. */
public final class PilotV2BudgetSnapshot {
    private PilotV2BudgetSnapshot(){}
    public static List<Map<String,Object>> read(Connection connection,String expectedDatabase)throws SQLException{
        if(expectedDatabase==null||!expectedDatabase.matches("exchange_ai_pilot_budget_[a-z0-9_]+"))throw new IllegalArgumentException("V2_BUDGET_DATABASE_SCOPE");
        try(var check=connection.createStatement()){
            check.setQueryTimeout(5);try(var r=check.executeQuery("select current_database()")){if(!r.next()||!expectedDatabase.equals(r.getString(1)))throw new IllegalArgumentException("V2_BUDGET_DATABASE_SCOPE");}
        }
        var out=new ArrayList<Map<String,Object>>();
        try(var query=connection.prepareStatement("select id::text,kind,reserved,state from ai_provider_budget.reservation order by id")){
            query.setQueryTimeout(5);try(var r=query.executeQuery()){while(r.next())out.add(Map.of("id",r.getString(1),"kind",r.getString(2),"reserved",r.getLong(3),"state",r.getString(4)));}
        }
        return List.copyOf(out);
    }
}
