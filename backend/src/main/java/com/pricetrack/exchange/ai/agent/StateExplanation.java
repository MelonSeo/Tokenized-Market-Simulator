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
                case "getPortfolio" -> {lines.add("이 포트폴리오는 ADMIN을 포함해 로그인한 본인의 모의장부 조회이며 타인 잔고가 아닙니다.");for(var b:n.path("balances"))lines.add(scalar(b,"symbol")+" 총액 "+scalar(b,"total")+", 잠금 "+scalar(b,"locked")+", 가용 "+scalar(b,"available")+"입니다.");}
                case "getOrder" -> lines.add("조회한 주문 상태는 "+scalar(n,"status")+"입니다. 주문 상태와 체인 Receipt의 검증 범위는 별개입니다.");
                case "getReceiptSummary" -> lines.add("Receipt 관측은 "+scalar(n,"receiptStatus")+", 실행 상태 "+scalar(n,"executionStatus")+", 이벤트 검증 "+scalar(n,"eventValidation")
                    +", 확인 수 "+scalar(n,"confirmations")+"/필요 "+scalar(n,"requiredConfirmations")+", DB 상태 "+scalar(n,"databaseStatus")+"입니다. 이 조회는 새 거래 실행이나 전체 체인 감사를 뜻하지 않습니다.");
                case "getQuote" -> {lines.add("견적 저장 상태는 "+scalar(n,"storedStatus")+"입니다. 소비된 견적이라는 사실만으로 체결을 확정하지 않습니다.");var a=n.path("amountRelation");if(a.path("inputIncludesFee").asBoolean())lines.add("매수 입력 "+scalar(a,"quotedTotalDebit")+" mKRW 안에 수수료 "+scalar(a,"includedFee")+" mKRW가 포함됩니다. 수수료를 추가 차감하지 않습니다. 가격 계산 입력은 "+scalar(a,"netPricingInput")+" mKRW, 계산 수량은 "+scalar(a,"calculatedOutput")+" mSEC이며 실제 체결·차감 확정은 아닙니다.");}
                case "listAbnormalOrders" -> lines.add("조회한 이상 주문 필터는 "+scalar(n,"filter")+"이며, 이번 페이지에 "+n.path("items").size()+"건입니다. 한도 "+scalar(n,"limit")+", "+waitingCriterion(n)+", 다음 페이지 존재 "+n.path("hasMore").asBoolean()+"입니다. 이는 전체 이력 건수가 아닙니다.");
                case "getCAccount" -> lines.add("C 온체인 지갑 "+scalar(n,"walletBalance")+" cMKRW, Vault Free "+scalar(n,"free")+", Locked "+scalar(n,"locked")+" cMKRW입니다. getPortfolio는 PostgreSQL 모의장부를 읽고, 이 C 잔고는 별도 getCAccount 조회입니다. 두 잔고를 합산하지 않습니다. 시스템 전체 미지급 수익은 개인 Claims 총액이 아닙니다.");
                case "getCPosition" -> lines.add("C 온체인 포지션 상태 "+scalar(n,"status")+", 레버리지 "+scalar(n,"leverage")+", 현재 담보 잠금 "+scalar(n,"lockedCollateral")+" cMKRW입니다. legacy DB 담보 선물과 다른 자산 모델입니다.");
                case "getFuturesPosition" -> {var p=n.path("position");lines.add("조회한 선물 포지션의 DB 기록 상태는 "+scalar(p,"status")+", 위험 상태는 "+scalar(p,"riskStatus")
                    +", 현재 담보 "+scalar(p,"collateral")+", 기록된 실현손익 "+scalar(p,"realized")+", badDebt "+scalar(p,"badDebt")+"입니다. DB 모의담보 조회이며 C Vault 잔고가 아닙니다. 현재 기록은 과거 청산 원인이나 사건 당시 담보를 증명하지 않습니다.");
                    if(p.path("priceAvailable").isBoolean()&&p.path("priceAvailable").booleanValue()&&p.hasNonNull("equity")&&p.hasNonNull("maintenance"))
                        lines.add("현재 가격 관측 기준 equity "+scalar(p,"equity")+", 유지증거금 "+scalar(p,"maintenance")+"입니다. 과거 사건 시점 값이나 경고 해제 증명은 아닙니다.");
                    if(p.has("pendingOperation"))lines.add("DB 기록의 처리 중 작업은 "+(p.path("pendingOperation").isNull()?"없음":"있음")+"입니다. 체인 작업 전체 이력 조회는 아닙니다.");}
                case "getFuturesLiquidationEvidence" -> lines.add("선물 사건 근거 검증은 "+scalar(n,"verification")+"입니다. "
                    +(n.path("scope").asText().equals("SELECTED_CALL_AND_LIQUIDATION_ONLY")?"선택 START_CALL/LIQUIDATE 근거 범위이며 CLEAR_CALL 전체 이력을 검증한 것이 아닙니다. ":"")
                    +"현재 equity 회복만으로 경고 해제 확정이나 체인 CLEAR_CALL 성공을 추론하지 않습니다.");
                default -> {}
            }
        }
        return lines.isEmpty()?"조회 시점의 사실은 toolEvidence에 표시했습니다.":String.join("\n",lines);
    }
    private static String waitingCriterion(JsonNode n){
        if("REVIEW_REQUIRED".equals(n.path("filter").asText()))return "대기시간 기준은 적용하지 않음";
        if("WAITING_LONG".equals(n.path("filter").asText())&&n.path("waitingSecondsApplied").isBoolean()
                &&n.path("waitingSecondsApplied").booleanValue())return "대기 기준 "+scalar(n,"waitingSeconds")+"초";
        return "대기시간 기준 적용 여부 미확인";
    }
}
