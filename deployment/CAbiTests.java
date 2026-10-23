import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Fixed pure ABI selectors; not a receipt, signature or canonical proof. */
public final class CAbiTests {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("C_ABI_TEST_SCOPE");
        String selected = switch (args[0]) {
            case "calls" -> "CAbiCodecTest";
            case "events" -> "CAbiEventCodecTest";
            default -> throw new IllegalArgumentException("C_ABI_TEST_SCOPE");
        };
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
            DiscoverySelectors.selectClass("com.pricetrack.exchange.custody.abi." + selected));
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request.build(), listener);
        var summary = listener.getSummary();
        var writer = new PrintWriter(System.out, true);
        summary.printTo(writer); summary.printFailuresTo(writer);
        boolean pass = summary.getTestsFoundCount() > 0
            && summary.getTestsFoundCount() == summary.getTestsSucceededCount()
            && summary.getTotalFailureCount() == 0 && summary.getTestsAbortedCount() == 0
            && summary.getTestsSkippedCount() == 0;
        System.exit(pass ? 0 : 1);
    }
}
