package dev.fairplay.telemetry.common;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** One {@link NewTopic} per topic this project uses. Spring's KafkaAdmin creates the missing ones at startup. */
@Configuration
@EnableConfigurationProperties(TopicsProperties.class)
public class TopicsConfig {

    static final String ONE_DAY_MS = "86400000";
    static final String SEVEN_DAYS_MS = "604800000";

    private final int replicationFactor;

    public TopicsConfig(TopicsProperties properties) {
        this.replicationFactor = properties.replicationFactor();
    }

    @Bean
    NewTopic matchEventsTopic() {
        return deleteAfter(Topics.MATCH_EVENTS, Topics.PLAYER_PARTITIONS, ONE_DAY_MS);
    }

    @Bean
    NewTopic matchEventsDltTopic() {
        return deleteAfter(Topics.MATCH_EVENTS_DLT, Topics.PLAYER_PARTITIONS, SEVEN_DAYS_MS);
    }

    @Bean
    NewTopic playerProfilesTopic() {
        return compacted(Topics.PLAYER_PROFILES, Topics.PLAYER_PARTITIONS);
    }

    @Bean
    NewTopic suspiciousPlayersTopic() {
        return deleteAfter(Topics.SUSPICIOUS_PLAYERS, Topics.PLAYER_PARTITIONS, SEVEN_DAYS_MS);
    }

    @Bean
    NewTopic integrityStatsTopic() {
        return compacted(Topics.INTEGRITY_STATS, Topics.PLAYER_PARTITIONS);
    }

    @Bean
    NewTopic matchSummariesTopic() {
        return deleteAfter(Topics.MATCH_SUMMARIES, 3, SEVEN_DAYS_MS);
    }

    @Bean
    NewTopic matchSummariesDltTopic() {
        return deleteAfter(Topics.MATCH_SUMMARIES_DLT, 3, SEVEN_DAYS_MS);
    }

    @Bean
    NewTopic cheaterGroundTruthTopic() {
        return compacted(Topics.CHEATER_GROUND_TRUTH, 1);
    }

    @Bean
    NewTopic simulationManifestTopic() {
        return compacted(Topics.SIMULATION_MANIFEST, 1);
    }

    private NewTopic deleteAfter(String name, int partitions, String retentionMs) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, retentionMs)
                .build();
    }

    private NewTopic compacted(String name, int partitions) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .compact()
                .build();
    }
}
