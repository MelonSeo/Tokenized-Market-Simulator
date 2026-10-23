package com.pricetrack.exchange.futures.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock Web3j transport verifies actual request objects; no real RPC/Anvil claim. */
class FuturesAuditRpcGatewayTest {
    final FuturesChainIdentity c=UserIntentV2CodecTest.C;
    String hash(String code){return Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code)));}
    @Test @SuppressWarnings({"unchecked","rawtypes"}) void logsUseBothAddressesAndNoTopicFilterAtExactRange()throws Exception{
        var transport=mock(Web3jService.class);var captured=new AtomicReference<Request>();
        when(transport.send(any(),any())).thenAnswer(x->{captured.set(x.getArgument(0));var response=new EthLog();response.setResult(List.of());return response;});
        try(var rpc=new FuturesRpcGateway(transport,c,hash("0x6001"),hash("0x6002"))){assertThat(rpc.auditLogs(BigInteger.ONE,BigInteger.TEN)).isEmpty();}
        var request=captured.get();assertThat(request.getMethod()).isEqualTo("eth_getLogs");
        var filter=new ObjectMapper().valueToTree(request.getParams().get(0));
        assertThat(filter.path("address").toString()).contains(c.engine(),c.rules());
        assertThat(filter.path("fromBlock").asText()).isEqualTo("0x1");assertThat(filter.path("toBlock").asText()).isEqualTo("0xa");
        assertThat(filter.path("topics").size()).isZero();
    }
    @Test @SuppressWarnings({"unchecked","rawtypes"}) void reverseTransactionsIncludeControllerAndRulesNotOtherContracts()throws Exception{
        var transport=mock(Web3jService.class);var controller=new EthBlock.TransactionObject();controller.setTo(c.engine());
        var rules=new EthBlock.TransactionObject();rules.setTo(c.rules());var other=new EthBlock.TransactionObject();other.setTo(c.priceSigner());
        when(transport.send(any(),any())).thenAnswer(x->{Request r=x.getArgument(0);assertThat(r.getMethod()).isEqualTo("eth_getBlockByNumber");
            assertThat(r.getParams()).containsExactly("0x2",true);var response=new EthBlock();var block=new EthBlock.Block();block.setTransactions(List.of(controller,rules,other));response.setResult(block);return response;});
        try(var rpc=new FuturesRpcGateway(transport,c,hash("0x6001"),hash("0x6002"))){assertThat(rpc.auditTransactions(BigInteger.TWO)).containsExactly(controller,rules);}
    }
    @Test @SuppressWarnings({"unchecked","rawtypes"}) void hashesReadControllerAndRulesAtExplicitBlock()throws Exception{
        var transport=mock(Web3jService.class);var addresses=new ArrayList<String>();
        when(transport.send(any(),any())).thenAnswer(x->{Request r=x.getArgument(0);assertThat(r.getMethod()).isEqualTo("eth_getCode");
            assertThat(r.getParams().get(1)).isEqualTo("0x3");String address=(String)r.getParams().get(0);addresses.add(address);
            var response=new EthGetCode();response.setResult(address.equals(c.engine())?"0x6001":"0x6002");return response;});
        try(var rpc=new FuturesRpcGateway(transport,c,hash("0x6001"),hash("0x6002"))){assertThat(rpc.deploymentCodeHashes(BigInteger.valueOf(3))).containsEntry("controller",hash("0x6001")).containsEntry("rules",hash("0x6002"));}
        assertThat(addresses).containsExactly(c.engine(),c.rules());
    }
    @Test void invalidScanRangeFailsBeforeTransport()throws Exception{
        var transport=mock(Web3jService.class);
        try(var rpc=new FuturesRpcGateway(transport,c,hash("0x6001"),hash("0x6002"))){
            assertThatThrownBy(()->rpc.auditLogs(BigInteger.ZERO,BigInteger.valueOf(250))).hasMessage("AUDIT_LOG_RANGE_LIMIT");verifyNoInteractions(transport);
        }
    }
    @Test @SuppressWarnings({"unchecked","rawtypes"}) void auditNonceIsAnExactWordAtExplicitBlockWithoutLatestFallback()throws Exception{
        var transport=mock(Web3jService.class);var word=new AtomicReference<>("0x"+"0".repeat(63)+"7");
        when(transport.send(any(),any())).thenAnswer(x->{Request r=x.getArgument(0);assertThat(r.getMethod()).isEqualTo("eth_call");
            // HttpService serializes DefaultBlockParameterNumber through Web3j's request mapper;
            // inspect that wire JSON rather than comparing its Java object to a String.
            var mapper=org.web3j.protocol.ObjectMapperFactory.getObjectMapper();
            var wire=mapper.readTree(mapper.writeValueAsString(r));
            assertThat(wire.path("params").get(1).isTextual()).isTrue();
            assertThat(wire.path("params").get(1).asText()).isEqualTo("0xb");var call=wire.path("params").get(0);
            assertThat(call.path("to").asText()).isEqualTo(c.engine());
            assertThat(call.path("data").asText()).isEqualTo(Hash.sha3String("nonces(address)").substring(0,10)+"0".repeat(24)+c.priceSigner().substring(2));
            var response=new EthCall();response.setResult(word.get());return response;});
        try(var rpc=new FuturesRpcGateway(transport,c,hash("0x6001"),hash("0x6002"))){
            assertThat(rpc.userNonce(c.priceSigner(),BigInteger.valueOf(11))).isEqualTo(BigInteger.valueOf(7));
            for(var invalid:List.of("0x7","0x"+"0".repeat(66))){word.set(invalid);assertThatThrownBy(()->rpc.userNonce(c.priceSigner(),BigInteger.valueOf(11))).hasMessage("AUDIT_NONCE_WORD_REQUIRED");}
        }
    }
    @Test @SuppressWarnings({"unchecked","rawtypes"}) void auditEoaCheckUsesHistoricalBlockAndInvalidNonceArgumentsNeverSend()throws Exception{
        var transport=mock(Web3jService.class);var code=new AtomicReference<>("0x");
        when(transport.send(any(),any())).thenAnswer(x->{Request r=x.getArgument(0);assertThat(r.getMethod()).isEqualTo("eth_getCode");assertThat(r.getParams()).containsExactly(c.priceSigner(),"0xc");var result=new EthGetCode();result.setResult(code.get());return result;});
        try(var rpc=new FuturesRpcGateway(transport,c,hash("0x6001"),hash("0x6002"))){
            assertThatThrownBy(()->rpc.userNonce("bad",BigInteger.ONE)).hasMessage("AUDIT_NONCE_ARGUMENT_REQUIRED");
            assertThatThrownBy(()->rpc.userNonce(c.priceSigner(),null)).hasMessage("AUDIT_NONCE_ARGUMENT_REQUIRED");verifyNoInteractions(transport);
            assertThat(rpc.eoa(c.priceSigner(),BigInteger.valueOf(12))).isTrue();code.set("0xef0100"+"11".repeat(20));assertThat(rpc.eoa(c.priceSigner(),BigInteger.valueOf(12))).isFalse();
        }
    }
}
