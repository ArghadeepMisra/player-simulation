package dev.fairplay.telemetry.detector.bridge;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Arena bridge, bound from {@code telemetry.arena.*}. The service password comes from
 * {@code TELEMETRY_SERVICE_PASSWORD} and is never hard-coded; when Arena is enabled a blank one fails startup.
 *
 * <p>DECISION: the spec uses {@code ArenaProperties} without defining it. These are the keys of the detector YAML in
 * spec section 6. The simulator has its own record of the same name for its registration keys.
 */
@ConfigurationProperties("telemetry.arena")
public record ArenaProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("telemetry-sim") String serviceUsername,
        @DefaultValue("") String servicePassword,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("2.0") double backoffMultiplier) {

    public ArenaProperties {
        if (enabled && (servicePassword == null || servicePassword.isBlank())) {
            throw new IllegalArgumentException(
                    "telemetry.arena.service-password must not be blank when Arena is enabled: set TELEMETRY_SERVICE_PASSWORD");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("telemetry.arena.base-url must not be blank");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("telemetry.arena.max-retries must not be negative");
        }
        if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("telemetry.arena.initial-backoff must be positive");
        }
        if (!(backoffMultiplier >= 1.0)) {
            throw new IllegalArgumentException("telemetry.arena.backoff-multiplier must be at least 1.0");
        }
    }
}
