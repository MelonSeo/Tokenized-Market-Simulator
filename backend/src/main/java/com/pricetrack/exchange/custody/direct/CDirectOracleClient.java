package com.pricetrack.exchange.custody.direct;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.custody.authorization.CReportCandidateBuilder;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.*;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import okhttp3.*;

/** Fixed internal Oracle bridge. No key, arbitrary URL/hash signing, redirect or retry. */
public final class CDirectOracleClient implements CDirectOraclePort,AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String token;private final Call.Factory calls;private final OkHttpClient owned;
    public CDirectOracleClient(String token){this(token,new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .callTimeout(Duration.ofSeconds(3)).connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(3))
        .addNetworkInterceptor(chain->{Response r=chain.proceed(chain.request());if(r.code()!=200){r.close();throw new IOException("C_ORACLE_HTTP_STATUS");}return r;}).build());}
    private CDirectOracleClient(String token,OkHttpClient client){this(token,client,client);}
    CDirectOracleClient(String token,Call.Factory calls){this(token,calls,null);}
    private CDirectOracleClient(String token,Call.Factory calls,OkHttpClient owned){
        if(token==null||!token.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("C_ORACLE_TOKEN_FORMAT");
        this.token=token;this.calls=Objects.requireNonNull(calls);this.owned=owned;
    }
    @Override public String sign(CCustodyIdentity id,CReportCandidateBuilder.Candidate candidate){
        try{
            String expected=new CEip712Verifier().reportDigest(candidate.report(),id.chainId(),id.rules().address());
            if(!expected.equals(candidate.reportDigest()))throw failure();
            byte[] body=JSON.writeValueAsBytes(Map.of("deploymentId",id.deploymentId(),"report",candidate.report()));
            if(body.length>16384)throw failure();
            Request request=new Request.Builder().url("http://oracle:8091/sign").header("X-TMS-C-Oracle-Token",token)
                .header("Connection","close").post(RequestBody.create(body,MediaType.get("application/json"))).build();
            try(Response response=calls.newCall(request).execute()){
                if(response.code()!=200||response.body()==null)throw failure();
                byte[] bytes=response.body().byteStream().readNBytes(8193);if(bytes.length>8192)throw failure();
                JsonNode result=JSON.readTree(bytes);
                if(result==null||!result.isObject()||result.size()!=4||!id.deploymentId().equals(result.path("deploymentId").asText())
                    ||!expected.equals(result.path("reportDigest").asText())||!id.oracle().equals(result.path("oracle").asText()))throw failure();
                String signature=result.path("signature").asText();
                new CEip712Verifier().verifyReport(candidate.report(),id.chainId(),id.rules().address(),id.oracle(),signature);
                return signature;
            }
        }catch(Exception ignored){throw failure();}
    }
    private static CCustodyFailure failure(){return new CCustodyFailure("C_DIRECT_ORACLE_UNAVAILABLE_STOP_NO_RETRY",503);}
    @Override public void close(){if(owned!=null){owned.dispatcher().executorService().shutdownNow();owned.connectionPool().evictAll();}}
}
