package com.pricetrack.exchange.ai.agent;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;

/** Closed read-only grammar, not a general intent classifier. Unrecognized/mixed questions use the model. */
final class DeterministicStateRoute {
    private DeterministicStateRoute(){}
    static Plan select(AgentRequest request){
        String q=request.modelQuestion().replaceAll("[\\s.!?。！？]","");
        String ask="(조회해(주세요|줘)|보여(주세요|줘)|알려(주세요|줘))";
        Subject s=null;
        if(request.targetKind()==Subject.NONE&&q.matches("(현재|지금)(기준가격과시장상태|기준시세와시장상태)(를)?"+ask))s=Subject.PRICE;
        else if(request.targetKind()==Subject.NONE&&q.matches("(내|제|본인)(가용잔고와포트폴리오|포트폴리오와가용잔고)(를)?"+ask))s=Subject.PORTFOLIO;
        else if(request.targetKind()==Subject.ORDER&&q.matches("(내|제|이)주문의현재상태만(을)?"+ask))s=Subject.ORDER;
        else if(request.targetKind()==Subject.NONE&&q.matches("REVIEW_REQUIRED이상주문목록(을)?"+ask))s=Subject.ABNORMAL;
        return s==null?null:new Plan(Route.STATE,s,Usage.none());
    }
}
