import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Typed C storage tests only; no application, RPC, signer or chain connection. */
public final class CPostgresTests {
    public static void main(String[] args) {
        if (!"true".equals(System.getenv("C_CUSTODY_PG_TESTS")))
            throw new IllegalStateException("C_EXPLICIT_PG_OPT_IN_REQUIRED");
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
            DiscoverySelectors.selectClass("com.pricetrack.exchange.custody.store.CCustodyProjectionStorePostgresTest"));
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
