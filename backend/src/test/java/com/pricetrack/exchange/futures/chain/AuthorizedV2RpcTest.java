package com.pricetrack.exchange.futures.chain;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.*;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.utils.Numeric;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class AuthorizedV2RpcTest {
    @Test @SuppressWarnings({"rawtypes","unchecked"}) void v2DeploymentRequiresBothCodeIdentitiesRelationsAndMaxUserWindow()throws Exception{
        var c=UserIntentV2CodecTest.C;var transport=mock(Web3jService.class);var ttl=new java.util.concurrent.atomic.AtomicInteger(120);
        when(transport.send(any(Request.class),any(Class.class))).thenAnswer(x->{Request request=x.getArgument(0);Response response=(Response)((Class)x.getArgument(1)).getConstructor().newInstance();String value=null;
            if(request.getMethod().equals("eth_chainId"))value="0x7a69";
            if(request.getMethod().equals("eth_getCode"))value=request.getParams().get(0).equals(c.engine())?"0x6001":"0x6002";
            if(request.getMethod().equals("eth_call")){Transaction t=(Transaction)request.getParams().get(0);String s=t.getData().substring(0,10),address=null;
                if(s.equals(selector("executor()")))address=t.getTo().equals(c.engine())?c.executor():c.engine();
                if(s.equals(selector("rules()")))address=c.rules();if(s.equals(selector("priceSigner()")))address=c.priceSigner();
                if(address!=null)value="0x"+"0".repeat(24)+address.substring(2);
                else if(s.equals(selector("supportedSymbolHash()")))value=FuturesReport.SYMBOL_HASH;
                else if(s.equals(selector("policyHash()")))value=com.pricetrack.exchange.futures.ledger.FuturesLedger.POLICY_HASH;
                else if(s.equals(selector("MAX_USER_TTL()")))value="0x"+Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(ttl.get()),64);
            }response.setResult(value);return response;});
        try(var rpc=new FuturesRpcGateway(transport,c,code("0x6001"),code("0x6002"))){rpc.verifyDeployment(c);assertThat(rpc.v2()).isTrue();ttl.set(121);assertThatThrownBy(()->rpc.verifyDeployment(c)).hasMessage("AUTHORIZED_VERSION_CONFLICT");}
    }
    String selector(String s){return Hash.sha3String(s).substring(0,10);}String code(String s){return Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(s)));}
}
