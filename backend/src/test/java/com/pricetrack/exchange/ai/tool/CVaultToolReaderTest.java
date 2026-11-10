package com.pricetrack.exchange.ai.tool;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.ai.tool.read.CVaultToolReader;
import com.pricetrack.exchange.custody.auth.*;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class CVaultToolReaderTest {
    @Test void sameJwtProvedOwnerOnlyAndGlobalDebtNeverBecomesPersonalClaim(){
        var proof=mock(CWalletProofService.class);var rpc=mock(CCustodyRpcReader.class);var now=Instant.parse("2026-10-31T00:00:00Z");
        String owner="0x"+"11".repeat(20);when(proof.wallet(7)).thenReturn(new CWalletLinkStore.Link(owner,now,"proof"));
        var a=new CCustodyRpcReader.Account(owner,"100000000000000000000","0","3000000000000000000","1000000000000000000","3","1","5","9000000000000000000","9","0","9","2");
        when(rpc.account(owner)).thenReturn(new CCustodyRpcReader.Observation<>("test-deployment",new CCustodyRpcReader.Point("0x1","0x"+"ab".repeat(32),"0x"+"cd".repeat(32),"0x1"),now,a));
        var reader=new CVaultToolReader(proof,rpc,new ObjectMapper().findAndRegisterModules());
        var n=reader.account(new ToolContext(7L,UserRole.ADMIN,"request","READ",null));
        assertThat(n.path("walletBalance").asText()).isEqualTo("100.000000000000000000");assertThat(n.path("systemTotalUnpaidProfit").asText()).isEqualTo("9.000000000000000000");
        assertThat(n.path("personalClaimsEnumerated").asBoolean()).isFalse();assertThat(n.has("owner")).isFalse();assertThat(n.has("userNonce")).isFalse();
        verify(proof).wallet(7);verify(rpc).account(owner);verifyNoMoreInteractions(rpc);
    }
    @Test void unlinkedWalletDoesNotReachRpc(){var proof=mock(CWalletProofService.class);var rpc=mock(CCustodyRpcReader.class);when(proof.wallet(8)).thenThrow(new CCustodyFailure("C_WALLET_NOT_LINKED",404));var reader=new CVaultToolReader(proof,rpc,new ObjectMapper());assertThatThrownBy(()->reader.account(new ToolContext(8L,UserRole.USER,"request","READ",null))).isInstanceOf(CCustodyFailure.class);verifyNoInteractions(rpc);}
}
