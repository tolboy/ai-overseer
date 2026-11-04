package dev.oseer.testing;

import java.util.List;

/**
 * Explicit suite registry rather than classpath scanning: a test that is not listed here is
 * a test that does not run, and that fact is visible in a diff.
 */
public final class TestMain {

    public static void main(String[] args) {
        List<Suite> suites = List.of(
                new dev.oseer.JsonTest(),
                new dev.oseer.JsonFileTest(),
                new dev.oseer.execution.EffortCapabilitiesTest(),
                new dev.oseer.FindingsTest(),
                new dev.oseer.SchemaTest(),
                new dev.oseer.YamlTest(),
                new dev.oseer.ConfigTest(),
                new dev.oseer.config.ConfigFileStoreTest(),
                new dev.oseer.RuntimeTest(),
                new dev.oseer.WorktreeLeaseTest(),
                new dev.oseer.DirectCaptureTest(),
                new dev.oseer.LiveCliOutputTest(),
                new dev.oseer.ProfileVerifierTest(),
                new dev.oseer.ModelCheckTest(),
                new dev.oseer.ModelSettingsTest(),
                new dev.oseer.VendorSettingsTest(),
                new dev.oseer.execution.ModelCatalogLifecycleTest(),
                new dev.oseer.ServiceBindingTest(),
                new dev.oseer.ServiceMigrationTest(),
                new dev.oseer.RoleRosterEditorTest(),
                new dev.oseer.DecisionRoutingTest(),
                new dev.oseer.DecisionCompatibilityTest(),
                new dev.oseer.ModelCatalogUiTest(),
                new dev.oseer.ModelSelectionTest(),
                new dev.oseer.ModelRoutingLoopTest(),
                new dev.oseer.TaskRoutingLoopTest(),
                new dev.oseer.RoleResolverTest(),
                new dev.oseer.QuotaSignalTest(),
                new dev.oseer.VendorAvailabilityTest(),
                new dev.oseer.RoleRunnerTest(),
                new dev.oseer.WorkflowTest(),
                new dev.oseer.TaskLoopTest(),
                new dev.oseer.HeartbeatTest(),
                new dev.oseer.LandMessageTest(),
                new dev.oseer.LandCommandTest(),
                new dev.oseer.DoCommandTest(),
                new dev.oseer.PlannerTest(),
                new dev.oseer.run.PreparationAccountingTest(),
                new dev.oseer.run.InterruptionGuardTest(),
                new dev.oseer.ShutdownResumeTest(),
                new dev.oseer.run.VendorCallFailedWordingTest(),
                new dev.oseer.VisualQaTest(),
                new dev.oseer.OrcaClientTest(),
                new dev.oseer.OrcaIsolationTest(),
                new dev.oseer.LaunchSettingsTest(),
                new dev.oseer.DashboardTest(),
                new dev.oseer.ConfigViewTest(),
                new dev.oseer.NextRunTest(),
                new dev.oseer.SettingsEditorTest(),
                new dev.oseer.RunAnalyticsTest(),
                new dev.oseer.OrcaSettlementTest(),
                new dev.oseer.OrcaLifecycleTest(),
                new dev.oseer.OrcaWorkspaceTest(),
                new dev.oseer.OrcaDesktopTest(),
                new dev.oseer.OrcaRunIdentityTest(),
                new dev.oseer.OrcaRecoveryTest(),
                new dev.oseer.OrcaStartTest(),
                new dev.oseer.OrcaDecisionGateTest(),
                new dev.oseer.NextStepTest(),
                new dev.oseer.RestoreContractTest(),
                new dev.oseer.LocalHttpExecutorTest(),
                new dev.oseer.ConnectionTest(),
                new dev.oseer.execution.OsCredentialStoreTest(),
                new dev.oseer.execution.OpenRouterLoginTest(),
                new dev.oseer.execution.CodingRelayTest(),
                new dev.oseer.CodingAttestationTest(),
                new dev.oseer.HarnessFailureClassificationTest(),
                new dev.oseer.execution.VerifiedOpenCodeExecutorTest(),
                new dev.oseer.execution.OpenCodeLimitsTest(),
                new dev.oseer.ConnectionServiceTest(),
                new dev.oseer.ConnectionSettingsUiTest(),
                new dev.oseer.DashboardConnectionsTest(),
                new dev.oseer.ApiExecutorTest(),
                new dev.oseer.execution.ApiResilienceTest(),
                new dev.oseer.execution.ApiEffortTransportTest(),
                new dev.oseer.ApiConfigTest(),
                new dev.oseer.ApiFallbackTest(),
                new dev.oseer.ApiFallbackLoopTest(),
                new dev.oseer.OpenCodeHarnessTest(),
                new dev.oseer.ApiContextTest(),
                new dev.oseer.ProcessEnvironmentTest(),
                new dev.oseer.execution.ApiReceiptTest(),
                new dev.oseer.LedgerTest(),
                new dev.oseer.MeasurementAccountingTest(),
                new dev.oseer.LedgerCompareTest(),
                new dev.oseer.PairedEvaluationTest(),
                new dev.oseer.HomeCorpusTest(),
                new dev.oseer.CorpusReaderTest(),
                new dev.oseer.PilotConfigTest(),
                new dev.oseer.PilotPrepareTest(),
                new dev.oseer.ApprovalStoreTest(),
                new dev.oseer.StatusCommandTest(),
                new dev.oseer.InitializerTest(),
                new dev.oseer.RosterTest(),
                new dev.oseer.RunOverrideTest(),
                new dev.oseer.OperatorContinuationTest(),
                new dev.oseer.DecisionPageTest(),
                new dev.oseer.RunStartUnderOrcaTest()
        );

        Check check = new Check();
        for (String requested : args) {
            if (suites.stream().noneMatch(suite -> suite.name().equals(requested))) {
                throw new IllegalArgumentException("Unknown suite: " + requested);
            }
        }
        long registryStarted = System.nanoTime();
        for (Suite suite : suites) {
            if (args.length > 0 && java.util.Arrays.stream(args).noneMatch(suite.name()::equals)) continue;
            check.beginSuite(suite.name());
            int passedBefore = check.passed();
            int failedBefore = check.failures().size();
            long suiteStarted = System.nanoTime();
            System.out.println("[suite] " + suite.name() + " starting");
            System.out.flush();
            try {
                suite.run(check);
            } catch (Exception failure) {
                check.that(suite.name() + " threw " + failure, false);
            }
            System.out.printf(java.util.Locale.ROOT,
                    "[suite] %s: %d passed, %d failed; elapsed_ms=%.1f%n",
                    suite.name(), check.passed() - passedBefore,
                    check.failures().size() - failedBefore,
                    (System.nanoTime() - suiteStarted) / 1_000_000.0);
        }

        System.out.println();
        for (Check.Failure failure : check.failures()) {
            System.out.println("  FAIL  [" + failure.suite() + "] " + failure.description());
            System.out.println("        " + failure.detail());
        }
        System.out.printf(java.util.Locale.ROOT, "test_elapsed_ms=%.1f%n",
                (System.nanoTime() - registryStarted) / 1_000_000.0);
        System.out.printf("%nchecks: %d passed, %d failed%n", check.passed(), check.failures().size());
        if (!check.failures().isEmpty()) System.exit(1);
    }
}
