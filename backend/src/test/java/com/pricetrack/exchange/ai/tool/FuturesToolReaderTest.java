package com.pricetrack.exchange.ai.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.tool.read.FuturesToolReader;
import com.pricetrack.exchange.futures.audit.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.read.FuturesReadService;
import com.pricetrack.exchange.user.UserRole;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FuturesToolReaderTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final FuturesAuditStore store=mock(FuturesAuditStore.class);final FuturesReadService reads=mock(FuturesReadService.class);final FuturesRpcGateway rpc=mock(FuturesRpcGateway.class);
    final String id="11111111-1111-1111-1111-111111111111";
    final Position p=new Position(id,7,"0x"+"11".repeat(32),Side.LONG,10,BigInteger.TEN,BigInteger.TEN,BigInteger.TEN,"OPEN","GRACE",2,1,100,40,null,null,null,null,Instant.EPOCH);
    ToolContext ctx(long uid,UserRole role){return new ToolContext(uid,role,"r","TEST",null);}
    FuturesToolReader reader(){return new FuturesToolReader(store,reads,rpc,null,json);}
    FuturesAuditStore.Cut cut(List<Operation> ops){return new FuturesAuditStore.Cut(new FuturesAuditSnapshot("p",BigInteger.TEN,BigInteger.TEN,BigInteger.ZERO,List.of(p),ops,List.of(),List.of(),List.of()),null);}
    @Test void foreignAndMissingAreIndistinguishableBeforeRpc(){
        when(store.cut()).thenReturn(cut(List.of()));
        assertThatThrownBy(()->reader().evidence(ctx(8,UserRole.USER),id)).hasMessage("RESOURCE_NOT_FOUND");
        assertThatThrownBy(()->reader().evidence(ctx(7,UserRole.USER),UUID.randomUUID().toString())).hasMessage("RESOURCE_NOT_FOUND");verifyNoInteractions(rpc,reads);
    }
    @Test void unavailableRpcNeverBecomesConfirmedOrLeaksPrivateSnapshot()throws Exception {
        var op=new Operation("op",id,7,Action.START_CALL,1,0,BigInteger.TEN,40,BigInteger.TEN,BigInteger.TEN,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,"SIGNED");
        when(store.cut()).thenReturn(cut(List.of(op)));doThrow(new java.io.IOException("Bearer SECRET_PRIVATE_ERROR")).when(rpc).verifyDeployment(any());
        var result=reader().evidence(ctx(7,UserRole.USER),id);
        assertThat(result.path("verification").asText()).isEqualTo("INCONCLUSIVE");assertThat(result.toString()).doesNotContain("SECRET","Bearer","raw","signature","accountRef");
        verify(store,never()).save(any());verifyNoInteractions(reads);
    }
    @Test void adminCanSelectButUserCannotPassIdentityArguments() {
        when(store.cut()).thenReturn(cut(List.of()));when(reads.position(7,id)).thenReturn(null);
        assertThat(reader().position(ctx(8,UserRole.ADMIN),id).path("onchainEscrow").asBoolean()).isFalse();verify(reads).position(7,id);
        var registry=new ToolRegistry(json);assertThat(registry.validate("getFuturesPosition",json.createObjectNode().put("positionId",id)).positionId()).isEqualTo(id);
        assertThatThrownBy(()->registry.validate("getFuturesPosition",json.createObjectNode().put("positionId",id).put("userId",7))).hasMessage("INVALID_TOOL_ARGUMENTS");
    }
    @Test void eachTerminalProjectionMismatchFailsClosedBeforeRpc() {
        var terminal=new Operation("op",id,7,Action.LIQUIDATE,2,1,BigInteger.TEN,100,BigInteger.TEN,BigInteger.TEN,BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN.negate(),BigInteger.ZERO,"CONFIRMED");
        for(String field:List.of("realized","badDebt")){
            var node=json.valueToTree(p);((com.fasterxml.jackson.databind.node.ObjectNode)node).put("status","LIQUIDATED").put("version",3).put("realized",BigInteger.TEN.negate()).put("badDebt",BigInteger.ZERO).put(field,BigInteger.ONE);
            var bad=json.convertValue(node,Position.class);var s=new FuturesAuditSnapshot("p",BigInteger.TEN,BigInteger.TEN,BigInteger.ZERO,List.of(bad),List.of(terminal),List.of(),List.of(),List.of());
            when(store.cut()).thenReturn(new FuturesAuditStore.Cut(s,null));var result=reader().evidence(ctx(7,UserRole.USER),id);
            assertThat(result.path("verification").asText()).isEqualTo("INCONCLUSIVE");assertThat(result.path("reason").asText()).isEqualTo("DB_SETTLEMENT_PROJECTION_UNVERIFIED");
        }verifyNoInteractions(rpc,reads);
    }
}
