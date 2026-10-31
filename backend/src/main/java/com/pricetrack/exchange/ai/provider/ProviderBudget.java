package com.pricetrack.exchange.ai.provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.ai.AiFailure;
public interface ProviderBudget {
    String reserve(String path,byte[] body);
    void observed(String id,JsonNode usage);
    ProviderBudget DENY=new ProviderBudget(){public String reserve(String p,byte[] b){throw new AiFailure("AI_BUDGET_NOT_CONFIGURED");}public void observed(String id,JsonNode u){}};
}
