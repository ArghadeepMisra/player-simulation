package dev.fairplay.telemetry.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.RunInfo;
import dev.fairplay.telemetry.common.model.Segment;
import dev.fairplay.telemetry.common.support.KafkaTestContainer;
import dev.fairplay.telemetry.simulator.engine.SimulationEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real simulator against a real Kafka 4 broker: 40 players for 6 seconds with 2-second matches and no
 * malformed records. Checks the one promise the verifier will rely on later: every player's events arrive as seq
 * 1..N in order with no gaps and no duplicates, and the manifest agrees with what is in the topic.
 */
@SpringBootTest(classes = SimulatorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "telemetry.sim.players=40",
                "telemetry.sim.duration=6s",
                "telemetry.sim.match-duration=2s",
                "telemetry.sim.cheat-start-min=1s",
                "telemetry.sim.cheat-start-max=3s",
                "telemetry.sim.malformed-ratio=0"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SimulatorIT {

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainer.KAFKA::getBootstrapServers);
    }

    @Autowired
    SimulationEngine engine;

    @Autowired
    MeterRegistry registry;

    @Test
    void everyPlayersEventsArriveInOrderWithNoGapsAndTheManifestAgrees() throws Exception {
        RunInfo info = engine.completion().get(90, TimeUnit.SECONDS);

        assertThat(info.players()).isEqualTo(40);
        assertThat(info.totalFailed()).isZero();
        assertThat(info.malformedSent()).isZero();
        assertThat(info.totalAcked()).isPositive();
        assertThat(info.endedAt()).isGreaterThan(info.startedAt());

        List<ConsumerRecord<String, byte[]>> raw = readTopic(Topics.MATCH_EVENTS);
        List<ConsumerRecord<String, byte[]>> mine = raw.stream()
                .filter(r -> JsonSerde.MAPPER.readValue(r.value(), MatchEvent.class).runId().equals(info.runId()))
                .toList();
        assertThat(mine).hasSize((int) info.totalAcked());

        // The manifest entry of every player agrees with the topic: seq 1..finalSeq, in order, keyed by the player.
        Map<String, ManifestEntry> manifest = readTopic(Topics.SIMULATION_MANIFEST).stream()
                .filter(r -> !r.key().equals(Topics.RUN_KEY))
                .map(r -> JsonSerde.MAPPER.readValue(r.value(), ManifestEntry.class))
                .filter(e -> e.runId().equals(info.runId()))
                .collect(Collectors.toMap(ManifestEntry::playerId, e -> e));
        assertThat(manifest).hasSize(40);

        Map<String, List<Long>> seqsByPlayer = new HashMap<>();
        for (ConsumerRecord<String, byte[]> record : mine) {
            MatchEvent event = JsonSerde.MAPPER.readValue(record.value(), MatchEvent.class);
            assertThat(record.key()).isEqualTo(event.playerId());
            assertThat(record.timestamp()).isEqualTo(event.ts());
            seqsByPlayer.computeIfAbsent(event.playerId(), k -> new ArrayList<>()).add(event.seq());
        }
        manifest.forEach((playerId, entry) -> {
            List<Long> seqs = seqsByPlayer.getOrDefault(playerId, List.of());
            assertThat(seqs).as("seqs of %s", playerId)
                    .isEqualTo(LongStream.rangeClosed(1, entry.finalSeq()).boxed().toList());
            assertThat(entry.acked()).isEqualTo(entry.finalSeq());
            assertThat(entry.failed()).isZero();
        });
        // The run record is stored under its own key.
        assertThat(readTopic(Topics.SIMULATION_MANIFEST).stream().filter(r -> r.key().equals(Topics.RUN_KEY))
                .map(r -> JsonSerde.MAPPER.readValue(r.value(), RunInfo.class)).toList()).contains(info);
    }

    @Test
    void profilesGroundTruthAndFinishedMatchesArePublished() throws Exception {
        RunInfo info = engine.completion().get(90, TimeUnit.SECONDS);

        List<PlayerProfile> profiles = readTopic(Topics.PLAYER_PROFILES).stream()
                .map(r -> JsonSerde.MAPPER.readValue(r.value(), PlayerProfile.class)).toList();
        assertThat(profiles).hasSize(40);
        assertThat(profiles).extracting(PlayerProfile::username).doesNotHaveDuplicates();

        List<GroundTruth> truth = readTopic(Topics.CHEATER_GROUND_TRUTH).stream()
                .map(r -> JsonSerde.MAPPER.readValue(r.value(), GroundTruth.class)).toList();
        assertThat(truth).hasSize(40).allMatch(t -> t.runId().equals(info.runId()));
        // round(40 x 0.05) = 2 cheaters, each with a start time, and round(40 x 0.02) = 1 pro.
        assertThat(truth.stream().filter(t -> t.segment() == Segment.CHEATER)).hasSize(2)
                .allMatch(t -> t.cheatStartsAt() != null);
        assertThat(truth.stream().filter(t -> t.segment() == Segment.PRO)).hasSize(1);

        List<MatchSummary> summaries = readTopic(Topics.MATCH_SUMMARIES).stream()
                .map(r -> JsonSerde.MAPPER.readValue(r.value(), MatchSummary.class)).toList();
        // 6 s of play with 2 s matches: every one of the 4 shards finishes at least one match.
        assertThat(summaries.size()).isGreaterThanOrEqualTo(4);
        assertThat(summaries).allSatisfy(s -> {
            assertThat(s.externalId()).startsWith("sim-" + info.runId() + "-");
            assertThat(s.participants()).hasSize(10);
        });
        assertThat(summaries).extracting(MatchSummary::externalId).doesNotHaveDuplicates();

        // The dashboard's metrics: both cheaters started cheating (within 3 s of a 6 s run) and matches were counted.
        assertThat(registry.get("telemetry.sim.players.cheating").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("telemetry.sim.matches.completed").counter().count())
                .isEqualTo((double) summaries.size());
    }

    /** Everything currently in {@code topic}, from the beginning, read as committed. */
    private static List<ConsumerRecord<String, byte[]>> readTopic(String topic) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "sim-it-reader-" + UUID.randomUUID());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream()
                    .map(i -> new TopicPartition(i.topic(), i.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);

            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!ends.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue())
                    && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }
}
