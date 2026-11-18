package dev.fairplay.telemetry.detector;

import java.time.Duration;

/** The default detection settings, for tests. */
public final class TestDetection {

    private TestDetection() {
    }

    public static DetectionProperties defaults() {
        return new DetectionProperties("1.0.0", Duration.ofMinutes(10), Duration.ofSeconds(5),
                new DetectionProperties.Aimbot(Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(2),
                        12, 140, 0.62),
                new DetectionProperties.Speedhack(Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(2),
                        40, 0.5, 7.5, 0.15));
    }
}
