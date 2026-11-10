package com.pricetrack.exchange.ai.pilot;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Bounded in-memory timing only; no SQL, arguments, wallet, JWT or response payload. */
public final class ReceiptProbeTrace {
 public static final boolean ENABLED="true".equals(System.getenv("AI_RECEIPT_PROBE"));
 public static final AtomicInteger current=new AtomicInteger();
 static final ConcurrentLinkedQueue<Map<String,Object>> events=new ConcurrentLinkedQueue<>();
 static final ConcurrentMap<Long,Integer> operations=new ConcurrentHashMap<>();
 static final AtomicInteger count=new AtomicInteger();
 public static void event(long op,String stage,String phase,long start,long elapsed,String code){
  if(!ENABLED)return;int call=op==0?current.get():operations.computeIfAbsent(op,k->current.get());
  if(count.incrementAndGet()>2000)throw new IllegalStateException("RECEIPT_TRACE_LIMIT");
  events.add(Map.of("call",call,"operation",op,"stage",stage,"phase",phase,"startedNs",start,"elapsedNs",elapsed,"utc",Instant.now().toString(),"code",code));
 }
 public static long begin(String stage){long n=System.nanoTime();event(0,stage,"START",n,0,"NONE");return n;}
 public static void end(String stage,long n,String code){event(0,stage,"END",n,System.nanoTime()-n,code);}
 public static void flush(ObjectMapper json,Path folder)throws Exception{if(count.get()>2000)throw new IllegalStateException("RECEIPT_TRACE_LIMIT");Files.writeString(folder.resolve("receipt-timing.private.json"),json.writeValueAsString(events));}
 @FunctionalInterface public interface FinalAction{void run()throws Throwable;}
 /** Diagnostic failure must not skip the independent financial invariant check. */
 public static void finalizeEvidence(FinalAction trace,FinalAction invariant)throws Throwable{
  Throwable failed=null;try{trace.run();}catch(Throwable e){failed=e;}
  try{invariant.run();}catch(Throwable e){if(failed!=null)e.addSuppressed(failed);throw e;}
  if(failed!=null)throw failed;
 }
 public static EntityManager wrap(EntityManager em){
  if(!ENABLED)return em;
  return (EntityManager)Proxy.newProxyInstance(EntityManager.class.getClassLoader(),new Class<?>[]{EntityManager.class},(p,m,a)->{
   Object result=invoke(em,m,a);return result instanceof TypedQuery<?> q?query(q):result;
  });
 }
 static Object query(TypedQuery<?> q){
  return Proxy.newProxyInstance(TypedQuery.class.getClassLoader(),new Class<?>[]{TypedQuery.class},(p,m,a)->{
   boolean measured=Set.of("getResultList","getSingleResult","getResultStream").contains(m.getName());long n=measured?begin("DB_QUERY"):0;String code="ERROR";
   try{Object r=invoke(q,m,a);code="OK";return r==q?p:r;}finally{if(measured)end("DB_QUERY",n,code);}
  });
 }
 static Object invoke(Object o,Method m,Object[] a)throws Throwable{try{return m.invoke(o,a);}catch(InvocationTargetException e){throw e.getCause();}}
}
