import java.nio.file.*;
import java.util.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Explicit 122 no-socket tests; does not start the demo application or Oracle server. */
public final class CDirectDemoTests {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("C_DIRECT_TEST_SCOPE");
        for(String name:List.of("c-consent-schema.sql","c-nonce-admission-schema.sql","c-direct-execution-schema.sql")){
            try(var in=CDirectDemoTests.class.getResourceAsStream("/"+name)){
                if(in==null||!Arrays.equals(in.readAllBytes(),Files.readAllBytes(Path.of(args[0],name))))throw new IllegalStateException("C_DIRECT_RESOURCE_CLASSPATH");
            }
        }
        var classes=List.of("com.pricetrack.exchange.custody.authorization.CConsentModelsTest",
            "com.pricetrack.exchange.custody.authorization.CConsentStoreTest","com.pricetrack.exchange.custody.runtime.CCustodyIdentityTest",
            "com.pricetrack.exchange.custody.runtime.CCustodyRpcReaderTest","com.pricetrack.exchange.custody.runtime.CAdmissionPrestateTest",
            "com.pricetrack.exchange.custody.authorization.CNonceAdmissionStoreTest","com.pricetrack.exchange.custody.authorization.CReportCandidateBuilderTest",
            "com.pricetrack.exchange.custody.direct.CDirectServiceTest","com.pricetrack.exchange.custody.direct.CDirectControllerTest",
            "com.pricetrack.exchange.custody.direct.CDirectOracleClientTest","com.pricetrack.exchange.custody.direct.CDirectReceiptReaderTest",
            "com.pricetrack.exchange.custody.runtime.CCustodyRpcClientTest","CDirectOracleServerTest");
        var listener=new SummaryGeneratingListener();LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
            .selectors(classes.stream().map(DiscoverySelectors::selectClass).toList()).build(),listener);
        var s=listener.getSummary();var out=new java.io.PrintWriter(System.out,true);s.printTo(out);s.printFailuresTo(out);
        boolean ok=s.getTestsFoundCount()==122&&s.getTestsSucceededCount()==122&&s.getTotalFailureCount()==0&&s.getTestsSkippedCount()==0&&s.getTestsAbortedCount()==0;
        System.exit(ok?0:1);
    }
}
