package com.pricetrack.exchange.custody.authorization;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.StructuredDataEncoder;

public final class CUserIntentTypedDataTest {
    private static final String CONTROLLER="0x"+"33".repeat(20);
    private static final BigInteger CHAIN=BigInteger.valueOf(31337);
    private static final ObjectMapper JSON=new ObjectMapper();
    private static BigInteger n(long value){return BigInteger.valueOf(value);}
    private static String h(long value){return "0x"+String.format("%064x",value);}
    private static BigInteger[] words(){return new BigInteger[]{n(0),n(0),n(0),n(1),n(10),n(0),n(0),n(100).multiply(BigInteger.TEN.pow(18)),
        n(9900000000L),n(10100000000L),n(9).multiply(BigInteger.TEN.pow(18)),n(11).multiply(BigInteger.TEN.pow(18)),n(7),n(1000),n(1120)};}
    private static Intent intent(BigInteger[] w){return new Intent("0x"+"11".repeat(20),"0x"+"22".repeat(20),h(3),h(4),h(5),h(6),
        w[0],w[1],w[2],w[3],w[4],w[5],w[6],w[7],w[8],w[9],w[10],w[11],w[12],w[13],w[14],h(8));}
    private static Map<String,Object> data(Intent i){return CUserIntentTypedData.format(i,CHAIN,CONTROLLER);}
    private static String hash(Map<String,Object> data)throws Exception{return "0x"+HexFormat.of().formatHex(new StructuredDataEncoder(JSON.writeValueAsString(data)).hashStructuredData());}

    @Test void serializedJsonMatchesIndependentGoldenAndExistingCVerifier()throws Exception{
        Intent i=intent(words());String digest=hash(data(i));
        assertEquals("0xb81cb4bf2b9bad10a2613da0ec1df89549be8aaf991f3cf4149ef39ca02a3e67",digest);
        assertEquals(new CEip712Verifier().intentDigest(i,CHAIN,CONTROLLER),digest);
    }
    @Test void exactShapeAndTwentyTwoOrderedFields()throws Exception{
        var d=data(intent(words()));assertEquals(List.of("domain","types","primaryType","message"),new ArrayList<>(d.keySet()));
        var node=JSON.valueToTree(d);assertEquals("CFuturesIntent",node.get("primaryType").asText());
        assertEquals(22,node.get("types").get("CFuturesIntent").size());assertEquals(22,node.get("message").size());
        var names=new ArrayList<String>();node.get("message").fieldNames().forEachRemaining(names::add);
        for(int k=0;k<22;k++)assertEquals(names.get(k),node.get("types").get("CFuturesIntent").get(k).get("name").asText());
        node.get("message").forEach(value->assertTrue(value.isTextual()));assertTrue(node.get("domain").get("chainId").isTextual());
    }
    @Test void everyFieldSurvivesJsonWithNoFinancialRecalculation()throws Exception{
        BigInteger[] w=words();for(int k=0;k<w.length;k++){BigInteger[] changed=w.clone();changed[k]=changed[k].add(BigInteger.ONE);
            Intent i=intent(changed);assertEquals(new CEip712Verifier().intentDigest(i,CHAIN,CONTROLLER),hash(data(i)));}
    }
    @Test void integersAboveJavascriptPrecisionAndUint256MaximumRemainExact()throws Exception{
        BigInteger[] w=words();w[1]=CAbiModels.UINT_MAX;w[12]=BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE);
        Intent i=intent(w);var d=CUserIntentTypedData.format(i,CAbiModels.UINT_MAX,CONTROLLER);
        var node=JSON.valueToTree(d);assertEquals(CAbiModels.UINT_MAX.toString(),node.get("message").get("expectedVersion").asText());
        assertEquals("9007199254740993",node.get("message").get("nonce").asText());
        assertEquals(new CEip712Verifier().intentDigest(i,CAbiModels.UINT_MAX,CONTROLLER),hash(d));
    }
    @Test void chainAndControllerAreSeparateDomains()throws Exception{
        Intent i=intent(words());String original=hash(data(i));assertNotEquals(original,hash(CUserIntentTypedData.format(i,CHAIN.add(BigInteger.ONE),CONTROLLER)));
        assertNotEquals(original,hash(CUserIntentTypedData.format(i,CHAIN,"0x"+"44".repeat(20))));
        assertNotEquals(new CEip712Verifier().reportDomain(CHAIN,CONTROLLER),new CEip712Verifier().intentDomain(CHAIN,CONTROLLER));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void returnedGraphIsDeeplyImmutable(){var d=data(intent(words()));
        assertThrows(UnsupportedOperationException.class,()->d.put("message",Map.of()));
        assertThrows(UnsupportedOperationException.class,()->((Map)d.get("domain")).put("chainId","1"));
        assertThrows(UnsupportedOperationException.class,()->((Map)d.get("message")).put("nonce","1"));
        var types=(Map)d.get("types");assertThrows(UnsupportedOperationException.class,()->types.put("CFuturesIntent",List.of()));
        var fields=(List)types.get("CFuturesIntent");assertThrows(UnsupportedOperationException.class,()->fields.clear());
        assertThrows(UnsupportedOperationException.class,()->((Map)fields.get(0)).put("type","bytes32"));
    }
    @Test void invalidDomainAndAbiSyntaxFailClosed(){Intent i=intent(words());
        for(BigInteger c:List.of(BigInteger.ZERO,BigInteger.ONE.negate(),CAbiModels.UINT_MAX.add(BigInteger.ONE)))
            assertThrows(IllegalArgumentException.class,()->CUserIntentTypedData.format(i,c,CONTROLLER));
        assertThrows(IllegalArgumentException.class,()->CUserIntentTypedData.format(i,null,CONTROLLER));
        for(String address:List.of("bad","0x"+"00".repeat(20)))assertThrows(IllegalArgumentException.class,()->CUserIntentTypedData.format(i,CHAIN,address));
        assertThrows(IllegalArgumentException.class,()->CUserIntentTypedData.format(null,CHAIN,CONTROLLER));
        BigInteger[] w=words();w[0]=n(256);assertThrows(IllegalArgumentException.class,()->intent(w));
        w[0]=n(0);w[12]=BigInteger.ONE.negate();assertThrows(IllegalArgumentException.class,()->intent(w));
    }
    @Test void formatterDoesNotClaimCurrentAuthorizationOrEconomicValidity()throws Exception{
        BigInteger[] w=words();w[13]=n(2000);w[14]=n(1);w[8]=n(999);w[9]=n(1);
        Intent i=intent(w);assertEquals(new CEip712Verifier().intentDigest(i,CHAIN,CONTROLLER),hash(data(i)));
    }
    public static void main(String[] args){
        if(args.length!=0)throw new IllegalArgumentException("C_TYPED_TEST_SCOPE");
        var request=org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request()
            .selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(CUserIntentTypedDataTest.class)).build();
        var listener=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();
        org.junit.platform.launcher.core.LauncherFactory.create().execute(request,listener);var s=listener.getSummary();
        var out=new java.io.PrintWriter(System.out,true);s.printTo(out);s.printFailuresTo(out);
        System.exit(s.getTestsFoundCount()==8&&s.getTestsSucceededCount()==8&&s.getTotalFailureCount()==0
            &&s.getTestsSkippedCount()==0&&s.getTestsAbortedCount()==0?0:1);
    }
}
