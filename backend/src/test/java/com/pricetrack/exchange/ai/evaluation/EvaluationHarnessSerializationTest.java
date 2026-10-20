package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import org.junit.jupiter.api.Test;

/** Reproduce evaluation-only mapper misconfiguration. No provider/network/DB. */
class EvaluationHarnessSerializationTest {
    @Test void plainMapperCannotSerializeActualToolResultInstant() {
        var plain=new ObjectMapper();
        assertThatThrownBy(()->plain.writeValueAsBytes(ToolResult.success("getOrder","TRADING_DB",plain.createObjectNode())))
            .isInstanceOf(InvalidDefinitionException.class);
    }
    ToolResult invoke(ObjectMapper json)throws Exception {
        var reads=mock(ToolReadFacade.class);
        when(reads.order(any(),eq(1L))).thenReturn(json.createObjectNode().put("orderId",1));
        try(var dispatcher=new ToolDispatcher(new ToolProperties(true),new ToolRegistry(json),reads,mock(ReceiptReader.class),new ToolAudit(),json)) {
            return dispatcher.invoke(new AuthenticatedUser(910001L,null,UserRole.USER),"getOrder",
                "{\"arguments\":{\"orderId\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Test void validToolArgumentsFailOnlyBecauseEvaluationMapperLacksTimeModule()throws Exception {
        assertThat(invoke(new ObjectMapper()).error()).isEqualTo("INVALID_TOOL_ARGUMENTS");
    }
    @Test void timeEnabledMapperRestoresSameToolWithoutProductChange()throws Exception {
        var fixed=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        assertThat(invoke(fixed).status()).isEqualTo("SUCCESS");
    }
}
