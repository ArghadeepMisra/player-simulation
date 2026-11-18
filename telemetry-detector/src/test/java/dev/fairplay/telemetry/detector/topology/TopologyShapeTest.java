package dev.fairplay.telemetry.detector.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import dev.fairplay.telemetry.common.Topics;
import org.apache.kafka.streams.TopologyDescription;
import org.junit.jupiter.api.Test;

/** Pins the shape of the topology: what it reads, what it writes, and that it never touches the ground truth. */
class TopologyShapeTest {

    private static Set<String> sourceTopics(TopologyDescription description) {
        Set<String> topics = new HashSet<>();
        for (TopologyDescription.Subtopology sub : description.subtopologies()) {
            for (TopologyDescription.Node node : sub.nodes()) {
                if (node instanceof TopologyDescription.Source source && source.topicSet() != null) {
                    topics.addAll(source.topicSet());
                }
            }
        }
        return topics;
    }

    private static Set<String> sinkTopics(TopologyDescription description) {
        Set<String> topics = new HashSet<>();
        for (TopologyDescription.Subtopology sub : description.subtopologies()) {
            for (TopologyDescription.Node node : sub.nodes()) {
                if (node instanceof TopologyDescription.Sink sink && sink.topic() != null) {
                    topics.add(sink.topic());
                }
            }
        }
        return topics;
    }

    @Test
    void readsOnlyEventsProfilesAndTheInternalFlagsRepartitionTopic() {
        try (TopologyFixture fixture = new TopologyFixture()) {
            assertThat(sourceTopics(fixture.topology.describe())).containsExactlyInAnyOrder(
                    Topics.MATCH_EVENTS, Topics.PLAYER_PROFILES, TelemetryTopology.FLAGS_REPARTITION + "-repartition");
        }
    }

    @Test
    void addsNoRepartitionTopicInFrontOfTheWindows() {
        // KStream#process marks its output as key-changing; grouping it would rewrite every event through the broker.
        try (TopologyFixture fixture = new TopologyFixture()) {
            String description = fixture.topology.describe().toString();

            assertThat(description).doesNotContain("aimbot-windows-repartition")
                    .doesNotContain("speedhack-windows-repartition");
        }
    }

    @Test
    void publishesFlagsIntegrityStatsAndDeadLettersOnly() {
        try (TopologyFixture fixture = new TopologyFixture()) {
            assertThat(sinkTopics(fixture.topology.describe())).contains(Topics.SUSPICIOUS_PLAYERS,
                    Topics.INTEGRITY_STATS, Topics.MATCH_EVENTS_DLT);
        }
    }

    @Test
    void neverMentionsTheGroundTruthTopic() {
        try (TopologyFixture fixture = new TopologyFixture()) {
            assertThat(fixture.topology.describe().toString()).doesNotContain(Topics.CHEATER_GROUND_TRUTH);
            assertThat(sourceTopics(fixture.topology.describe())).doesNotContain(Topics.CHEATER_GROUND_TRUTH);
            assertThat(sinkTopics(fixture.topology.describe())).doesNotContain(Topics.CHEATER_GROUND_TRUTH);
        }
    }

    @Test
    void writesDeadLettersToTheDeadLetterTopic() {
        try (TopologyFixture fixture = new TopologyFixture()) {
            assertThat(sinkTopics(fixture.topology.describe())).contains(Topics.MATCH_EVENTS_DLT);
        }
    }
}
