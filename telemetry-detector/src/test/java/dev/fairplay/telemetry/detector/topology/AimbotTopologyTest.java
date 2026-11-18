package dev.fairplay.telemetry.detector.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AimbotTopologyTest {

    private static final String P = TestEvents.player(1);
    private static final String Q = TestEvents.player(2);
    private static final long T0 = TopologyFixture.START.toEpochMilli();

    private final TopologyFixture fixture = new TopologyFixture();

    @AfterEach
    void close() {
        fixture.close();
    }

    /**
     * Sends {@code count} shots, one every {@code spacingMs}, all hits, the last {@code headshots} of them headshots.
     * Returns the next free seq.
     */
    private long burst(String player, long firstSeq, long startTs, int count, long spacingMs, int headshots,
                       int reactionMs) {
        for (int i = 0; i < count; i++) {
            boolean headshot = i >= count - headshots;
            fixture.send(TestEvents.shot(player, firstSeq + i, startTs + i * spacingMs, true, headshot, reactionMs));
        }
        return firstSeq + count;
    }

    @Test
    void anObviousAimbotBurstProducesExactlyOneFlagWithCorrectEvidence() {
        // 15 shots over 2.8 s, all hits, 13 headshots (the last 13), 60 ms reaction.
        burst(P, 1, T0, 15, 200, 13, 60);

        List<SuspiciousPlayer> flags = fixture.flags.readValuesToList();

        assertThat(flags).hasSize(1);
        SuspiciousPlayer flag = flags.get(0);
        assertThat(flag.rule()).isEqualTo(Rule.AIMBOT);
        assertThat(flag.playerId()).isEqualTo(P);
        assertThat(flag.detectorVersion()).isEqualTo("1.0.0");
        assertThat(flag.flagId()).isNotBlank();
        assertThat(flag.detectedAt()).isEqualTo(T0);
        // The 12th shot (t = 2.2 s) is the first moment any window reaches 12 hits. The earliest window holding it
        // is [T0 - 2 s, T0 + 3 s), which then holds shots 1 to 12 of which 10 are headshots.
        assertThat(flag.windowStart()).isEqualTo(T0 - 2000);
        assertThat(flag.windowEnd()).isEqualTo(T0 + 3000);
        assertThat(flag.evidence()).isEqualTo(Evidence.aimbot(12, 12, 10, 0.8333, 60.0));
        // 0.5 + 0.25 x (0.8333 - 0.62) / 0.33 + 0.25 x (140 - 60) / 110
        assertThat(flag.score()).isEqualTo(0.843);
        // No profile was published, so the flag carries no username or tier.
        assertThat(flag.username()).isNull();
        assertThat(flag.skillTier()).isNull();
        assertThat(fixture.metrics.aimbotFlags.get()).isEqualTo(1);
    }

    @Test
    void anHonestStreamForAMinuteNeverFlags() {
        // 5 shots a second for 60 s, 35% hit rate, 240 ms reaction.
        for (int i = 0; i < 300; i++) {
            boolean hit = i % 20 < 7;
            fixture.send(TestEvents.shot(P, i + 1, T0 + i * 200L, hit, hit && i % 3 == 0, 240));
        }

        assertThat(fixture.flags.isEmpty()).isTrue();
        assertThat(fixture.metrics.aimbotFlags.get()).isZero();
    }

    @Test
    void aSecondBurstInsideTheCooldownDoesNotFlagAgain() {
        long next = burst(P, 1, T0, 15, 200, 13, 60);
        assertThat(fixture.flags.readValuesToList()).hasSize(1);

        fixture.advanceTime(Duration.ofSeconds(30));
        burst(P, next, T0 + 30_000, 15, 200, 13, 60);

        assertThat(fixture.flags.isEmpty()).isTrue();
    }

    @Test
    void aBurstAfterTheCooldownFlagsAgain() {
        long next = burst(P, 1, T0, 15, 200, 13, 60);
        assertThat(fixture.flags.readValuesToList()).hasSize(1);

        fixture.advanceTime(Duration.ofMinutes(11));
        burst(P, next, T0 + Duration.ofMinutes(11).toMillis(), 15, 200, 13, 60);

        List<SuspiciousPlayer> second = fixture.flags.readValuesToList();
        assertThat(second).hasSize(1);
        assertThat(second.get(0).rule()).isEqualTo(Rule.AIMBOT);
        assertThat(second.get(0).detectedAt()).isEqualTo(T0 + Duration.ofMinutes(11).toMillis());
    }

    @Test
    void theCooldownIsPerPlayer() {
        burst(P, 1, T0, 15, 200, 13, 60);
        burst(Q, 1, T0, 15, 200, 13, 60);

        assertThat(fixture.flags.readValuesToList()).extracting(SuspiciousPlayer::playerId)
                .containsExactlyInAnyOrder(P, Q);
    }

    @Test
    void slowReactionsOrFewHeadshotsOrFewHitsDoNotFlag() {
        burst(P, 1, T0, 15, 200, 13, 141);        // reaction just above the limit
        burst(Q, 1, T0, 15, 200, 0, 60);          // no headshots
        String third = TestEvents.player(3);
        burst(third, 1, T0, 11, 200, 11, 60);     // only 11 hits

        assertThat(fixture.flags.isEmpty()).isTrue();
    }

    @Test
    void shotsSpreadOverMoreThanAWindowDoNotAccumulate() {
        // 15 hits, but one every 500 ms: no 5 s window ever holds 12 of them.
        burst(P, 1, T0, 15, 500, 15, 60);

        assertThat(fixture.flags.isEmpty()).isTrue();
    }

    @Test
    void invalidEventsAreDeadLetteredAndNeverCountedInTheWindows() {
        for (int i = 0; i < 15; i++) {
            // seq 0 is invalid, so the parse step rejects the event and the window path must ignore it too.
            fixture.send(TestEvents.shot(P, 0, T0 + i * 200L, true, true, 60));
        }

        assertThat(fixture.flags.isEmpty()).isTrue();
        assertThat(fixture.deadLetters.readValuesToList()).hasSize(15);
    }

    @Test
    void eventsKeyedByAnotherPlayerAreNotCountedEither() {
        for (int i = 0; i < 15; i++) {
            fixture.events.pipeInput(Q, TestEvents.bytes(TestEvents.shot(P, i + 1, T0 + i * 200L, true, true, 60)),
                    T0 + i * 200L);
        }

        assertThat(fixture.flags.isEmpty()).isTrue();
        assertThat(fixture.deadLetters.readValuesToList()).hasSize(15);
    }

    @Test
    void positionAndKillEventsNeverFeedTheAimbotWindows() {
        for (int i = 0; i < 40; i++) {
            fixture.send(TestEvents.position(P, i + 1, T0 + i * 50L, 0.01, 0.01));
        }
        for (int i = 0; i < 15; i++) {
            fixture.send(TestEvents.kill(P, 100 + i, T0 + i * 200L, Q, true));
        }

        assertThat(fixture.flags.isEmpty()).isTrue();
    }
}
