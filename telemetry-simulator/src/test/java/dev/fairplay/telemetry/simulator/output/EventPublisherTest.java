package dev.fairplay.telemetry.simulator.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.RunInfo;
import dev.fairplay.telemetry.common.model.Segment;
import dev.fairplay.telemetry.common.model.SkillTier;
import dev.fairplay.telemetry.common.support.MutableClock;
import dev.fairplay.telemetry.common.support.TestEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class EventPublisherTest {

    private static final long NOW = 1_763_372_123_456L;

    private KafkaTemplate<String, byte[]> template;
    private ManifestTracker manifest;
    private SimpleMeterRegistry registry;
    private EventPublisher publisher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        template = mock(KafkaTemplate.class);
        manifest = new ManifestTracker();
        registry = new SimpleMeterRegistry();
        publisher = new EventPublisher(template, manifest, registry, new MutableClock(Instant.ofEpochMilli(NOW)));
    }

    private ProducerRecord<String, byte[]> sent() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        return captor.getValue();
    }

    private void sendSucceeds() {
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    private double count(String name, String... tags) {
        return registry.counter(name, tags).count();
    }

    @Test
    void publishesAnEventKeyedByPlayerWithTheEventTimeAsRecordTimestamp() {
        sendSucceeds();
        String player = TestEvents.player(1);
        MatchEvent event = TestEvents.shot(player, 7, NOW - 50, true, false, 200);

        publisher.publish(event);

        ProducerRecord<String, byte[]> record = sent();
        assertThat(record.topic()).isEqualTo(Topics.MATCH_EVENTS);
        assertThat(record.key()).isEqualTo(player);
        assertThat(record.timestamp()).isEqualTo(NOW - 50);
        assertThat(record.partition()).isNull();
        assertThat(JsonSerde.MAPPER.readValue(record.value(), MatchEvent.class)).isEqualTo(event);
    }

    @Test
    void anAcknowledgedSendIsCountedInTheManifestAndTheMetrics() {
        sendSucceeds();
        String player = TestEvents.player(1);

        publisher.publish(TestEvents.shot(player, 7, NOW, true, false, 200));
        publisher.publish(TestEvents.position(player, 8, NOW, 0.1, 0.2));

        assertThat(manifest.ackedOf(player)).isEqualTo(2);
        assertThat(manifest.highestAckedSeq(player)).isEqualTo(8);
        assertThat(manifest.failedOf(player)).isZero();
        assertThat(count("telemetry.sim.events.sent", "type", "SHOT")).isEqualTo(1);
        assertThat(count("telemetry.sim.events.sent", "type", "POSITION")).isEqualTo(1);
        assertThat(count("telemetry.sim.send.failures")).isZero();
    }

    @Test
    void aFailedSendIsCountedAsAFailureAndNotAsAnAck() {
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, byte[]>>failedFuture(new IllegalStateException("boom")));
        String player = TestEvents.player(2);

        publisher.publish(TestEvents.shot(player, 1, NOW, true, false, 200));

        assertThat(manifest.failedOf(player)).isEqualTo(1);
        assertThat(manifest.ackedOf(player)).isZero();
        assertThat(count("telemetry.sim.send.failures")).isEqualTo(1);
        assertThat(count("telemetry.sim.events.sent", "type", "SHOT")).isZero();
    }

    @Test
    void aSendThatThrowsIsCountedAsAFailureToo() {
        when(template.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("buffer exhausted"));
        String player = TestEvents.player(3);

        publisher.publish(TestEvents.shot(player, 1, NOW, true, false, 200));

        assertThat(manifest.failedOf(player)).isEqualTo(1);
        assertThat(count("telemetry.sim.send.failures")).isEqualTo(1);
    }

    @Test
    void malformedRecordsAreCountedButNeverEnterTheManifest() {
        sendSucceeds();
        String player = TestEvents.player(4);
        byte[] garbage = "{\"broken\":".getBytes(StandardCharsets.UTF_8);

        publisher.publishMalformed(player, garbage);

        ProducerRecord<String, byte[]> record = sent();
        assertThat(record.topic()).isEqualTo(Topics.MATCH_EVENTS);
        assertThat(record.key()).isEqualTo(player);
        assertThat(record.value()).isEqualTo(garbage);
        assertThat(record.timestamp()).isEqualTo(NOW);
        assertThat(publisher.malformedSent()).isEqualTo(1);
        assertThat(count("telemetry.sim.malformed.sent")).isEqualTo(1);
        assertThat(manifest.totalAcked()).isZero();
        assertThat(manifest.totalFailed()).isZero();
    }

    @Test
    void profilesAndGroundTruthAreKeyedByPlayerId() {
        sendSucceeds();
        String player = TestEvents.player(5);

        publisher.publishProfile(new PlayerProfile(player, "sim_0005", "IN", SkillTier.GOLD, NOW), NOW - 1);

        ProducerRecord<String, byte[]> record = sent();
        assertThat(record.topic()).isEqualTo(Topics.PLAYER_PROFILES);
        assertThat(record.key()).isEqualTo(player);
        assertThat(record.timestamp()).isEqualTo(NOW - 1);
    }

    @Test
    void groundTruthGoesToItsOwnTopic() {
        sendSucceeds();
        String player = TestEvents.player(5);

        publisher.publishGroundTruth(new GroundTruth("r-1", player, Segment.CHEATER, CheatType.AIMBOT, NOW), NOW);

        ProducerRecord<String, byte[]> record = sent();
        assertThat(record.topic()).isEqualTo(Topics.CHEATER_GROUND_TRUTH);
        assertThat(record.key()).isEqualTo(player);
    }

    @Test
    void summariesAreKeyedByMatchExternalId() {
        sendSucceeds();

        publisher.publishSummary(new MatchSummary("sim-r-1-000001", "FFA", "Harbor", "2026-11-17T10:00:00Z",
                "2026-11-17T10:02:00Z", List.of()));

        ProducerRecord<String, byte[]> record = sent();
        assertThat(record.topic()).isEqualTo(Topics.MATCH_SUMMARIES);
        assertThat(record.key()).isEqualTo("sim-r-1-000001");
        assertThat(count("telemetry.sim.matches.completed")).isEqualTo(1);
        assertThat(record.timestamp()).isEqualTo(NOW);
    }

    @Test
    void manifestEntriesUseThePlayerIdAndRunInfoUsesTheRunKey() {
        sendSucceeds();

        publisher.publishManifestEntry(new ManifestEntry("r-1", "p1", 10, 10, 0), NOW);
        publisher.publishRunInfo(new RunInfo("r-1", NOW - 1000, NOW, 1, 10, 0, 0), NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, org.mockito.Mockito.times(2)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(ProducerRecord::topic)
                .containsOnly(Topics.SIMULATION_MANIFEST);
        assertThat(captor.getAllValues()).extracting(ProducerRecord::key).containsExactly("p1", Topics.RUN_KEY);
    }

    @Test
    void flushDelegatesToTheTemplate() {
        publisher.flush();

        verify(template).flush();
    }
}
