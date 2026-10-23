package com.pricetrack.exchange.futures.chain;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.AiProperties;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.agent.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FuturesWebStage1TransportTest {
    @Test void everyProviderPathFailsBeforeHttpWithNoKeysNoApprovalAndNoRetry(){
        var json=new ObjectMapper();var transport=new FuturesWebStage1Configuration.NoPaidTransport(json);
        var p=new AiProperties(false,"jdbc:postgresql://127.0.0.1:1/exchange_ai_probe","offline","","../docs/ai-knowledge","../docs/ai/ingest-manifest.json","","guarded:stdio/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        var provider=OpenAiProvider.forGuardedEvaluation(p,json,transport);var model=new OpenAiAgentProvider(provider,json);
        assertThatThrownBy(()->provider.embed(List.of("offline"))).hasMessage("AI_WEB_PAID_EXECUTION_NOT_APPROVED");
        assertThatThrownBy(()->model.plan("offline",AgentModelProvider.Subject.NONE,Duration.ofSeconds(1))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->model.synthesize("offline",AgentModelProvider.Route.MIXED,json.createObjectNode(),Duration.ofSeconds(1))).isInstanceOf(RuntimeException.class);
        assertThat(transport.rejected()).isEqualTo(3);
    }
}
