package dev.oseer.cli;

import dev.oseer.config.UserConfig;
import dev.oseer.execution.orca.OrcaDecisionGate;
import dev.oseer.execution.orca.OrcaLifecycle;
import dev.oseer.process.ProcessRunner;
import dev.oseer.run.RunReadiness;
import dev.oseer.run.TaskRouting;

import java.io.IOException;
import java.nio.file.Path;

/** Dependencies shared by decision import, observation and continuation. */
public final class ApproveEnv {
    @FunctionalInterface
    public interface Clock {
        long nowMillis();
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    public interface Gates {
        OrcaDecisionGate.Answer read(Path root, OrcaLifecycle.Gate gate) throws Exception;
    }

    final Clock clock;
    final Sleeper sleeper;
    final Gates gates;
    private final Path configHome;
    final RunReadiness readiness;
    final TaskRouting taskRouting;
    final RunObservation observation;

    public ApproveEnv(Clock clock, Sleeper sleeper, Gates gates, Path configHome) {
        this(clock, sleeper, gates, configHome, null);
    }

    public ApproveEnv(Clock clock, Sleeper sleeper, Gates gates, Path configHome, RunReadiness readiness) {
        this(clock, sleeper, gates, configHome, readiness, null, RunObservation.live());
    }

    private ApproveEnv(Clock clock, Sleeper sleeper, Gates gates, Path configHome, RunReadiness readiness,
                       TaskRouting taskRouting, RunObservation observation) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.gates = gates;
        this.configHome = configHome;
        this.readiness = readiness;
        this.taskRouting = taskRouting;
        this.observation = observation;
    }

    public ApproveEnv withObservation(RunObservation source) {
        return new ApproveEnv(clock, sleeper, gates, configHome, readiness, taskRouting, source);
    }

    UserConfig user() throws IOException {
        return configHome == null ? UserConfig.load() : UserConfig.load(configHome);
    }

    public static ApproveEnv realtime() {
        ProcessRunner processes = new ProcessRunner();
        OrcaDecisionGate gate = new OrcaDecisionGate(processes);
        return new ApproveEnv(System::currentTimeMillis, Thread::sleep, gate::read, null,
                RunReadiness.live(processes), TaskRouting.live(), RunObservation.live());
    }
}
