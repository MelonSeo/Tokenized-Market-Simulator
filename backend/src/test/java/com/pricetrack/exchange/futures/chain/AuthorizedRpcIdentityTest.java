package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.*;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.*;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** In-memory Web3j transport, no listener, HTTP, Anvil or remote RPC. */
class AuthorizedRpcIdentityTest {
    final FuturesChainIdentity chain=UserIntentCodecTest.C;
    Web3jService transport;FuturesRpcGateway rpc;
    boolean wrongExecutor,wrongRules,wrongCode;
    @BeforeEach @SuppressWarnings({"rawtypes","unchecked"}) void setup()throws Exception {
        transport=mock(Web3jService.class);
        when(transport.send(any(Request.class),any(Class.class))).thenAnswer(x->{
            Request request=x.getArgument(0);Class type=x.getArgument(1);Response response=(Response)type.getConstructor().newInstance();Object value=null;
            if(request.getMethod().equals("eth_chainId"))value="0x7a69";
            if(request.getMethod().equals("eth_getCode")){String to=(String)request.getParams().get(0);value=to.equals(chain.engine())?"0x6001":to.equals(chain.rules())?(wrongCode?"0x6003":"0x6002"):"0x";}
            if(request.getMethod().equals("eth_call")){
                Transaction tx=(Transaction)request.getParams().get(0);String selector=tx.getData().substring(0,10);String address=null;
                if(selector.equals(selector("executor()")))address=tx.getTo().equals(chain.engine())||wrongExecutor?chain.executor():chain.engine();
                if(selector.equals(selector("rules()")))address=wrongRules?chain.engine():chain.rules();
                if(selector.equals(selector("priceSigner()")))address=chain.priceSigner();
                if(selector.equals(selector("positionOwner(bytes32)")))address=UserIntentCodecTest.USER.getAddress();
                if(address!=null)value="0x"+"0".repeat(24)+address.substring(2);
                else if(selector.equals(selector("supportedSymbolHash()")))value=FuturesReport.SYMBOL_HASH;
                else if(selector.equals(selector("policyHash()")))value=FuturesLedger.POLICY_HASH;
                else if(selector.equals(selector("nonces(address)")))value="0x"+"0".repeat(63)+"3";
            }
            response.setResult(value);return response;
        });
        rpc=new FuturesRpcGateway(transport,chain,hash("0x6001"),hash("0x6002"));
    }
    String selector(String s){return Hash.sha3String(s).substring(0,10);}
    String hash(String code){return Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code)));}
    @AfterEach void close(){rpc.close();}
    @Test void verifiesBothCodeHashesControllerInnerRelationsAndWalletReads()throws Exception {
        rpc.verifyDeployment(chain);assertThat(rpc.eoa(UserIntentCodecTest.USER.getAddress())).isTrue();
        assertThat(rpc.userNonce(UserIntentCodecTest.USER.getAddress())).isEqualTo(BigInteger.valueOf(3));
        assertThat(rpc.positionOwner("position",BigInteger.valueOf(7))).isEqualTo(UserIntentCodecTest.USER.getAddress());
    }
    @Test void wrongInnerExecutorRulesPointerAndCodeFailClosed(){
        wrongExecutor=true;assertThatThrownBy(()->rpc.verifyDeployment(chain)).hasMessage("FUTURES_IMMUTABLE_MISMATCH");wrongExecutor=false;
        wrongRules=true;assertThatThrownBy(()->rpc.verifyDeployment(chain)).hasMessage("AUTHORIZED_DEPLOYMENT_CONFLICT");wrongRules=false;
        wrongCode=true;assertThatThrownBy(()->rpc.verifyDeployment(chain)).hasMessage("AUTHORIZED_DEPLOYMENT_CONFLICT");
    }
}
