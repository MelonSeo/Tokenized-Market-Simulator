import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Pure C unit selector, not the existing application/PG/RPC test suite. */
public final class CProjectionTests {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
            DiscoverySelectors.selectClass("com.pricetrack.exchange.custody.projection.CCustodyReducerTest"),
            DiscoverySelectors.selectClass("com.pricetrack.exchange.custody.store.CCustodyStateCodecTest"));
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request.build(), listener);
        var summary = listener.getSummary();
        var writer = new PrintWriter(System.out, true);
        summary.printTo(writer);
        summary.printFailuresTo(writer);
        boolean pass = summary.getTestsFoundCount() > 0
            && summary.getTestsFoundCount() == summary.getTestsSucceededCount()
            && summary.getTotalFailureCount() == 0
            && summary.getTestsAbortedCount() == 0 && summary.getTestsSkippedCount() == 0;
        System.exit(pass ? 0 : 1);
    }
}
