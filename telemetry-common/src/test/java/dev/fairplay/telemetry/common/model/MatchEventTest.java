package dev.fairplay.telemetry.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fairplay.telemetry.common.support.TestEvents;
import org.junit.jupiter.api.Test;

class MatchEventTest {

    private static final String P = TestEvents.player(1);
    private static final String V = TestEvents.player(2);

    @Test
    void wellFormedEventsOfEveryTypeAreValid() {
        assertThat(TestEvents.shot(P, 1, 1000, true, true, 60).isValid()).isTrue();
        assertThat(TestEvents.shot(P, 1, 1000, false, false, 60).isValid()).isTrue();
        assertThat(TestEvents.position(P, 1, 1000, 0, 0).isValid()).isTrue();
        assertThat(TestEvents.kill(P, 1, 1000, V, false).isValid()).isTrue();
    }

    @Test
    void shotNeedsHitHeadshotAndReaction() {
        MatchEvent ok = TestEvents.shot(P, 1, 1000, true, false, 60);

        assertThat(withShot(ok, null, false, 60).isValid()).isFalse();
        assertThat(withShot(ok, true, null, 60).isValid()).isFalse();
        assertThat(withShot(ok, true, false, null).isValid()).isFalse();
    }

    @Test
    void positionNeedsDxDyAndPositiveDt() {
        MatchEvent ok = TestEvents.position(P, 1, 1000, 0.1, 0.2);

        assertThat(withPosition(ok, null, 0.2, 50).isValid()).isFalse();
        assertThat(withPosition(ok, 0.1, null, 50).isValid()).isFalse();
        assertThat(withPosition(ok, 0.1, 0.2, null).isValid()).isFalse();
        assertThat(withPosition(ok, 0.1, 0.2, 0).isValid()).isFalse();
    }

    @Test
    void killNeedsVictimAndHeadshot() {
        MatchEvent ok = TestEvents.kill(P, 1, 1000, V, true);

        assertThat(new MatchEvent(ok.eventId(), ok.runId(), P, ok.matchId(), 1, EventType.KILL, 1000,
                null, true, null, null, null, null, null).isValid()).isFalse();
        assertThat(new MatchEvent(ok.eventId(), ok.runId(), P, ok.matchId(), 1, EventType.KILL, 1000,
                null, true, null, null, null, null, " ").isValid()).isFalse();
        assertThat(new MatchEvent(ok.eventId(), ok.runId(), P, ok.matchId(), 1, EventType.KILL, 1000,
                null, null, null, null, null, null, V).isValid()).isFalse();
    }

    @Test
    void seqBelowOneAndNonPositiveTimestampAreInvalid() {
        assertThat(TestEvents.shot(P, 0, 1000, true, false, 60).isValid()).isFalse();
        assertThat(TestEvents.shot(P, -3, 1000, true, false, 60).isValid()).isFalse();
        assertThat(TestEvents.shot(P, 1, 0, true, false, 60).isValid()).isFalse();
        assertThat(TestEvents.shot(P, 1, -5, true, false, 60).isValid()).isFalse();
    }

    @Test
    void playerIdMustBeAUuid() {
        assertThat(TestEvents.shot("not-a-uuid", 1, 1000, true, false, 60).isValid()).isFalse();
        assertThat(TestEvents.shot("1-1-1-1-1", 1, 1000, true, false, 60).isValid()).isFalse();
        assertThat(TestEvents.shot(null, 1, 1000, true, false, 60).isValid()).isFalse();
    }

    @Test
    void missingTypeAndMissingIdsAreInvalid() {
        MatchEvent ok = TestEvents.shot(P, 1, 1000, true, false, 60);

        assertThat(new MatchEvent(ok.eventId(), ok.runId(), P, ok.matchId(), 1, null, 1000,
                true, false, 60, null, null, null, null).isValid()).isFalse();
        assertThat(new MatchEvent(null, ok.runId(), P, ok.matchId(), 1, EventType.SHOT, 1000,
                true, false, 60, null, null, null, null).isValid()).isFalse();
        assertThat(new MatchEvent(ok.eventId(), "", P, ok.matchId(), 1, EventType.SHOT, 1000,
                true, false, 60, null, null, null, null).isValid()).isFalse();
        assertThat(new MatchEvent(ok.eventId(), ok.runId(), P, null, 1, EventType.SHOT, 1000,
                true, false, 60, null, null, null, null).isValid()).isFalse();
    }

    private static MatchEvent withShot(MatchEvent e, Boolean hit, Boolean headshot, Integer reaction) {
        return new MatchEvent(e.eventId(), e.runId(), e.playerId(), e.matchId(), e.seq(), e.type(), e.ts(),
                hit, headshot, reaction, null, null, null, null);
    }

    private static MatchEvent withPosition(MatchEvent e, Double dx, Double dy, Integer dtMs) {
        return new MatchEvent(e.eventId(), e.runId(), e.playerId(), e.matchId(), e.seq(), e.type(), e.ts(),
                null, null, null, dx, dy, dtMs, null);
    }
}
