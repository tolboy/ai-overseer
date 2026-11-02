package dev.oseer.testing;

import dev.oseer.dashboard.DecisionPage;

import java.nio.file.Path;

/**
 * Supervises a run from another process until its standard input closes: the other
 * `oseer decide`, or the Dashboard, that a supervisor in the suite has to step aside for.
 * Prints {@code held} or {@code busy} once it knows.
 */
public final class HoldsSupervision {
    public static void main(String[] args) throws Exception {
        try (DecisionPage.Supervision held = DecisionPage.supervise(Path.of(args[0]), args[1])) {
            System.out.println(held == null ? "busy" : "held");
            System.out.flush();
            while (System.in.read() >= 0) {
                // Held until the suite closes this end.
            }
        }
    }
}
