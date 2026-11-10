package com.pricetrack.exchange.ai.agent;

/** Connection/privacy triage is a policy question, not a request for another account's balances. */
final class AccountSwitchQuestion {
    private AccountSwitchQuestion(){}
    static boolean matches(AgentRequest request){
        if(request.targetKind()!=AgentModelProvider.Subject.NONE||request.skillId()!=null||request.abnormal()!=null)return false;
        String q=RequiredReadRoute.compact(request.question());
        return q.contains("계정")&&q.matches(".*(바꿨|바꾸|바꾼|변경|전환|로그아웃|다시로그인).*" )
            &&q.matches(".*(이전|옛|다른계정|전계정).*" )&&q.matches(".*(알림|이벤트|보였|보이|노출|화면|연결).*" );
    }
    static String policyQuery(){return "WebSocket 계정 변경 로그아웃 이전 개인 이벤트 늦은 REST 연결 세대 재동기화 본인 포트폴리오";}
}
