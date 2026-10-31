package com.pricetrack.exchange.ai.agent;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import java.util.*;

/** Evidence requirements, not an authorization oracle. Unknown wording still uses the bounded planner. */
final class RequiredReadRoute {
    private RequiredReadRoute(){}
    static String compact(String text){return text.replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
    static Route cRoute(String q){return q.matches(".*(왜|정책|차이|원리|이유|무엇|어떻게|뜻|설명).*" )?Route.MIXED:Route.STATE;}
    static Plan select(AgentRequest r){
        String q=compact(r.question());
        if(r.cPositionId()!=null)return new Plan(cRoute(q),Subject.C_POSITION,Usage.none());
        if(r.targetKind()!=Subject.NONE||r.skillId()!=null)return null;
        if(q.matches(".*(cvault|c자산|c지갑|marginvault|온체인.*(free|locked|지갑잔고)).*"))return new Plan(cRoute(q),Subject.C_ACCOUNT,Usage.none());
        if(r.abnormal()!=null||q.matches(".*(이상(주문|목록)|이상.*목록|장기대기.*(주문|목록)|오래.*(대기|기다).*(주문|목록)|waiting_long).*"))
            return new Plan(q.matches(".*(왜|정책|어떤필터|전체|조사|원인|이유|되나요|설명).*" )?Route.MIXED:Route.STATE,Subject.ABNORMAL,Usage.none());
        boolean metric=q.matches(".*(공급자|provider|거래량|volume|합성시세|synthetic).*"),live=q.matches(".*(현재|지금|이차트|화면|여기|돌아|접속|보이|계속|올라가|왜늘).*"),policy=q.matches(".*(정의만|일반적인정책|문서에서|용어정의).*" );
        return metric&&live&&!policy?new Plan(Route.MIXED,Subject.PRICE,Usage.none()):null;
    }
    static Map<String,Object> abnormal(AgentRequest r){
        if(r.abnormal()!=null)return r.abnormal();
        String q=compact(r.question());
        boolean wait=!q.contains("review_required")&&q.matches(".*(waiting_long|장기대기|오래.*(대기|기다)).*");
        return Map.of("limit",explicitNumber(q,"([0-9]+)(?:건|개)",1,20,10),"filter",wait?"WAITING_LONG":"REVIEW_REQUIRED","waitingSeconds",explicitNumber(q,"([0-9]+)초",60,3600,300));
    }
    private static int explicitNumber(String q,String pattern,int min,int max,int fallback){
        var m=java.util.regex.Pattern.compile(pattern).matcher(q);Integer value=null;
        while(m.find())try{int n=Integer.parseInt(m.group(1));if(n<min||n>max||value!=null&&value!=n)throw new NumberFormatException();value=n;}
        catch(NumberFormatException e){throw new AgentFailure("ABNORMAL_FILTER_CLARIFICATION_REQUIRED",400);}
        return value==null?fallback:value;
    }
}
