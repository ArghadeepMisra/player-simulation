package dev.fairplay.telemetry.simulator;

import java.time.Duration;

/** The default simulator settings, for tests. */
public final class TestSimProps {

    private TestSimProps() {
    }

    public static SimulatorProperties defaults() {
        return of(42, 1000);
    }

    public static SimulatorProperties of(long seed, int players) {
        return new SimulatorProperties(seed, players, Duration.ofMinutes(5), 10, Duration.ofSeconds(120), 20,
                4.5, 0.25, 4, 0.05, 0.02, Duration.ofSeconds(10), Duration.ofSeconds(60), 0.001);
    }
}
