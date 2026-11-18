package dev.fairplay.telemetry.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.support.KafkaTestContainer;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 20 players x 500 gapless events: every player must end at count 500 with no duplicates and no gaps. A negative
 * control then re-sends one sequence number, to prove the tracker would notice a duplicate if one ever happened.
 */
@SpringBootTest(classes = DetectorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.kafka.streams.application-id=exactly-once-${random.uuid}",
                "spring.kafka.streams.properties.commit.interval.ms=100",
                "spring.kafka.streams.properties.num.standby.replicas=0",
                "spring.kafka.streams.properties.state.dir=${java.io.tmpdir}/ks-${random.uuid}",
                "telemetry.detection.integrity-emit-interval=1s"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExactlyOnceIT {

    private static final int PLAYERS = 20;
    private static final int EVENTS = 500;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainer.KAFKA::getBootstrapServers);
    }

    @Autowired
    StreamsBuilderFactoryBean streams;

    /** The latest committed stats of each of our players. */
    private static Map<String, IntegrityStats> latest(List<String> players) {
        Map<String, IntegrityStats> latest = new HashMap<>();
        for (IntegrityStats s : KafkaIt.readValues(Topics.INTEGRITY_STATS, IntegrityStats.class)) {
            if (players.contains(s.playerId())) {
                latest.put(s.playerId(), s);
            }
        }
        return latest;
    }

    @Test
    void everyPlayerCountsExactlyItsEventsAndADuplicateIsDetected() {
        List<String> players = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            players.add(UUID.randomUUID().toString());
        }
        long base = Instant.now().toEpochMilli() - 60_000;

        await().atMost(Duration.ofSeconds(60)).until(() ->
                streams.getKafkaStreams() != null && streams.getKafkaStreams().state() == KafkaStreams.State.RUNNING);

        try (KafkaProducer<String, byte[]> producer = KafkaIt.producer()) {
            // Idle POSITION events, so the speedhack rule stays quiet. Interleave players like real traffic.
            for (int seq = 1; seq <= EVENTS; seq++) {
                for (String player : players) {
                    KafkaIt.send(producer, TestEvents.position(player, seq, base + seq * 50L, 0, 0));
                }
            }
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            Map<String, IntegrityStats> stats = latest(players);
            assertThat(stats).hasSize(PLAYERS);
            assertThat(stats.values()).allSatisfy(s -> assertThat(s.count()).isEqualTo(EVENTS));
        });

        Map<String, IntegrityStats> stats = latest(players);
        assertThat(stats.values()).allSatisfy(s -> {
            assertThat(s.lastSeq()).isEqualTo(EVENTS);
            assertThat(s.duplicates()).isZero();
            assertThat(s.gaps()).isZero();
        });

        // Negative control: send seq 250 of the first player again.
        String victim = players.get(0);
        try (KafkaProducer<String, byte[]> producer = KafkaIt.producer()) {
            KafkaIt.send(producer, TestEvents.position(victim, 250, base + 250 * 50L, 0, 0));
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            IntegrityStats s = latest(players).get(victim);
            assertThat(s.duplicates()).isEqualTo(1);
            assertThat(s.count()).isEqualTo(EVENTS);
            assertThat(s.gaps()).isZero();
        });
        assertThat(latest(players).get(players.get(1)).duplicates()).isZero();
    }
}
