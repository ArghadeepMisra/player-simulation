package dev.fairplay.telemetry.detector.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import dev.fairplay.telemetry.common.model.DeadLetter;
import dev.fairplay.telemetry.common.model.EventType;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.apache.kafka.streams.KeyValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DeadLetterTopologyTest {

    private static final String P = TestEvents.player(1);
    private static final String OTHER = TestEvents.player(2);
    private static final long T0 = TopologyFixture.START.toEpochMilli();

    private final TopologyFixture fixture = new TopologyFixture();

    @AfterEach
    void close() {
        fixture.close();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void garbageBytesGoToTheDeadLetterTopicWithReasonOffsetAndBase64Payload() {
        byte[] garbage = bytes("{\"broken\":");

        fixture.events.pipeInput(P, garbage, T0);

        List<KeyValue<String, DeadLetter>> letters = fixture.deadLetters.readKeyValuesToList();
        assertThat(letters).hasSize(1);
        assertThat(letters.get(0).key).isEqualTo(P);
        DeadLetter letter = letters.get(0).value;
        assertThat(letter.sourceTopic()).isEqualTo("match-events");
        assertThat(letter.partition()).isZero();
        assertThat(letter.offset()).isZero();
        assertThat(letter.key()).isEqualTo(P);
        assertThat(Base64.getDecoder().decode(letter.payloadBase64())).isEqualTo(garbage);
        assertThat(letter.error()).isNotBlank();
        assertThat(letter.receivedAt()).isEqualTo(T0);
    }

    @Test
    void anInvalidEventGoesToTheDeadLetterTopic() {
        MatchEvent invalid = TestEvents.shot(P, 0, T0, true, false, 60); // seq 0 is never valid

        fixture.events.pipeInput(P, TestEvents.bytes(invalid), T0);

        DeadLetter letter = fixture.deadLetters.readValue();
        assertThat(letter.error()).startsWith("invalid event");
        assertThat(Base64.getDecoder().decode(letter.payloadBase64())).isEqualTo(TestEvents.bytes(invalid));
        assertThat(letter.offset()).isZero();
    }

    @Test
    void aKeyThatIsNotThePlayerIdGoesToTheDeadLetterTopic() {
        MatchEvent event = TestEvents.shot(P, 1, T0, true, false, 60);

        fixture.events.pipeInput(OTHER, TestEvents.bytes(event), T0);

        DeadLetter letter = fixture.deadLetters.readValue();
        assertThat(letter.error()).startsWith("key mismatch").contains(OTHER).contains(P);
        assertThat(letter.key()).isEqualTo(OTHER);
    }

    @Test
    void nullAndJsonNullPayloadsAreDeadLettered() {
        fixture.events.pipeInput(P, (byte[]) null, T0);
        fixture.events.pipeInput(P, bytes("null"), T0 + 1);

        List<DeadLetter> letters = fixture.deadLetters.readValuesToList();

        assertThat(letters).hasSize(2);
        assertThat(letters.get(0).error()).isEqualTo("null payload");
        assertThat(letters.get(0).payloadBase64()).isNull();
        assertThat(letters.get(1).error()).isEqualTo("payload is JSON null");
    }

    @Test
    void randomBytesAndUnknownEventTypesAreDeadLettered() {
        byte[] random = new byte[16];
        new java.util.Random(1).nextBytes(random);
        random[0] = (byte) 0xFF;

        fixture.events.pipeInput(P, random, T0);
        fixture.events.pipeInput(P, bytes("{\"playerId\":\"" + P + "\",\"type\":\"TELEPORT\",\"seq\":1,\"ts\":5}"), T0 + 1);

        assertThat(fixture.deadLetters.readValuesToList()).hasSize(2);
    }

    @Test
    void validEventsAfterBadOnesAreStillProcessedAndNeverDeadLettered() {
        fixture.events.pipeInput(P, bytes("{\"broken\":"), T0);
        fixture.events.pipeInput(P, TestEvents.bytes(TestEvents.shot(P, 0, T0, true, false, 60)), T0 + 1);
        fixture.events.pipeInput(OTHER, TestEvents.bytes(TestEvents.shot(P, 1, T0, true, false, 60)), T0 + 2);
        for (int seq = 1; seq <= 5; seq++) {
            fixture.send(TestEvents.shot(P, seq, T0 + 10L * seq, true, false, 60));
        }

        List<DeadLetter> letters = fixture.deadLetters.readValuesToList();

        assertThat(letters).hasSize(3);
        assertThat(letters).extracting(DeadLetter::offset).containsExactly(0L, 1L, 2L);
        assertThat(fixture.metrics.deadLettered.get()).isEqualTo(3);
        assertThat(fixture.metrics.parsed.get()).isEqualTo(5);
    }

    @Test
    void positionKillAndShotEventsAllParse() {
        fixture.send(TestEvents.position(P, 1, T0, 0.1, 0.1));
        fixture.send(TestEvents.shot(P, 2, T0 + 1, false, false, 200));
        fixture.send(TestEvents.kill(P, 3, T0 + 2, OTHER, true));

        assertThat(fixture.deadLetters.isEmpty()).isTrue();
        assertThat(fixture.metrics.parsed.get()).isEqualTo(3);
        assertThat(EventType.values()).hasSize(3);
    }
}
