package com.pricetrack.exchange.ai;
import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.ai.provider.*;
/** Test sources only. Never permits a real provider URL; no API credentials are read. */
public final class UnpaidLoopbackBudget implements ProviderBudget {
    public static ProviderBudget forBase(String base){var uri=java.net.URI.create(base);if(!uri.getScheme().equals("http")||!uri.getHost().equals("127.0.0.1"))throw new IllegalArgumentException("LOCAL_TEST_SERVER_ONLY");return new UnpaidLoopbackBudget();}
    public String reserve(String p,byte[] b){PgProviderBudget.estimate(p,b);return java.util.UUID.randomUUID().toString();}
    public void observed(String id,JsonNode usage){}
}
