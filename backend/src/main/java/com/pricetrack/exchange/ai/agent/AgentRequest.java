package com.pricetrack.exchange.ai.agent;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.regex.Pattern;

/** Strict, stateless request. Resource identifiers are supplied explicitly and never invented by a model. */
public record AgentRequest(String question, Long orderId, String quoteId,String skillId,String positionId) {
    public AgentRequest(String question,Long orderId,String quoteId,String skillId){this(question,orderId,quoteId,skillId,null);}
    public AgentRequest(String question,Long orderId,String quoteId){this(question,orderId,quoteId,null);}
    public static AgentRequest parse(ObjectMapper json,byte[] bytes){
        try {
            if(bytes==null || bytes.length>4096)throw new AgentFailure("AGENT_INPUT_LIMIT",400);
            var root=json.readTree(bytes);
            if(root==null || !root.isObject() || !root.has("question") || root.size()>3
                    || root.properties().stream().anyMatch(e -> !Set.of("question","target","skillId").contains(e.getKey()))
                    || !root.path("question").isTextual())throw new AgentFailure("INVALID_AGENT_REQUEST",400);
            String q=root.path("question").asText();
            if(q.isBlank() || q.length()>1000)throw new AgentFailure("AGENT_INPUT_LIMIT",400);
            Long order=null;String quote=null;String position=null;
            if(root.has("target")){
                var target=root.get("target");
                if(!target.isObject() || target.size()!=1)throw new AgentFailure("INVALID_AGENT_REQUEST",400);
                if(target.has("orderId")){
                    var id=target.get("orderId");
                    if(!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue()<=0)throw new AgentFailure("INVALID_AGENT_REQUEST",400);
                    order=id.longValue();
                }else if(target.has("quoteId") && target.get("quoteId").isTextual()
                        && target.get("quoteId").asText().matches("0x[0-9a-fA-F]{64}")) quote=target.get("quoteId").asText();
                else if(target.has("positionId")&&target.get("positionId").isTextual()&&target.get("positionId").asText().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))position=target.get("positionId").asText();
                else throw new AgentFailure("INVALID_AGENT_REQUEST",400);
            }
            String skill=null;
            if(root.has("skillId")){
                if(!root.get("skillId").isTextual() || !root.get("skillId").asText().matches("[a-z-]{1,64}"))throw new AgentFailure("INVALID_AGENT_REQUEST",400);
                skill=root.get("skillId").asText();
            }
            return new AgentRequest(q,order,quote,skill,position);
        }catch(AgentFailure e){throw e;}catch(Exception e){throw new AgentFailure("INVALID_AGENT_REQUEST",400);}
    }
    public AgentModelProvider.Subject targetKind(){return positionId!=null?AgentModelProvider.Subject.FUTURES:orderId!=null?AgentModelProvider.Subject.ORDER:quoteId!=null?AgentModelProvider.Subject.QUOTE:AgentModelProvider.Subject.NONE;}
    public boolean conflictingTarget(){
        var positions=Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(question);
        while(positions.find())if(positionId==null||!positions.group().equals(positionId))return true;
        for(String regex:List.of("(?i)(?:주문|order)(?:\\s*(?:번호|id|#))?\\s*(\\d+)","(\\d+)\\s*번\\s*주문")){
            var matcher=Pattern.compile(regex).matcher(question);
            while(matcher.find())if(orderId==null || !matcher.group(1).equals(orderId.toString()))return true;
        }
        var quotes=Pattern.compile("0x[0-9a-fA-F]{64}").matcher(question);
        while(quotes.find())if(quoteId==null || !quotes.group().equalsIgnoreCase(quoteId))return true;
        return false;
    }
    /** The provider receives no target IDs, JWTs or user profile. User-supplied recognizable secrets are redacted. */
    public String modelQuestion(){
        return question.replaceAll("(?i)(?:주문|order)(?:\\s*(?:번호|id|#))?\\s*\\d+","대상 주문")
                .replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","대상 포지션")
                .replaceAll("\\d+\\s*번\\s*주문","대상 주문")
                .replaceAll("0x[0-9a-fA-F]{64,}","[REDACTED]")
                .replaceAll("(?i)Bearer\\s+\\S+","[REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]+","[REDACTED]")
                .replaceAll("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+","[REDACTED]")
                .replaceAll("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]+","[REDACTED]")
                .replaceAll("(?i)(password|secret|private.?key|비밀번호)\\s*[:=]\\s*\\S+","[REDACTED]");
    }
    public boolean mutationRequest(){
        if(Pattern.compile("(?i)(?:강제청산|청산|추가담보|증거금\\s*변경|포지션\\s*(?:개설|종료))(?:을|를)?\\s*(?:해줘|해 줘|실행해)").matcher(question).find())return true;
        return Pattern.compile("(?i)(forceFill|setUserBalance|retryTransactionWithNewNonce|(?:매수|매도|입금|출금|재전송|재서명|잠금 해제|강제 정산)(?:을|를)?\\s*(?:해줘|해 줘|실행해)|주문\\s*(?:생성|취소)\\s*해)").matcher(question).find();
    }
}
