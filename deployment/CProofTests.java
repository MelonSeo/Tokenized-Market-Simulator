import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Explicit pure C proof test selection, never RPC/canonical validation. */
public final class CProofTests {
    public static void main(String[] args) {
        if (args.length > 1) throw new IllegalArgumentException("C_PROOF_TEST_SCOPE");
        String selected = args.length == 0 ? "CEip712VerifierTest" : switch (args[0]) {
            case "transaction" -> "CSignedTransactionVerifierTest";
            case "rules" -> "CRulesTransitionVerifierTest";
            case "authorization" -> "CAuthorizationVerifierTest";
            case "envelope" -> "CReceiptEnvelopeVerifierTest";
            case "token" -> "CTokenLedgerReducerTest";
            case "user" -> "CTransactionProofVerifierTest";
            case "risk" -> "CTransactionRiskProofVerifierTest";
            case "vault" -> "CVaultTransactionProofVerifierTest";
            case "control" -> "CControllerControlProofVerifierTest";
            case "token-call" -> "CTokenTransactionProofVerifierTest";
            case "overlay" -> "CGlobalProofOverlayTest";
            default -> throw new IllegalArgumentException("C_PROOF_TEST_SCOPE");
        };
        String packageName = selected.equals("CTokenLedgerReducerTest")
            ? "com.pricetrack.exchange.custody.token." : "com.pricetrack.exchange.custody.proof.";
        var request = LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(packageName + selected));
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request.build(), listener);
        var summary = listener.getSummary();
        var writer = new PrintWriter(System.out, true);
        summary.printTo(writer); summary.printFailuresTo(writer);
        System.exit(summary.getTestsFoundCount() > 0 && summary.getTestsFoundCount() == summary.getTestsSucceededCount()
            && summary.getTotalFailureCount() == 0 && summary.getTestsAbortedCount() == 0
            && summary.getTestsSkippedCount() == 0 ? 0 : 1);
    }
}
