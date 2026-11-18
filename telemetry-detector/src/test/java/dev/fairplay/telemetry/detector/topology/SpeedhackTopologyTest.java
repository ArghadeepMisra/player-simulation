package dev.fairplay.telemetry.detector.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpeedhackTopologyTest {

    private static final String P = TestEvents.player(1);
    private static final long T0 = TopologyFixture.START.toEpochMilli();

    private final TopologyFixture fixture = new TopologyFixture();

    @AfterEach
    void close() {
        fixture.close();
    }

    /** One POSITION every 50 ms moving at {@code speed} units per second along x. Returns the next seq. */
    private long run(long firstSeq, long startTs, int count, double speed) {
        for (int i = 0; i < count; i++) {
            fixture.send(TestEvents.position(P, firstSeq + i, startTs + i * 50L, speed * 0.05, 0));
        }
        return firstSeq + count;
    }

    @Test
    void sixtyPositionsAtSpeedElevenProduceOneSpeedhackFlag() {
        run(1, T0, 60, 11.0);

        List<SuspiciousPlayer> flags = fixture.flags.readValuesToList();

        assertThat(flags).hasSize(1);
        SuspiciousPlayer flag = flags.get(0);
        assertThat(flag.rule()).isEqualTo(Rule.SPEEDHACK);
        assertThat(flag.playerId()).isEqualTo(P);
        // The 40th moving sample (t = 1.95 s) first satisfies the rule, in the earliest window holding it.
        assertThat(flag.windowStart()).isEqualTo(T0 - 3000);
        assertThat(flag.windowEnd()).isEqualTo(T0 + 2000);
        assertThat(flag.evidence()).isEqualTo(Evidence.speedhack(40, 40, 1.0, 11.0));
        assertThat(flag.score()).isEqualTo(1.0);
        assertThat(fixture.metrics.speedhackFlags.get()).isEqualTo(1);
    }

    @Test
    void speedsBetweenSixPointNineAndSevenPointThreeDoNotFlag() {
        long seq = 1;
        double[] speeds = {6.9, 7.0, 7.1, 7.2, 7.3};
        for (int round = 0; round < 40; round++) {
            for (double speed : speeds) {
                seq = run(seq, T0 + (seq - 1) * 50L, 1, speed);
            }
        }

        assertThat(fixture.flags.isEmpty()).isTrue();
    }

    @Test
    void thirtyNineMovingSamplesAreNotEnough() {
        run(1, T0, 39, 11.0);

        assertThat(fixture.flags.isEmpty()).isTrue();
    }

    @Test
    void idleSamplesDoNotCountAsMovingAndDoNotDiluteTheRatio() {
        // 60 idle samples, then 7 at speed 11 and 33 at a normal speed 4. That is 40 moving samples with 7 over the
        // limit (0.175 >= 0.15). If idle samples counted, the ratio would be 7 / 100 and nothing would fire.
        long seq = run(1, T0, 60, 0.0);
        seq = run(seq, T0 + 3000, 7, 11.0);
        run(seq, T0 + 3000 + 7 * 50L, 33, 4.0);

        List<SuspiciousPlayer> flags = fixture.flags.readValuesToList();

        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).evidence().movingSamples()).isEqualTo(40);
        assertThat(flags.get(0).evidence().overspeedSamples()).isEqualTo(7);
    }

    @Test
    void onlyIdleSamplesNeverFlag() {
        run(1, T0, 400, 0.0);

        assertThat(fixture.flags.isEmpty()).isTrue();
    }

    @Test
    void aSecondRunInsideTheCooldownDoesNotFlagAgain() {
        long seq = run(1, T0, 60, 11.0);
        assertThat(fixture.flags.readValuesToList()).hasSize(1);

        fixture.advanceTime(java.time.Duration.ofSeconds(20));
        run(seq, T0 + 20_000, 60, 11.0);

        assertThat(fixture.flags.isEmpty()).isTrue();
    }

    @Test
    void anAimbotFlagDoesNotHideASpeedhackFlag() {
        // The cooldown key is playerId|rule, so one rule's flag never suppresses the other's.
        for (int i = 0; i < 15; i++) {
            fixture.send(TestEvents.shot(P, 1000 + i, T0 + i * 200L, true, true, 60));
        }
        run(1, T0, 60, 11.0);

        assertThat(fixture.flags.readValuesToList()).extracting(SuspiciousPlayer::rule)
                .containsExactlyInAnyOrder(Rule.AIMBOT, Rule.SPEEDHACK);
    }
}
