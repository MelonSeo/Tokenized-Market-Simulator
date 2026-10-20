package com.pricetrack.exchange.ai.evaluation;

import java.sql.SQLException;
import java.util.*;

/** Test-only allow-list; never serialize exception message/stack/URL/credentials. */
final class EvaluationFailureDiagnostic {
    static Map<String,Object> safe(Throwable failure) {
        var result=new LinkedHashMap<String,Object>();
        result.put("category","EVALUATION_FAILURE");
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        Throwable current=failure;
        for(int depth=0;current!=null&&depth<16&&seen.add(current);depth++,current=current.getCause()) {
            if(current instanceof SQLException sql) {
                String state=sql.getSQLState();
                result.put("category","SQL_EXCEPTION");
                result.put("sqlState",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNAVAILABLE");
                result.put("vendorCode",sql.getErrorCode());
                break;
            }
        }
        return result;
    }
}
