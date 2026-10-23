package com.pricetrack.exchange.futures.chain;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
class WebScopedTransportTest {
    @Test void unauthorizedOrUnboundScopeStopsAndCannotBindOrSubmitLater()throws Exception {
        var json=new ObjectMapper();var scoped=new FuturesWebScopedTransport(json,Path.of("unused-never-created"));
        try {
            assertThatThrownBy(()->scoped.authorize(new AuthenticatedUser(7L,"user",UserRole.USER),json.readTree("{}"))).hasMessage("AI_WEB_SCOPE_FORBIDDEN");
            assertThat(scoped.stopped()).isTrue();
            assertThatThrownBy(()->scoped.bind(7,"11111111-1111-1111-1111-111111111111")).hasMessage("AI_WEB_ALREADY_BOUND");
            assertThatThrownBy(()->scoped.authorize(null,json.readTree("{}"))).hasMessage("AI_WEB_SCOPE_FORBIDDEN");
        }finally{scoped.close();}
    }
}
