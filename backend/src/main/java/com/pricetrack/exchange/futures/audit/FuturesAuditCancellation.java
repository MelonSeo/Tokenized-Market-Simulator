package com.pricetrack.exchange.futures.audit;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request-local cooperative deadline. Cancellation never means a JDBC commit has been undone. */
final class FuturesAuditCancellation {
    private static final ThreadLocal<FuturesAuditCancellation> CURRENT=new ThreadLocal<>();
    private final long until;private final AtomicBoolean cancelled=new AtomicBoolean();
    FuturesAuditCancellation(long nanos){until=System.nanoTime()+nanos;}
    void cancel(){cancelled.set(true);}
    <T>T bound(Callable<T> work)throws Exception {
        var previous=CURRENT.get();CURRENT.set(this);
        try{check();return work.call();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    static void check(){
        var c=CURRENT.get();
        if(Thread.currentThread().isInterrupted()||(c!=null&&(c.cancelled.get()||System.nanoTime()>=c.until)))
            throw new IllegalStateException("AUTHORIZED_AUDIT_CANCELLED_OR_DEADLINE");
    }
    static int transactionSeconds(){
        check();var c=CURRENT.get();if(c==null)return 5;
        long remaining=c.until-System.nanoTime();if(remaining<=0){check();throw new IllegalStateException("AUTHORIZED_AUDIT_DEADLINE");}
        return (int)Math.min(5,Math.max(1,(remaining+TimeUnit.SECONDS.toNanos(1)-1)/TimeUnit.SECONDS.toNanos(1)));
    }
}
