package dev.fairplay.telemetry.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("telemetry.topics")
public record TopicsProperties(@DefaultValue("1") int replicationFactor) {

    public TopicsProperties {
        // Checked here instead of with @Positive so telemetry-common needs no validation starter.
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("telemetry.topics.replication-factor must be positive");
        }
    }
}
