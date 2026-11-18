package dev.fairplay.telemetry.detector;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Detection tunables bound from {@code telemetry.detection.*}. Startup fails when a rule is broken.
 *
 * <p>Rules are checked in compact constructors, not with Bean Validation annotations, because
 * Hibernate Validator has no {@code @Positive} for {@link Duration} and the window rules compare two fields.
 */
@ConfigurationProperties("telemetry.detection")
public record DetectionProperties(
        String detectorVersion,
        Duration cooldown,
        Duration integrityEmitInterval,
        Aimbot aimbot,
        Speedhack speedhack) {

    public DetectionProperties {
        if (detectorVersion == null || detectorVersion.isBlank()) {
            throw invalid("detector-version must not be blank");
        }
        positive(cooldown, "cooldown");
        positive(integrityEmitInterval, "integrity-emit-interval");
        if (aimbot == null || speedhack == null) {
            throw invalid("aimbot and speedhack settings are required");
        }
    }

    /** Aimbot rule: a hopping window over SHOT events. */
    public record Aimbot(
            Duration windowSize,
            Duration advance,
            Duration grace,
            int minHits,
            double maxAvgReactionMs,
            double minHeadshotRate) {

        public Aimbot {
            window(windowSize, advance, grace, "aimbot");
            positive(minHits, "aimbot.min-hits");
            positive(maxAvgReactionMs, "aimbot.max-avg-reaction-ms");
            ratio(minHeadshotRate, "aimbot.min-headshot-rate");
        }
    }

    /** Speedhack rule: a hopping window over POSITION events. */
    public record Speedhack(
            Duration windowSize,
            Duration advance,
            Duration grace,
            int minMovingSamples,
            double movingSpeedFloor,
            double speedLimit,
            double minOverspeedRatio) {

        public Speedhack {
            window(windowSize, advance, grace, "speedhack");
            positive(minMovingSamples, "speedhack.min-moving-samples");
            positive(movingSpeedFloor, "speedhack.moving-speed-floor");
            positive(speedLimit, "speedhack.speed-limit");
            ratio(minOverspeedRatio, "speedhack.min-overspeed-ratio");
        }
    }

    private static void window(Duration size, Duration advance, Duration grace, String rule) {
        positive(size, rule + ".window-size");
        positive(advance, rule + ".advance");
        positive(grace, rule + ".grace");
        // A hopping window cannot advance by more than its size, or Kafka Streams rejects the topology.
        if (advance.compareTo(size) > 0) {
            throw invalid(rule + ".advance must not be greater than " + rule + ".window-size");
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(name + " must be positive");
        }
    }

    private static void positive(double value, String name) {
        if (!(value > 0)) {
            throw invalid(name + " must be positive");
        }
    }

    private static void ratio(double value, String name) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw invalid(name + " must be between 0.0 and 1.0");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("telemetry.detection." + message);
    }
}
