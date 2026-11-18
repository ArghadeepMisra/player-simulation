package dev.fairplay.telemetry.detector.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IntegrityTopologyTest {

    private static final String P = TestEvents.player(1);
    private static final String Q = TestEvents.player(2);
    private static final long T0 = TopologyFixture.START.toEpochMilli();
    private static final Duration EMIT = Duration.ofSeconds(5);

    private final TopologyFixture fixture = new TopologyFixture();

    @AfterEach
    void close() {
        fixture.close();
    }

    private void sendSeqs(String player, long from, long to) {
        for (long seq = from; seq <= to; seq++) {
            fixture.send(TestEvents.shot(player, seq, T0 + seq, true, false, 200));
        }
    }

    /** Emits the stats and returns the latest value per player. */
    private Map<String, IntegrityStats> emit() {
        fixture.advanceTime(EMIT);
        return fixture.integrity.readKeyValuesToMap();
    }

    @Test
    void sequenceOneToOneHundredGivesCountOneHundred() {
        sendSeqs(P, 1, 100);

        IntegrityStats stats = emit().get(P);

        assertThat(stats.count()).isEqualTo(100);
        assertThat(stats.lastSeq()).isEqualTo(100);
        assertThat(stats.duplicates()).isZero();
        assertThat(stats.gaps()).isZero();
        assertThat(stats.playerId()).isEqualTo(P);
    }

    @Test
    void repeatingASequenceNumberCountsOneDuplicateAndDoesNotCountTheEvent() {
        sendSeqs(P, 1, 100);
        fixture.send(TestEvents.shot(P, 50, T0 + 500, true, false, 200));

        IntegrityStats stats = emit().get(P);

        assertThat(stats.duplicates()).isEqualTo(1);
        assertThat(stats.count()).isEqualTo(100);
        assertThat(stats.lastSeq()).isEqualTo(100);
        assertThat(stats.gaps()).isZero();
    }

    @Test
    void repeatingTheLastSequenceNumberIsAlsoADuplicate() {
        sendSeqs(P, 1, 10);
        fixture.send(TestEvents.shot(P, 10, T0 + 99, true, false, 200));

        assertThat(emit().get(P).duplicates()).isEqualTo(1);
    }

    @Test
    void jumpingToSequenceOneHundredFiveCountsFourGaps() {
        sendSeqs(P, 1, 100);
        fixture.send(TestEvents.shot(P, 105, T0 + 105, true, false, 200));

        IntegrityStats stats = emit().get(P);

        assertThat(stats.gaps()).isEqualTo(4);
        assertThat(stats.count()).isEqualTo(101);
        assertThat(stats.lastSeq()).isEqualTo(105);
        assertThat(stats.duplicates()).isZero();
    }

    @Test
    void aFirstEventThatIsNotSequenceOneCountsTheMissingOnesAsGaps() {
        fixture.send(TestEvents.shot(P, 4, T0, true, false, 200));

        IntegrityStats stats = emit().get(P);

        assertThat(stats.gaps()).isEqualTo(3);
        assertThat(stats.count()).isEqualTo(1);
        assertThat(stats.lastSeq()).isEqualTo(4);
    }

    @Test
    void nothingIsEmittedUntilTheIntervalPasses() {
        sendSeqs(P, 1, 10);

        assertThat(fixture.integrity.isEmpty()).isTrue();

        fixture.advanceTime(Duration.ofSeconds(4));
        assertThat(fixture.integrity.isEmpty()).isTrue();

        fixture.advanceTime(Duration.ofSeconds(1));
        assertThat(fixture.integrity.isEmpty()).isFalse();
    }

    @Test
    void everyPlayerIsReEmittedOnEveryIntervalEvenWhenNothingChanged() {
        sendSeqs(P, 1, 5);
        sendSeqs(Q, 1, 7);

        Map<String, IntegrityStats> first = emit();
        Map<String, IntegrityStats> second = emit();

        assertThat(first).containsOnlyKeys(P, Q);
        assertThat(second).containsOnlyKeys(P, Q);
        assertThat(second.get(P).count()).isEqualTo(5);
        assertThat(second.get(Q).count()).isEqualTo(7);
    }

    @Test
    void emittedStatsCarryTheCurrentClockAsUpdatedAt() {
        sendSeqs(P, 1, 3);

        long expected = T0 + EMIT.toMillis();

        assertThat(emit().get(P).updatedAt()).isEqualTo(expected);
    }

    @Test
    void playersAreTrackedIndependently() {
        sendSeqs(P, 1, 20);
        sendSeqs(Q, 1, 3);
        fixture.send(TestEvents.shot(Q, 3, T0 + 1000, true, false, 200));

        Map<String, IntegrityStats> stats = emit();

        assertThat(stats.get(P).duplicates()).isZero();
        assertThat(stats.get(Q).duplicates()).isEqualTo(1);
    }

    @Test
    void positionAndKillEventsCountToo() {
        fixture.send(TestEvents.position(P, 1, T0, 0.1, 0.1));
        fixture.send(TestEvents.shot(P, 2, T0 + 1, true, true, 60));
        fixture.send(TestEvents.kill(P, 3, T0 + 2, Q, true));

        assertThat(emit().get(P).count()).isEqualTo(3);
    }

    @Test
    void badRecordsNeverReachTheTracker() {
        sendSeqs(P, 1, 3);
        fixture.events.pipeInput(P, new byte[] {1, 2, 3}, T0 + 50);

        IntegrityStats stats = emit().get(P);

        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.duplicates()).isZero();
    }

    @Test
    void applyCoversEveryCase() {
        IntegrityStats start = new IntegrityStats(P, 10, 10, 0, 0, 0);

        assertThat(IntegrityProcessor.apply(start, 11)).isEqualTo(new IntegrityStats(P, 11, 11, 0, 0, 0));
        assertThat(IntegrityProcessor.apply(start, 10)).isEqualTo(new IntegrityStats(P, 10, 10, 1, 0, 0));
        assertThat(IntegrityProcessor.apply(start, 3)).isEqualTo(new IntegrityStats(P, 10, 10, 1, 0, 0));
        assertThat(IntegrityProcessor.apply(start, 15)).isEqualTo(new IntegrityStats(P, 11, 15, 0, 4, 0));
    }
}
