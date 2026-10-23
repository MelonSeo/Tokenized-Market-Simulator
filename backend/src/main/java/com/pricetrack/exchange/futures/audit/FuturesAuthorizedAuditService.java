package com.pricetrack.exchange.futures.audit;

import com.pricetrack.exchange.futures.audit.FuturesAuditDeployment.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded ADMIN observation admission. No execution, repair, automatic baseline or legacy history access. */
public final class FuturesAuthorizedAuditService implements AutoCloseable {
    public static final String HISTORY="NOT_PERSISTED: observation DTO only; legacy audit history is separate. A baseline POST timeout is not proof of rollback: read baselines to resolve commit uncertainty; never overwrite or blindly repeat.";
    public record Targets(List<FuturesAuditDeployment> targets,String history){public Targets{targets=List.copyOf(targets);}}
    private final FuturesAuthorizedAuditObserver observer;private final FuturesAuditDeploymentStore store;
    private final Registry registry;private final long deadlineNanos;private final AtomicBoolean admitted=new AtomicBoolean();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicReference<FuturesAuditCancellation> active=new AtomicReference<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"futures-authorized-audit");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public FuturesAuthorizedAuditService(FuturesAuthorizedAuditObserver observer,FuturesAuditDeploymentStore store,Registry registry){this(observer,store,registry,Duration.ofSeconds(40));}
    /** Short deadlines are a test-only injection; runtime configuration cannot weaken the production deadline. */
    FuturesAuthorizedAuditService(FuturesAuthorizedAuditObserver observer,FuturesAuditDeploymentStore store,Registry registry,Duration deadline){
        this.observer=Objects.requireNonNull(observer);this.store=Objects.requireNonNull(store);this.registry=Objects.requireNonNull(registry);
        if(deadline==null||deadline.isZero()||deadline.isNegative()||deadline.compareTo(Duration.ofSeconds(40))>0)throw new IllegalArgumentException("INVALID_AUDIT_DEADLINE");
        deadlineNanos=deadline.toNanos();
    }
    public Targets targets(){return new Targets(registry.targets().stream().sorted(Comparator.comparing(FuturesAuditDeployment::key)).toList(),HISTORY);}
    public List<Envelope> baselines(){return call(()->registry.targets().stream().map(store::find).flatMap(Optional::stream).toList());}
    public Envelope baseline(String targetKey,long actor){
        if(targetKey==null||registry.find(targetKey).isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AUDIT_TARGET_NOT_REGISTERED");
        requireActor(actor);return call(()->observer.baseline(targetKey,actor));
    }
    public FuturesAuthorizedAuditObserver.Observation run(long actor){requireActor(actor);return call(()->observer.observe(actor));}
    private static void requireActor(long actor){if(actor<=0)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"AUDIT_ADMIN_REQUIRED");}
    private <T>T call(Callable<T> action){
        if(closed.get())throw unavailable("AUTHORIZED_AUDIT_CLOSED");
        if(!admitted.compareAndSet(false,true))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"AUTHORIZED_AUDIT_BUSY");
        Future<T> task;var cancellation=new FuturesAuditCancellation(deadlineNanos);active.set(cancellation);
        try{task=worker.submit(()->{try{return cancellation.bound(action);}finally{active.compareAndSet(cancellation,null);admitted.set(false);}});}
        catch(RejectedExecutionException rejected){cancellation.cancel();active.compareAndSet(cancellation,null);admitted.set(false);if(closed.get())throw unavailable("AUTHORIZED_AUDIT_CLOSED");throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"AUTHORIZED_AUDIT_BUSY");}
        try{return task.get(deadlineNanos,TimeUnit.NANOSECONDS);}
        catch(TimeoutException timeout){cancellation.cancel();task.cancel(true);throw unavailable("AUTHORIZED_AUDIT_DEADLINE");}
        catch(InterruptedException interrupted){cancellation.cancel();task.cancel(true);Thread.currentThread().interrupt();throw unavailable("AUTHORIZED_AUDIT_INTERRUPTED");}
        catch(ExecutionException|CancellationException failure){throw unavailable("AUTHORIZED_AUDIT_UNAVAILABLE");}
    }
    private static ResponseStatusException unavailable(String reason){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,reason);}
    @Override public void close(){if(closed.compareAndSet(false,true)){var token=active.get();if(token!=null)token.cancel();worker.shutdownNow();}}
}
