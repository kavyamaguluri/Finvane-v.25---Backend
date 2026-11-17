package com.finvane;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

public class TestRunner {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("FIN VANE ERP: MATHEMATICAL PARITY UNIT TEST RUNNER");
        System.out.println("==================================================");

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectPackage("com.finvane"))
            .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);

        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));

        if (summary.getTotalFailureCount() > 0) {
            System.err.println("FAILED TESTS: " + summary.getTotalFailureCount());
            summary.getFailures().forEach(f -> {
                System.err.println("Failure in: " + f.getTestIdentifier().getDisplayName());
                f.getException().printStackTrace(System.err);
            });
            System.exit(1);
        } else {
            System.out.println("ALL PARITY TESTS PASSED SUCCESSFULLY!");
            System.exit(0);
        }
    }
}

