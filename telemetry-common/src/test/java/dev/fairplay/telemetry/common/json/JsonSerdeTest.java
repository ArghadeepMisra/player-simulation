package dev.fairplay.telemetry.common.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.DeadLetter;
import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.model.ParticipantSummary;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.RunInfo;
import dev.fairplay.telemetry.common.model.Segment;
import dev.fairplay.telemetry.common.model.SkillTier;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serde;
import org.junit.jupiter.api.Test;

class JsonSerdeTest {

    private static final String P = TestEvents.player(1);

    private static <T> void assertRoundTrip(Serde<T> serde, T value) {
        byte[] bytes = serde.serializer().serialize("t", value);
        assertThat(serde.deserializer().deserialize("t", bytes)).isEqualTo(value);
    }

    @Test
    void roundTripsEveryEventType() {
        assertRoundTrip(JsonSerdes.matchEvent(), TestEvents.shot(P, 1, 1000, true, false, 231));
        assertRoundTrip(JsonSerdes.matchEvent(), TestEvents.position(P, 2, 1050, 0.25, -0.5));
        assertRoundTrip(JsonSerdes.matchEvent(), TestEvents.kill(P, 3, 1100, TestEvents.player(2), true));
    }

    @Test
    void roundTripsEveryContractRecord() {
        assertRoundTrip(JsonSerdes.profile(), new PlayerProfile(P, "sim_0001", "IN", SkillTier.GOLD, 1763372000000L));
        assertRoundTrip(JsonSerdes.groundTruth(),
                new GroundTruth("r-9f2c41", P, Segment.CHEATER, CheatType.AIMBOT_SUBTLE, 1763372151000L));
        assertRoundTrip(JsonSerdes.groundTruth(), new GroundTruth("r-9f2c41", P, Segment.HONEST, CheatType.NONE, null));
        assertRoundTrip(JsonSerdes.manifestEntry(), new ManifestEntry("r-9f2c41", P, 7342, 7342, 0));
        assertRoundTrip(JsonSerdes.runInfo(), new RunInfo("r-9f2c41", 1763372100000L, 1763372400000L, 1000,
                7518234, 0, 7519));
        assertRoundTrip(JsonSerdes.integrity(), new IntegrityStats(P, 7342, 7342, 0, 0, 1763372405000L));
        assertRoundTrip(JsonSerdes.deadLetter(), new DeadLetter("match-events", 4, 918273, P, "eyJicm9rZW4i", "bad", 5L));
        assertRoundTrip(JsonSerdes.matchSummary(), new MatchSummary("sim-r-9f2c41-000017", "FFA", "Rust Yard",
                "2026-11-17T10:00:00Z", "2026-11-17T10:02:00Z",
                List.of(new ParticipantSummary(P, 1, 14, 3, 6, 540, 170, 228))));
        assertRoundTrip(JsonSerdes.suspicious(), new SuspiciousPlayer("7c1e", P, "sim_0042", SkillTier.GOLD,
                Rule.AIMBOT, 0.874, 1763372150000L, 1763372155000L, 1763372155412L,
                Evidence.aimbot(23, 17, 14, 0.8235, 61.4), "1.0.0"));
        assertRoundTrip(JsonSerdes.suspicious(), new SuspiciousPlayer("7c1f", P, null, null,
                Rule.SPEEDHACK, 0.6, 1L, 2L, 3L, Evidence.speedhack(50, 20, 0.4, 11.0), "1.0.0"));
    }

    @Test
    void omitsNullFieldsAndKeepsContractFieldNames() {
        String json = new String(JsonSerdes.suspicious().serializer().serialize("t",
                new SuspiciousPlayer("f", P, null, null, Rule.SPEEDHACK, 0.5, 1, 2, 3,
                        Evidence.speedhack(40, 6, 0.15, 8.0), "1.0.0")), StandardCharsets.UTF_8);

        assertThat(json)
                .contains("\"flagId\"", "\"rule\":\"SPEEDHACK\"", "\"movingSamples\":40", "\"detectorVersion\"")
                .doesNotContain("username", "skillTier", "headshotRate");
    }

    @Test
    void doesNotSerializeIsValidAsAProperty() {
        String json = new String(TestEvents.bytes(TestEvents.shot(P, 1, 1000, true, true, 60)), StandardCharsets.UTF_8);

        assertThat(json).doesNotContain("valid").doesNotContain("dx").doesNotContain("victimId");
    }

    @Test
    void nullValueSerializesToNullAndNullBytesDeserializeToNull() {
        assertThat(JsonSerdes.matchEvent().serializer().serialize("t", null)).isNull();
        assertThat(JsonSerdes.matchEvent().deserializer().deserialize("t", null)).isNull();
    }

    @Test
    void garbageBytesThrowSerializationException() {
        var deserializer = JsonSerdes.matchEvent().deserializer();

        assertThatThrownBy(() -> deserializer.deserialize("t", "{\"broken\":".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SerializationException.class)
                .hasCauseInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> deserializer.deserialize("t", new byte[] {1, 2, 3, 4}))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    void unknownFieldsAreIgnored() {
        byte[] json = ("{\"playerId\":\"" + P + "\",\"username\":\"u\",\"skillTier\":\"PRO\",\"registeredAt\":1,"
                + "\"somethingNew\":42}").getBytes(StandardCharsets.UTF_8);

        PlayerProfile profile = JsonSerdes.profile().deserializer().deserialize("t", json);

        assertThat(profile.username()).isEqualTo("u");
        assertThat(profile.skillTier()).isEqualTo(SkillTier.PRO);
    }

    @Test
    void unserializableValueThrowsSerializationException() {
        var serde = JsonSerdes.of(Unserializable.class);

        assertThatThrownBy(() -> serde.serializer().serialize("t", new Unserializable()))
                .isInstanceOf(SerializationException.class);
    }

    /** Jackson wraps the exception thrown by this getter in a JacksonException. */
    public static class Unserializable {
        public String getBoom() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void serdeIsItsOwnSerializerAndDeserializerAndConfigureIsANoOp() {
        var serde = JsonSerdes.matchEvent();

        assertThat(serde.serializer()).isSameAs(serde);
        assertThat(serde.deserializer()).isSameAs(serde);
        serde.configure(java.util.Map.of(), false);
        serde.close();
    }
}
