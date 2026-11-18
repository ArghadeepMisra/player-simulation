package dev.fairplay.telemetry.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TopicsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(TopicsConfig.class);

    private static Map<String, NewTopic> topicsOf(org.springframework.context.ApplicationContext ctx) {
        return ctx.getBeansOfType(NewTopic.class).values().stream()
                .collect(Collectors.toMap(NewTopic::name, t -> t));
    }

    @Test
    void declaresTheNineTopicsWithPartitionsAndCleanupPolicies() {
        runner.run(ctx -> {
            Map<String, NewTopic> topics = topicsOf(ctx);

            assertThat(topics).hasSize(9);
            assertTopic(topics, Topics.MATCH_EVENTS, 6, "delete", TopicsConfig.ONE_DAY_MS);
            assertTopic(topics, Topics.MATCH_EVENTS_DLT, 6, "delete", TopicsConfig.SEVEN_DAYS_MS);
            assertTopic(topics, Topics.PLAYER_PROFILES, 6, "compact", null);
            assertTopic(topics, Topics.SUSPICIOUS_PLAYERS, 6, "delete", TopicsConfig.SEVEN_DAYS_MS);
            assertTopic(topics, Topics.INTEGRITY_STATS, 6, "compact", null);
            assertTopic(topics, Topics.MATCH_SUMMARIES, 3, "delete", TopicsConfig.SEVEN_DAYS_MS);
            assertTopic(topics, Topics.MATCH_SUMMARIES_DLT, 3, "delete", TopicsConfig.SEVEN_DAYS_MS);
            assertTopic(topics, Topics.CHEATER_GROUND_TRUTH, 1, "compact", null);
            assertTopic(topics, Topics.SIMULATION_MANIFEST, 1, "compact", null);
        });
    }

    @Test
    void replicationFactorComesFromProperties() {
        runner.withPropertyValues("telemetry.topics.replication-factor=3").run(ctx ->
                assertThat(topicsOf(ctx).values()).allSatisfy(t -> assertThat(t.replicationFactor()).isEqualTo((short) 3)));
    }

    @Test
    void replicationFactorDefaultsToOne() {
        runner.run(ctx ->
                assertThat(topicsOf(ctx).values()).allSatisfy(t -> assertThat(t.replicationFactor()).isEqualTo((short) 1)));
    }

    @Test
    void nonPositiveReplicationFactorIsRejected() {
        assertThatThrownBy(() -> new TopicsProperties(0)).isInstanceOf(IllegalArgumentException.class);
        runner.withPropertyValues("telemetry.topics.replication-factor=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    private static void assertTopic(Map<String, NewTopic> topics, String name, int partitions, String cleanup,
                                    String retentionMs) {
        NewTopic topic = topics.get(name);
        assertThat(topic).as(name).isNotNull();
        assertThat(topic.numPartitions()).as(name + " partitions").isEqualTo(partitions);
        assertThat(topic.configs()).as(name + " cleanup").containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, cleanup);
        if (retentionMs != null) {
            assertThat(topic.configs()).as(name + " retention").containsEntry(TopicConfig.RETENTION_MS_CONFIG, retentionMs);
        }
    }
}
