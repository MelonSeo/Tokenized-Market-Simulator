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
        if(r.abnormal()==null&&foreignPortfolio(q))return new Plan(Route.UNSUPPORTED,Subject.NONE,Usage.none());
        if(counterpartyPolicy(r))return new Plan(Route.KNOWLEDGE,Subject.NONE,Usage.none());
        if(AccountSwitchQuestion.matches(r))return new Plan(Route.KNOWLEDGE,Subject.NONE,Usage.none());
        if(q.matches(".*(cvault|c자산|c지갑|marginvault|온체인.*(free|locked|지갑잔고)).*"))return new Plan(cRoute(q),Subject.C_ACCOUNT,Usage.none());
        if(r.abnormal()!=null||q.matches(".*(이상(주문|목록)|이상.*목록|장기대기.*(주문|목록)|오래.*(대기|기다).*(주문|목록)|waiting_long).*"))
            return new Plan(q.matches(".*(왜|정책|어떤필터|전체|조사|원인|이유|되나요|설명).*" )?Route.MIXED:Route.STATE,Subject.ABNORMAL,Usage.none());
        boolean metric=q.matches(".*(공급자|provider|거래량|volume|합성시세|synthetic).*"),live=q.matches(".*(현재|지금|이차트|화면|여기|돌아|접속|보이|계속|올라가|왜늘).*"),policy=q.matches(".*(정의만|일반적인정책|문서에서|용어정의).*" );
        return metric&&live&&!policy?new Plan(Route.MIXED,Subject.PRICE,Usage.none()):null;
    }
    private static boolean foreignPortfolio(String q){
        if(q.matches(".*(다른(회원|사용자|사람)|타인|남의).*말고(내|제|본인).*" ))return false;
        return q.matches(".*(다른(회원|사용자|사람)|타인|남의|그회원|해당회원).*" )
            &&q.matches(".*(포트폴리오|잔고|보유자산|자산).*" )&&q.matches(".*(보여|조회|바꿔|바꾸|열람|읽어).*" );
    }
    static boolean counterpartyPolicy(AgentRequest r){
        if(r.targetKind()!=Subject.NONE||r.skillId()!=null||r.abnormal()!=null)return false;
        String q=compact(r.question());
        // A personal execution check still needs an explicit owned target; never turn it into policy-only success.
        if(q.matches(".*(체결|매칭|성공|확정)(됐|되었|했|하였)(는지|나요|어|나|니|습니까|을까).*" ))return false;
        if(q.matches(".*(실제|현재|지금|정말).*" )&&q.matches(".*(체결|성공|상태|확정).*" )&&q.matches(".*(확인|조회).*" ))return false;
        return q.matches(".*(주문장|가격[·ㆍ]?시간우선|가격우선|반대주문|상대주문|회원.*매칭).*" )
            &&q.matches(".*(매수|매도|msec|거래|체결|주문).*" )&&q.matches(".*(정책|구조|방식|어디|발행|소각|넘어|원리).*" );
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
