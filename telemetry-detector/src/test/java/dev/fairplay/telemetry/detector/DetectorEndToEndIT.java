package dev.fairplay.telemetry.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.model.DeadLetter;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.SkillTier;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import dev.fairplay.telemetry.common.support.KafkaTestContainer;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real detector against a real Kafka 4 broker: one obvious cheater and one honest player, and one garbage record.
 * Production settings apply (64 MB cache, exactly_once_v2), so windows emit at commit time and the first flag may come
 * from a different window than in the TopologyTestDriver tests. The assertions are therefore about who is flagged and
 * how often, not about exact evidence.
 */
@SpringBootTest(classes = DetectorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.kafka.streams.application-id=detector-e2e-${random.uuid}",
                "spring.kafka.streams.properties.commit.interval.ms=100",
                "spring.kafka.streams.properties.num.standby.replicas=0",
                "spring.kafka.streams.properties.state.dir=${java.io.tmpdir}/ks-${random.uuid}"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DetectorEndToEndIT {

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainer.KAFKA::getBootstrapServers);
    }

    @Autowired
    StreamsBuilderFactoryBean streams;

    @Test
    void flagsTheCheaterOnceLeavesTheHonestPlayerAloneAndDeadLettersGarbage() throws Exception {
        String cheater = UUID.randomUUID().toString();
        String honest = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();

        await().atMost(Duration.ofSeconds(60)).until(() ->
                streams.getKafkaStreams() != null && streams.getKafkaStreams().state() == KafkaStreams.State.RUNNING);

        try (KafkaProducer<String, byte[]> producer = KafkaIt.producer()) {
            KafkaIt.sendJson(producer, Topics.PLAYER_PROFILES, cheater,
                    new PlayerProfile(cheater, "it_cheater", "IN", SkillTier.GOLD, now), now - 60_000);
            KafkaIt.sendJson(producer, Topics.PLAYER_PROFILES, honest,
                    new PlayerProfile(honest, "it_honest", "US", SkillTier.SILVER, now), now - 60_000);
            producer.flush();
            Thread.sleep(2000); // let the profile table load before the flags arrive

            // The cheater: 15 hits in 2.8 s, 13 of them headshots, 60 ms reaction.
            for (int i = 0; i < 15; i++) {
                KafkaIt.send(producer, TestEvents.shot(cheater, i + 1, now - 10_000 + i * 200L, true, i >= 2, 60));
            }
            // The honest player: 60 s of shots, 35% hit rate, 240 ms reaction.
            for (int i = 0; i < 60; i++) {
                boolean hit = i % 20 < 7;
                KafkaIt.send(producer, TestEvents.shot(honest, i + 1, now - 10_000 + i * 200L, hit, false, 240));
            }
            // One record that is not JSON at all, keyed by the cheater.
            producer.send(new ProducerRecord<>(Topics.MATCH_EVENTS, null, now - 5_000, cheater,
                    "{\"broken\":".getBytes(StandardCharsets.UTF_8)));
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(flagsFor(cheater)).isNotEmpty());
        Thread.sleep(3000); // a duplicate flag would have arrived by now

        List<SuspiciousPlayer> cheaterFlags = flagsFor(cheater);
        assertThat(cheaterFlags).hasSize(1);
        assertThat(cheaterFlags.get(0).rule()).isEqualTo(Rule.AIMBOT);
        assertThat(cheaterFlags.get(0).username()).isEqualTo("it_cheater");
        assertThat(cheaterFlags.get(0).skillTier()).isEqualTo(SkillTier.GOLD);
        assertThat(flagsFor(honest)).isEmpty();

        List<DeadLetter> letters = KafkaIt.readValues(Topics.MATCH_EVENTS_DLT, DeadLetter.class).stream()
                .filter(l -> cheater.equals(l.key())).toList();
        assertThat(letters).hasSize(1);
        assertThat(new String(Base64.getDecoder().decode(letters.get(0).payloadBase64()), StandardCharsets.UTF_8))
                .isEqualTo("{\"broken\":");
        assertThat(letters.get(0).sourceTopic()).isEqualTo(Topics.MATCH_EVENTS);
        assertThat(letters.get(0).partition()).isBetween(0, 5);
    }

    @Test
    void topicsAndInternalTopicsHaveTheExpectedShapeOnARealBroker() throws Exception {
        await().atMost(Duration.ofSeconds(60)).until(() ->
                streams.getKafkaStreams() != null && streams.getKafkaStreams().state() == KafkaStreams.State.RUNNING);

        Map<String, Integer> partitions = KafkaIt.partitionCounts();

        assertThat(partitions).containsEntry(Topics.MATCH_EVENTS, 6)
                .containsEntry(Topics.PLAYER_PROFILES, 6)
                .containsEntry(Topics.SUSPICIOUS_PLAYERS, 6)
                .containsEntry(Topics.INTEGRITY_STATS, 6)
                .containsEntry(Topics.MATCH_EVENTS_DLT, 6)
                .containsEntry(Topics.MATCH_SUMMARIES, 3)
                .containsEntry(Topics.CHEATER_GROUND_TRUTH, 1);
        // The window branches add no repartition topic. The single named one is co-partitioned with player-profiles.
        assertThat(partitions.keySet()).noneMatch(name -> name.contains("-windows-repartition"));
        assertThat(partitions).anySatisfy((name, count) -> {
            assertThat(name).endsWith("-flags-repartition");
            assertThat(count).isEqualTo(partitions.get(Topics.PLAYER_PROFILES));
        });
    }

    private static List<SuspiciousPlayer> flagsFor(String playerId) {
        return KafkaIt.readValues(Topics.SUSPICIOUS_PLAYERS, SuspiciousPlayer.class).stream()
                .filter(f -> playerId.equals(f.playerId())).toList();
    }
}
