package dev.fairplay.telemetry.detector.topology;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fairplay.telemetry.common.json.JsonSerdes;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.junit.jupiter.api.Test;

/** Accumulation and rates of {@link AimStats} and {@link SpeedStats}. */
class StatsTest {

    private static final String P = TestEvents.player(1);

    private static MatchEvent shot(boolean hit, boolean headshot, int reactionMs) {
        return TestEvents.shot(P, 1, 1000, hit, headshot, reactionMs);
    }

    /** A POSITION event moving at {@code speed} units per second (dtMs is 50). */
    private static MatchEvent moving(double speed) {
        return TestEvents.position(P, 1, 1000, speed * 0.05, 0);
    }

    @Test
    void aimStatsAccumulateEveryShotButHitsAndHeadshotsOnlyWhenSet() {
        AimStats stats = AimStats.empty()
                .add(shot(true, true, 60))
                .add(shot(true, false, 80))
                .add(shot(false, false, 100));

        assertThat(stats).isEqualTo(new AimStats(3, 2, 1, 240));
        assertThat(stats.avgReactionMs()).isEqualTo(80.0);
        assertThat(stats.headshotRate()).isEqualTo(0.5);
    }

    @Test
    void aimStatsAreImmutable() {
        AimStats empty = AimStats.empty();

        empty.add(shot(true, true, 60));

        assertThat(empty).isEqualTo(new AimStats(0, 0, 0, 0));
    }

    @Test
    void aimRatesAreZeroWithoutDenominators() {
        assertThat(AimStats.empty().avgReactionMs()).isZero();
        assertThat(AimStats.empty().headshotRate()).isZero();
        assertThat(AimStats.empty().add(shot(false, false, 200)).headshotRate()).isZero();
    }

    @Test
    void aimStatsTreatMissingFieldsAsZero() {
        MatchEvent odd = new MatchEvent("e", "r", P, "m", 1, dev.fairplay.telemetry.common.model.EventType.SHOT, 1,
                null, null, null, null, null, null, null);

        assertThat(AimStats.empty().add(odd)).isEqualTo(new AimStats(1, 0, 0, 0));
    }

    @Test
    void speedStatsClassifyMovingAndOverspeedSamples() {
        SpeedStats stats = SpeedStats.empty()
                .add(moving(0.0), 0.5, 7.5)    // idle
                .add(moving(0.49), 0.5, 7.5)   // still idle
                .add(moving(0.5), 0.5, 7.5)    // moving, boundary inclusive
                .add(moving(7.5), 0.5, 7.5)    // moving, not overspeed (limit exclusive)
                .add(moving(11.0), 0.5, 7.5);  // overspeed

        assertThat(stats.movingSamples()).isEqualTo(3);
        assertThat(stats.overspeedSamples()).isEqualTo(1);
        assertThat(stats.maxSpeed()).isEqualTo(11.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.overspeedRatio()).isEqualTo(1 / 3.0);
    }

    @Test
    void speedIsDistanceOverTimeFromBothAxes() {
        MatchEvent diagonal = TestEvents.position(P, 1, 1000, 0.3, 0.4); // 0.5 units in 50 ms = 10 units/s

        assertThat(SpeedStats.speedOf(diagonal)).isEqualTo(10.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void speedRatioIsZeroWhenNothingMoved() {
        assertThat(SpeedStats.empty().overspeedRatio()).isZero();
        assertThat(SpeedStats.empty().add(moving(0), 0.5, 7.5).overspeedRatio()).isZero();
    }

    @Test
    void statsSurviveTheJsonSerdeUsedByTheWindowStores() {
        AimStats aim = new AimStats(5, 4, 3, 300);
        SpeedStats speed = new SpeedStats(40, 12, 9.25);

        var aimSerde = JsonSerdes.of(AimStats.class);
        var speedSerde = JsonSerdes.of(SpeedStats.class);

        assertThat(aimSerde.deserialize("t", aimSerde.serialize("t", aim))).isEqualTo(aim);
        assertThat(speedSerde.deserialize("t", speedSerde.serialize("t", speed))).isEqualTo(speed);
        assertThat(new String(aimSerde.serialize("t", aim))).doesNotContain("avgReactionMs", "headshotRate");
    }
}
