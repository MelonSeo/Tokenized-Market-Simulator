import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Explicit offline C service test selection. No production application scanning. */
public final class CReadWalletTests {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("C_READ_TEST_SCOPE");
        String base = "com.pricetrack.exchange.custody.";
        String[] selected = {
            "runtime.CCustodyIdentityTest", "runtime.CCustodyRpcReaderTest",
            "runtime.CCustodyRpcClientTest", "runtime.CCustodyRuntimeConfigTest",
            "auth.CWalletLinkStoreTest", "auth.CWalletProofServiceTest",
            "api.CCustodyControllerTest"
        };
        var request = LauncherDiscoveryRequestBuilder.request();
        for (String name : selected) request.selectors(DiscoverySelectors.selectClass(base + name));
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request.build(), listener);
        var summary = listener.getSummary();
        var writer = new PrintWriter(System.out, true);
        summary.printTo(writer);
        summary.printFailuresTo(writer);
        System.exit(summary.getTestsFoundCount() > 0
            && summary.getTestsFoundCount() == summary.getTestsSucceededCount()
            && summary.getTotalFailureCount() == 0
            && summary.getTestsSkippedCount() == 0
            && summary.getTestsAbortedCount() == 0 ? 0 : 1);
    }
}
