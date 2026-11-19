package dev.fairplay.telemetry.simulator.arena;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Arena (Project 1) connection settings, bound from {@code telemetry.arena.*}. The service password comes from
 * {@code TELEMETRY_SERVICE_PASSWORD} and is never hard-coded; when Arena is enabled a blank one fails startup.
 *
 * <p>DECISION: the spec uses {@code ArenaProperties} without defining it. The fields are exactly the keys of the
 * simulator YAML in spec section 6. The detector has its own record of the same name with the keys it needs
 * (retry settings instead of registration settings), because the two apps share no module for it.
 */
@ConfigurationProperties("telemetry.arena")
public record ArenaProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("telemetry-sim") String serviceUsername,
        @DefaultValue("") String servicePassword,
        @DefaultValue("sim-player-pass") String playerPassword,
        @DefaultValue("16") int registerConcurrency,
        @DefaultValue("./sim-players.json") String mappingFile) {

    public ArenaProperties {
        if (enabled && (servicePassword == null || servicePassword.isBlank())) {
            throw new IllegalArgumentException(
                    "telemetry.arena.service-password must not be blank when Arena is enabled: set TELEMETRY_SERVICE_PASSWORD");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("telemetry.arena.base-url must not be blank");
        }
        if (registerConcurrency < 1) {
            throw new IllegalArgumentException("telemetry.arena.register-concurrency must be positive");
        }
        if (mappingFile == null || mappingFile.isBlank()) {
            throw new IllegalArgumentException("telemetry.arena.mapping-file must not be blank");
        }
        // Arena requires 8 to 72 characters for a password.
        if (playerPassword == null || playerPassword.length() < 8 || playerPassword.length() > 72) {
            throw new IllegalArgumentException("telemetry.arena.player-password must be 8 to 72 characters");
        }
    }
}
