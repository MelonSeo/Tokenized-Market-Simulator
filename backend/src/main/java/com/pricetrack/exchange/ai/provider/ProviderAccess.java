package com.pricetrack.exchange.ai.provider;
import com.pricetrack.exchange.ai.AiFailure;
import java.util.function.Supplier;

/** Server-only actor propagation; never accepted from request JSON/model output. */
public final class ProviderAccess {
    public record Actor(long userId,String requestId){}
    private static final ThreadLocal<Actor> CURRENT=new ThreadLocal<>();
    private ProviderAccess(){}
    public static Actor actor(){var a=CURRENT.get();if(a==null)throw new AiFailure("AI_BUDGET_ACTOR_REQUIRED");return a;}
    public static <T>T call(long uid,String requestId,Supplier<T> work){
        if(uid<=0||requestId==null||!requestId.matches("[0-9a-f-]{36}"))throw new AiFailure("AI_BUDGET_ACTOR_REQUIRED");
        var old=CURRENT.get();CURRENT.set(new Actor(uid,requestId));try{return work.get();}finally{if(old==null)CURRENT.remove();else CURRENT.set(old);}
    }
}
