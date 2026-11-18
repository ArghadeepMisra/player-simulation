package dev.fairplay.telemetry.simulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.MatchEvent;
import org.junit.jupiter.api.Test;

class MalformedInjectorTest {

    private final SplittableRandom rng = new SplittableRandom(21);

    private static boolean parsesIntoAValidEvent(byte[] bytes) {
        try {
            MatchEvent event = JsonSerde.MAPPER.readValue(bytes, MatchEvent.class);
            return event != null && event.isValid();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void allThreeKindsAreProducedInRotation() {
        MalformedInjector injector = new MalformedInjector(1.0);

        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            payloads.add(injector.maybeNext(rng).orElseThrow());
        }

        assertThat(new String(payloads.get(0), StandardCharsets.UTF_8)).isEqualTo("{\"broken\":");
        assertThat(new String(payloads.get(1), StandardCharsets.UTF_8))
                .isEqualTo("{\"playerId\":\"not-a-uuid\",\"type\":\"SHOT\"}");
        assertThat(payloads.get(2)).hasSize(16);
        assertThat(payloads.get(3)).isEqualTo(payloads.get(0));
        assertThat(payloads.get(4)).isEqualTo(payloads.get(1));
        assertThat(payloads.get(5)).hasSize(16).isNotEqualTo(payloads.get(2));
    }

    @Test
    void noPayloadParsesIntoAValidEvent() {
        MalformedInjector injector = new MalformedInjector(1.0);

        for (int i = 0; i < 3000; i++) {
            assertThat(parsesIntoAValidEvent(injector.maybeNext(rng).orElseThrow())).isFalse();
        }
    }

    @Test
    void ratioZeroNeverInjects() {
        MalformedInjector injector = new MalformedInjector(0.0);

        for (int i = 0; i < 10_000; i++) {
            assertThat(injector.maybeNext(rng)).isEqualTo(Optional.empty());
        }
    }

    @Test
    void injectionRateFollowsTheRatio() {
        MalformedInjector injector = new MalformedInjector(0.001);

        long injected = java.util.stream.IntStream.range(0, 1_000_000)
                .filter(i -> injector.maybeNext(rng).isPresent()).count();

        assertThat(injected).isBetween(800L, 1200L);
    }

    @Test
    void returnedBuffersCannotCorruptTheSharedTemplates() {
        MalformedInjector injector = new MalformedInjector(1.0);

        injector.maybeNext(rng).orElseThrow()[0] = 0;

        assertThat(new String(injector.maybeNext(rng).orElseThrow(), StandardCharsets.UTF_8))
                .isEqualTo("{\"playerId\":\"not-a-uuid\",\"type\":\"SHOT\"}");
        assertThat(MalformedInjector.TRUNCATED_JSON[0]).isEqualTo((byte) '{');
    }
}
