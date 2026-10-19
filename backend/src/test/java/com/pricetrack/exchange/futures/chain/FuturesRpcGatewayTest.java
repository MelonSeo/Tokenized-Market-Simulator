package com.pricetrack.exchange.futures.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

/** Local loopback HTTP fixtures only; no Anvil or external endpoint. */
class FuturesRpcGatewayTest {
    HttpServer server; FuturesRpcGateway rpc;
    String chainResult="0x7a69",code="0x6000",estimateTag,estimateRevert; boolean wrongSigner,error;
    FuturesChainIdentity chain=new FuturesChainIdentity(BigInteger.valueOf(31337),"0x"+"12".repeat(20),"0x"+"34".repeat(20),"0x"+"56".repeat(20));
    @BeforeEach void setup()throws Exception {
        var mapper=new ObjectMapper();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            var request=mapper.readTree(exchange.getRequestBody());String method=request.get("method").asText();String result="null";
            if(method.equals("eth_chainId"))result='"'+chainResult+'"';
            if(method.equals("eth_getCode"))result='"'+code+'"';
            if(method.equals("eth_estimateGas")) { estimateTag=request.get("params").get(1).asText();result="\"0x5208\""; }
            if(method.equals("eth_call")) {
                String data=request.get("params").get(0).get("data").asText();String value=null;
                if(data.equals(Hash.sha3String("executor()").substring(0,10)))value="0x"+"0".repeat(24)+Numeric.cleanHexPrefix(chain.executor());
                if(data.equals(Hash.sha3String("priceSigner()").substring(0,10)))value="0x"+"0".repeat(24)+Numeric.cleanHexPrefix(wrongSigner?chain.executor():chain.priceSigner());
                if(data.equals(Hash.sha3String("supportedSymbolHash()").substring(0,10)))value=FuturesReport.SYMBOL_HASH;
                if(data.equals(Hash.sha3String("policyHash()").substring(0,10)))value=FuturesLedger.POLICY_HASH;
                result='"'+value+'"';
            }
            String response="{\"jsonrpc\":\"2.0\",\"id\":"+request.get("id")+","+(error || estimateRevert!=null?"\"error\":{\"code\":-32000,\"message\":\"DO_NOT_EXPOSE_SECRET_PROVIDER_BODY\""+(estimateRevert==null?"":",\"data\":\""+estimateRevert+"\"")+"}":"\"result\":"+result)+"}";
            byte[] body=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();rpc=new FuturesRpcGateway(new HttpService("http://127.0.0.1:"+server.getAddress().getPort()),chain,Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code))));
    }
    @AfterEach void stop() { if(rpc!=null)rpc.close();if(server!=null)server.stop(0); }
    @Test void validDeploymentAndMissingReceiptOrTransactionAreNotRpcErrors()throws Exception {
        rpc.verifyDeployment(chain);
        assertThat(rpc.receipt("0x"+"11".repeat(32))).isEmpty();assertThat(rpc.known("0x"+"11".repeat(32))).isFalse();
    }
    @Test void wrongChainCodeAndImmutableSignerFailClosed() {
        chainResult="0x1";assertThatThrownBy(()->rpc.verifyDeployment(chain)).hasMessage("FUTURES_CHAIN_MISMATCH");
        chainResult="0x7a69";code="0x6001";assertThatThrownBy(()->rpc.verifyDeployment(chain)).hasMessage("FUTURES_CODE_MISMATCH");
        code="0x6000";wrongSigner=true;assertThatThrownBy(()->rpc.verifyDeployment(chain)).hasMessage("FUTURES_IMMUTABLE_MISMATCH");
    }
    @Test void providerErrorBodyIsNotExposed() {
        error=true;assertThatThrownBy(()->rpc.verifyDeployment(chain)).hasMessage("FUTURES_RPC_RESPONSE_UNAVAILABLE");
    }
    @Test void gasEstimateUsesPendingAndOnlyKnownRevertIsExposed()throws Exception {
        assertThat(rpc.estimate(chain.executor(),BigInteger.ZERO,BigInteger.ONE,chain.engine(),"0x1234")).isEqualTo(BigInteger.valueOf(21000));
        assertThat(estimateTag).isEqualTo("pending");
        estimateRevert=Hash.sha3String("GraceNotOver()").substring(0,10);
        assertThatThrownBy(()->rpc.estimate(chain.executor(),BigInteger.ZERO,BigInteger.ONE,chain.engine(),"0x1234")).hasMessage("FUTURES_GRACE_NOT_OVER");
        estimateRevert="untrusted-provider-data";
        assertThatThrownBy(()->rpc.estimate(chain.executor(),BigInteger.ZERO,BigInteger.ONE,chain.engine(),"0x1234")).hasMessage("FUTURES_ESTIMATE_REJECTED");
    }
}
