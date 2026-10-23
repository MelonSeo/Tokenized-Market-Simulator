import java.nio.file.*;
import java.util.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Explicit offline tests only. Never starts an app, network listener or transaction sender. */
public final class CNonceAdmissionTests {
    private static final List<String> CLASSES = List.of(
        "com.pricetrack.exchange.custody.authorization.CConsentModelsTest",
        "com.pricetrack.exchange.custody.authorization.CConsentStoreTest",
        "com.pricetrack.exchange.custody.runtime.CCustodyIdentityTest",
        "com.pricetrack.exchange.custody.runtime.CCustodyRpcReaderTest",
        "com.pricetrack.exchange.custody.runtime.CAdmissionPrestateTest",
        "com.pricetrack.exchange.custody.authorization.CNonceAdmissionStoreTest");
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("C_ADMISSION_TEST_SCOPE");
        for(String name:List.of("c-consent-schema.sql","c-nonce-admission-schema.sql")){
            try(var in=CNonceAdmissionTests.class.getResourceAsStream("/"+name)){
                if(in==null||!Arrays.equals(in.readAllBytes(),Files.readAllBytes(Path.of(args[0],name))))
                    throw new IllegalStateException("C_ADMISSION_RESOURCE_CLASSPATH");
            }
        }
        var selectors=CLASSES.stream().map(DiscoverySelectors::selectClass).toList();
        var request=LauncherDiscoveryRequestBuilder.request().selectors(selectors).build();
        var listener=new SummaryGeneratingListener();
        LauncherFactory.create().execute(request,listener);
        var s=listener.getSummary();var out=new java.io.PrintWriter(System.out,true);
        s.printTo(out);s.printFailuresTo(out);
        // Explicit six-class suite: consent14 + identity/reader12 + admission reader15/store14.
        boolean ok=s.getTestsFoundCount()==55&&s.getTestsSucceededCount()==55
            &&s.getTotalFailureCount()==0&&s.getTestsSkippedCount()==0&&s.getTestsAbortedCount()==0;
        System.exit(ok?0:1);
    }
}
