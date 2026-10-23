package com.pricetrack.exchange.futures.authorization;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.futures.read.FuturesReadService;
import com.pricetrack.exchange.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizedV2BoundaryTest {
    @Test void defaultOffAndIncompleteFlagSetCannotStartRuntimeOrApi(){
        var runner=new ApplicationContextRunner().withUserConfiguration(AuthorizedV2Config.class,AuthorizedV2Controller.class);
        runner.run(c->{assertThat(c).doesNotHaveBean(AuthorizedV2Controller.class);assertThat(c).doesNotHaveBean(AuthorizedV2Config.V2Runtime.class);});
        runner.withPropertyValues("app.futures.user-authorization-v2.enabled=true","app.futures.enabled=true","app.futures.runtime-enabled=false").run(c->{assertThat(c).doesNotHaveBean(AuthorizedV2Controller.class);assertThat(c).doesNotHaveBean(AuthorizedV2Config.V2Runtime.class);});
    }
    @Test void v2ControllerUsesJwtSubjectAndExplicitV2ServiceOnly(){
        var service=mock(AuthorizedV2Service.class);var proof=mock(AuthorizedFuturesService.class);var runtime=new AuthorizedV2Config.V2Runtime(null,proof,service);
        var controller=new AuthorizedV2Controller(runtime,mock(AuthorizedFuturesStore.class),mock(FuturesReadService.class));
        var user=new AuthenticatedUser(7L,null,UserRole.USER);controller.draft(user,"key",null);verify(service).draft(7,"key",null);verifyNoInteractions(proof);
        controller.challenge(user,"http://localhost:5173",new AuthorizedFuturesController.WalletRequest("0x"+"11".repeat(20)));verify(proof).challenge(7,"0x"+"11".repeat(20),"http://localhost:5173");
    }
}
