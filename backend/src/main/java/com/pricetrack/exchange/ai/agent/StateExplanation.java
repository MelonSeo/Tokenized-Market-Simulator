package com.pricetrack.exchange.ai.agent;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Deterministic read-only prose from a small scalar allowlist. No inference or additional Tool/LLM call. */
final class StateExplanation {
    private StateExplanation(){}
    private static String scalar(JsonNode n,String field){
        String s=n.path(field).asText("");
        return s.length()<=100&&s.matches("[A-Za-z0-9_+.: -]+")?s:"미확인";
    }
    static String explain(List<AgentResponse.ToolEvidence> evidence){
        var lines=new ArrayList<String>();
        for(var e:evidence){if(!"SUCCESS".equals(e.status())||e.data()==null)continue;var n=e.data();
            switch(e.tool()){
                case "getCurrentReferencePrice","getMarketStatus" -> lines.add("조회한 시장 상태는 "+scalar(n,"marketStatus")+", 공급자는 "+scalar(n,"provider")+", 가격 상태는 "+scalar(n,"priceStatus")+"입니다."+(n.hasNonNull("price")?" 기준가격은 "+scalar(n,"price")+" "+scalar(n,"priceSymbol")+"이며 실행가격 보장은 아닙니다.":""));
                case "getPortfolio" -> {for(var b:n.path("balances"))lines.add(scalar(b,"symbol")+" 총액 "+scalar(b,"total")+", 잠금 "+scalar(b,"locked")+", 가용 "+scalar(b,"available")+"입니다.");}
                case "getOrder" -> lines.add("조회한 주문 상태는 "+scalar(n,"status")+"입니다. 주문 상태와 체인 Receipt의 검증 범위는 별개입니다.");
                case "getQuote" -> {lines.add("견적 저장 상태는 "+scalar(n,"storedStatus")+"입니다. 소비된 견적이라는 사실만으로 체결을 확정하지 않습니다.");var a=n.path("amountRelation");if(a.path("inputIncludesFee").asBoolean())lines.add("매수 입력 "+scalar(a,"quotedTotalDebit")+" mKRW 안에 수수료 "+scalar(a,"includedFee")+" mKRW가 포함됩니다. 수수료를 추가 차감하지 않습니다. 가격 계산 입력은 "+scalar(a,"netPricingInput")+" mKRW, 계산 수량은 "+scalar(a,"calculatedOutput")+" mSEC이며 실제 체결·차감 확정은 아닙니다.");}
                case "listAbnormalOrders" -> lines.add("조회한 이상 주문 필터는 "+scalar(n,"filter")+"이며, 이번 페이지에 "+n.path("items").size()+"건입니다. 한도 "+scalar(n,"limit")+", 대기 기준 "+scalar(n,"waitingSeconds")+"초, 다음 페이지 존재 "+n.path("hasMore").asBoolean()+"입니다. 이는 전체 이력 건수가 아닙니다.");
                case "getCAccount" -> lines.add("C 온체인 지갑 "+scalar(n,"walletBalance")+" cMKRW, Vault Free "+scalar(n,"free")+", Locked "+scalar(n,"locked")+" cMKRW입니다. PostgreSQL 모의장부와 합산하지 않습니다. 시스템 전체 미지급 수익은 개인 Claims 총액이 아닙니다.");
                case "getCPosition" -> lines.add("C 온체인 포지션 상태 "+scalar(n,"status")+", 레버리지 "+scalar(n,"leverage")+", 현재 담보 잠금 "+scalar(n,"lockedCollateral")+" cMKRW입니다. legacy DB 담보 선물과 다른 자산 모델입니다.");
                case "getFuturesPosition" -> lines.add("조회한 선물 포지션은 "+scalar(n.path("position"),"status")+", 위험 상태는 "+scalar(n.path("position"),"riskStatus")+"입니다. DB 모의담보 조회이며 C Vault 잔고가 아닙니다.");
                default -> {}
            }
        }
        return lines.isEmpty()?"조회 시점의 사실은 toolEvidence에 표시했습니다.":String.join("\n",lines);
    }
}
