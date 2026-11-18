package dev.fairplay.telemetry.simulator.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import dev.fairplay.telemetry.common.json.JsonSerdes;
import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.model.ParticipantSummary;
import dev.fairplay.telemetry.common.support.MutableClock;
import dev.fairplay.telemetry.simulator.SimulatorProperties;
import dev.fairplay.telemetry.simulator.TestSimProps;
import dev.fairplay.telemetry.simulator.engine.EventFactory;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import dev.fairplay.telemetry.simulator.population.PopulationFactory;
import org.junit.jupiter.api.Test;

class MatchSummaryBuilderTest {

    private static final long START = 1_763_372_100_000L;

    private final SimulatorProperties props = TestSimProps.defaults();
    private final List<PlayerState> players = new PopulationFactory(props).create(START).subList(0, 10);

    private MatchRoom room(int matchNumber) {
        return new MatchRoom(matchNumber, "r-9f2c41", START, players);
    }

    private ParticipantSummary of(MatchSummary summary, PlayerState p) {
        return summary.participants().stream().filter(s -> s.playerId().equals(p.playerId())).findFirst().orElseThrow();
    }

    @Test
    void placementsAreExactlyOneToN() {
        MatchRoom room = room(1);
        room.recordKill(players.get(0), players.get(1), false);
        room.recordKill(players.get(2), players.get(1), true);
        room.recordKill(players.get(2), players.get(3), true);

        MatchSummary summary = MatchSummaryBuilder.build(room, START + 120_000);

        assertThat(summary.participants()).extracting(ParticipantSummary::placement)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void ranksByKillsThenFewerDeathsThenPlayerId() {
        MatchRoom room = room(1);
        PlayerState topKiller = players.get(0);
        PlayerState sameKillsMoreDeaths = players.get(1);
        PlayerState sameKillsFewerDeaths = players.get(2);
        PlayerState victim = players.get(3);
        room.recordKill(topKiller, victim, false);
        room.recordKill(topKiller, victim, false);
        room.recordKill(sameKillsMoreDeaths, victim, false);
        room.recordKill(sameKillsFewerDeaths, victim, false);
        room.recordKill(victim, sameKillsMoreDeaths, false); // one death for sameKillsMoreDeaths, but a kill for victim

        MatchSummary summary = MatchSummaryBuilder.build(room, START + 120_000);

        assertThat(of(summary, topKiller).placement()).isEqualTo(1);
        // sameKills* both have 1 kill, victim has 1 kill and 4 deaths; fewer deaths ranks higher.
        assertThat(of(summary, sameKillsFewerDeaths).placement()).isLessThan(of(summary, sameKillsMoreDeaths).placement());
        assertThat(of(summary, sameKillsMoreDeaths).placement()).isLessThan(of(summary, victim).placement());
    }

    @Test
    void fullTiesAreBrokenByPlayerIdAscending() {
        MatchSummary summary = MatchSummaryBuilder.build(room(1), START + 120_000);

        List<String> ids = summary.participants().stream().map(ParticipantSummary::playerId).toList();
        assertThat(ids).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void zeroShotsGivesAnAverageReactionOfOne() {
        MatchSummary summary = MatchSummaryBuilder.build(room(1), START + 120_000);

        assertThat(summary.participants()).allMatch(p -> p.avgReactionMs() == 1 && p.shotsFired() == 0);
    }

    @Test
    void averageReactionIsRoundedAndNeverBelowOne() {
        MatchRoom room = room(1);
        room.recordShot(players.get(0), true, 100);
        room.recordShot(players.get(0), false, 101); // mean 100.5 rounds to 101
        room.recordShot(players.get(1), false, 0);   // mean 0 would be 0, but the floor is 1

        MatchSummary summary = MatchSummaryBuilder.build(room, START + 120_000);

        assertThat(of(summary, players.get(0)).avgReactionMs()).isEqualTo(101);
        assertThat(of(summary, players.get(1)).avgReactionMs()).isEqualTo(1);
    }

    @Test
    void aSimulatedMatchSatisfiesEveryArenaInvariant() {
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(START));
        EventFactory events = new EventFactory(props, "r-9f2c41", clock);
        SplittableRandom rng = new SplittableRandom(8);
        MatchRoom room = room(17);

        for (int tick = 0; tick < 20 * 120; tick++) {
            for (PlayerState p : players) {
                events.tick(p, room, rng, e -> { });
            }
            clock.advance(Duration.ofMillis(50));
        }
        MatchSummary summary = MatchSummaryBuilder.build(room, clock.millis());

        assertThat(summary.externalId()).isEqualTo("sim-r-9f2c41-000017");
        assertThat(summary.participants()).hasSize(10);
        assertThat(summary.participants()).extracting(ParticipantSummary::placement)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 10).boxed().toList());
        assertThat(summary.participants()).allSatisfy(p -> {
            assertThat(p.headshots()).isLessThanOrEqualTo(p.kills());
            assertThat(p.shotsHit()).isLessThanOrEqualTo(p.shotsFired());
            assertThat(p.avgReactionMs()).isGreaterThanOrEqualTo(1);
        });
        assertThat(summary.participants().stream().mapToInt(ParticipantSummary::kills).sum())
                .isEqualTo(summary.participants().stream().mapToInt(ParticipantSummary::deaths).sum())
                .isPositive();
        assertThat(summary.participants()).extracting(ParticipantSummary::kills).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void timesAreIso8601AndTheSummarySurvivesJson() {
        MatchSummary summary = MatchSummaryBuilder.build(room(2), START + 120_000);

        assertThat(Instant.parse(summary.startedAt())).isEqualTo(Instant.ofEpochMilli(START));
        assertThat(Instant.parse(summary.endedAt())).isEqualTo(Instant.ofEpochMilli(START + 120_000));
        assertThat(summary.gameMode()).isEqualTo("FFA");
        byte[] json = JsonSerdes.matchSummary().serializer().serialize("t", summary);
        assertThat(JsonSerdes.matchSummary().deserializer().deserialize("t", json)).isEqualTo(summary);
    }

    @Test
    void mapsRotateByMatchNumber() {
        assertThat(room(1).mapName()).isEqualTo("Rust Yard");
        assertThat(room(2).mapName()).isEqualTo("Dockside");
        assertThat(room(6).mapName()).isEqualTo("Harbor");
        assertThat(room(7).mapName()).isEqualTo("Rust Yard");
        assertThat(room(13).mapName()).isEqualTo("Rust Yard");
    }

    @Test
    void roomBasics() {
        MatchRoom room = room(3);

        assertThat(room.externalId()).isEqualTo("sim-r-9f2c41-000003");
        assertThat(room.startedAtMs()).isEqualTo(START);
        assertThat(room.isOver(START + 119_999, 120_000)).isFalse();
        assertThat(room.isOver(START + 120_000, 120_000)).isTrue();
    }

    @Test
    void randomOpponentIsNeverTheAskerAndCoversEveryoneElse() {
        MatchRoom room = room(1);
        SplittableRandom rng = new SplittableRandom(1);
        PlayerState asker = players.get(4);
        List<PlayerState> seen = new ArrayList<>();

        for (int i = 0; i < 2000; i++) {
            PlayerState opponent = room.randomOpponent(asker, rng);
            assertThat(opponent).isNotEqualTo(asker);
            if (!seen.contains(opponent)) {
                seen.add(opponent);
            }
        }

        assertThat(seen).hasSize(9);
    }

    @Test
    void aRoomNeedsAtLeastTwoPlayers() {
        assertThatThrownBy(() -> new MatchRoom(1, "r-x", START, players.subList(0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
