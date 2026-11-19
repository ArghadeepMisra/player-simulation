package dev.fairplay.telemetry.verifier;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Verifier tunables, bound from {@code telemetry.verifier.*}. Validated in the constructor like the other
 * property records, because Hibernate Validator has no {@code @Positive} for {@link Duration}.
 *
 * <p>DECISION: {@code settlePollInterval} (default 5 s, the detector's integrity emit interval) is not in the spec's
 * YAML. The spec says "re-reads every 5 s"; making it a property lets tests poll fast.
 */
@ConfigurationProperties("telemetry.verifier")
public record VerifierProperties(
        @DefaultValue("./reports") String reportsDir,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue("5s") Duration settlePollInterval) {

    public VerifierProperties {
        if (reportsDir == null || reportsDir.isBlank()) {
            throw new IllegalArgumentException("telemetry.verifier.reports-dir must not be blank");
        }
        positive(readTimeout, "read-timeout");
        positive(settlePollInterval, "settle-poll-interval");
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("telemetry.verifier." + name + " must be positive");
        }
    }
}
