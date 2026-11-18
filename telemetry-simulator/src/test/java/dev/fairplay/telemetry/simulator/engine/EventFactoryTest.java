package dev.fairplay.telemetry.simulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.EventType;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.support.MutableClock;
import dev.fairplay.telemetry.simulator.SimulatorProperties;
import dev.fairplay.telemetry.simulator.TestSimProps;
import dev.fairplay.telemetry.simulator.match.MatchRoom;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import dev.fairplay.telemetry.simulator.population.PopulationFactory;
import org.junit.jupiter.api.Test;

class EventFactoryTest {

    private static final String RUN_ID = "r-test01";
    private static final long RUN_START = 1_763_372_100_000L;

    private final SimulatorProperties props = TestSimProps.defaults();
    private final List<PlayerState> population = new PopulationFactory(props).create(RUN_START);
    private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(RUN_START));
    private final EventFactory factory = new EventFactory(props, RUN_ID, clock);
    private final SplittableRandom rng = new SplittableRandom(5);

    private PlayerState first(CheatType type) {
        return population.stream().filter(p -> p.cheatType() == type).findFirst().orElseThrow();
    }

    /** A room holding {@code player} and nine others. */
    private MatchRoom roomWith(PlayerState player) {
        List<PlayerState> members = new ArrayList<>();
        members.add(player);
        population.stream().filter(p -> p != player).limit(9).forEach(members::add);
        return new MatchRoom(1, RUN_ID, clock.millis(), members);
    }

    private List<List<MatchEvent>> play(PlayerState player, MatchRoom room, int ticks) {
        List<List<MatchEvent>> perTick = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            List<MatchEvent> events = new ArrayList<>();
            factory.tick(player, room, rng, events::add);
            perTick.add(events);
            clock.advance(Duration.ofMillis(props.tickMs()));
        }
        return perTick;
    }

    private static double speed(MatchEvent position) {
        return Math.hypot(position.dx(), position.dy()) / (position.dtMs() / 1000.0);
    }

    @Test
    void everyEventIsValidAndSequenceNumbersGrowByExactlyOne() {
        PlayerState player = population.get(0);
        List<MatchEvent> events = play(player, roomWith(player), 3000).stream().flatMap(List::stream).toList();

        assertThat(events).allMatch(MatchEvent::isValid);
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).seq()).isEqualTo(i + 1);
        }
        assertThat(player.lastSeq()).isEqualTo(events.size());
        assertThat(events).extracting(MatchEvent::playerId).containsOnly(player.playerId());
        assertThat(events).extracting(MatchEvent::runId).containsOnly(RUN_ID);
        assertThat(events).extracting(MatchEvent::eventId).doesNotHaveDuplicates();
    }

    @Test
    void eachTickStartsWithExactlyOnePositionAndSharesOneTimestamp() {
        PlayerState player = population.get(1);

        for (List<MatchEvent> tick : play(player, roomWith(player), 2000)) {
            assertThat(tick.get(0).type()).isEqualTo(EventType.POSITION);
            assertThat(tick.stream().filter(e -> e.type() == EventType.POSITION)).hasSize(1);
            assertThat(tick).extracting(MatchEvent::ts).containsOnly(tick.get(0).ts());
        }
    }

    @Test
    void positionsAlwaysCarryDtMsOf50() {
        PlayerState player = population.get(2);

        List<MatchEvent> positions = play(player, roomWith(player), 500).stream().flatMap(List::stream)
                .filter(e -> e.type() == EventType.POSITION).toList();

        assertThat(positions).hasSize(500).allMatch(e -> e.dtMs() == 50);
    }

    @Test
    void headshotIsNeverTrueWithoutAHitAndKillsCopyTheShotsHeadshot() {
        PlayerState player = first(CheatType.AIMBOT);
        clock.set(Instant.ofEpochMilli(player.cheatStartsAt() + 1));
        List<MatchEvent> events = play(player, roomWith(player), 5000).stream().flatMap(List::stream).toList();

        assertThat(events).filteredOn(e -> e.type() == EventType.SHOT)
                .allSatisfy(e -> assertThat(!e.headshot() || e.hit()).isTrue());
        int kills = 0;
        for (int i = 0; i < events.size(); i++) {
            MatchEvent e = events.get(i);
            if (e.type() == EventType.KILL) {
                kills++;
                MatchEvent shot = events.get(i - 1);
                assertThat(shot.type()).isEqualTo(EventType.SHOT);
                assertThat(shot.hit()).isTrue();
                assertThat(e.headshot()).isEqualTo(shot.headshot());
                assertThat(e.victimId()).isNotEqualTo(player.playerId());
            }
        }
        assertThat(kills).isPositive();
    }

    @Test
    void shotRateMatchesShotsPerSecond() {
        PlayerState player = population.get(3);
        int ticks = 20 * 600; // 10 minutes

        long shots = play(player, roomWith(player), ticks).stream().flatMap(List::stream)
                .filter(e -> e.type() == EventType.SHOT).count();

        assertThat(shots / 600.0).isBetween(4.2, 4.8);
    }

    @Test
    void aimbotProfileSwitchesAtCheatStart() {
        PlayerState player = first(CheatType.AIMBOT);
        MatchRoom room = roomWith(player);
        long start = player.cheatStartsAt();

        clock.set(Instant.ofEpochMilli(start - 300_000));
        List<MatchEvent> before = play(player, room, 6000).stream().flatMap(List::stream)
                .filter(e -> e.type() == EventType.SHOT).toList();
        assertThat(clock.millis()).isLessThanOrEqualTo(start);

        clock.set(Instant.ofEpochMilli(start));
        List<MatchEvent> after = play(player, room, 6000).stream().flatMap(List::stream)
                .filter(e -> e.type() == EventType.SHOT).toList();

        assertThat(hitRate(before)).isLessThan(0.50);
        assertThat(hitRate(after)).isGreaterThan(0.55);
        assertThat(avgReaction(before)).isGreaterThan(150);
        assertThat(avgReaction(after)).isLessThan(100);
    }

    @Test
    void speedhackMultiplierAppliesOnlyAfterTheCheatStarts() {
        PlayerState player = first(CheatType.SPEEDHACK);
        MatchRoom room = roomWith(player);
        long start = player.cheatStartsAt();

        clock.set(Instant.ofEpochMilli(start - 300_000));
        double before = movingSpeedMean(play(player, room, 6000));
        clock.set(Instant.ofEpochMilli(start));
        double after = movingSpeedMean(play(player, room, 6000));

        assertThat(before).isBetween(3.5, 5.5);   // honest: U(2, 7)
        assertThat(after).isGreaterThan(8.5);     // U(5, 7) x U(1.5, 2.0)
    }

    @Test
    void honestPlayersNeverExceedTheSprintSpeedByMuch() {
        PlayerState player = population.stream().filter(p -> p.cheatType() == CheatType.NONE).findFirst().orElseThrow();

        double max = play(player, roomWith(player), 6000).stream().flatMap(List::stream)
                .filter(e -> e.type() == EventType.POSITION).mapToDouble(EventFactoryTest::speed).max().orElseThrow();

        assertThat(max).isLessThan(7.5);
    }

    @Test
    void idleTicksHaveZeroMovement() {
        PlayerState player = population.get(4);

        List<MatchEvent> positions = play(player, roomWith(player), 2000).stream().flatMap(List::stream)
                .filter(e -> e.type() == EventType.POSITION).toList();

        assertThat(positions).anyMatch(e -> e.dx() == 0 && e.dy() == 0);
        assertThat(positions).anyMatch(e -> e.dx() != 0 || e.dy() != 0);
    }

    @Test
    void roomTotalsFollowTheEvents() {
        PlayerState player = population.get(5);
        MatchRoom room = roomWith(player);

        List<MatchEvent> events = play(player, room, 4000).stream().flatMap(List::stream).toList();

        MatchRoom.PlayerTotals totals = room.totalsOf(player.playerId());
        List<MatchEvent> shots = events.stream().filter(e -> e.type() == EventType.SHOT).toList();
        assertThat(totals.shotsFired()).isEqualTo(shots.size());
        assertThat(totals.shotsHit()).isEqualTo((int) shots.stream().filter(MatchEvent::hit).count());
        assertThat(totals.sumReactionMs()).isEqualTo(shots.stream().mapToLong(MatchEvent::reactionMs).sum());
        assertThat(totals.kills()).isEqualTo((int) events.stream().filter(e -> e.type() == EventType.KILL).count());
        assertThat(room.members().stream().mapToInt(m -> room.totalsOf(m.playerId()).deaths()).sum())
                .isEqualTo(totals.kills());
    }

    @Test
    void sameSeedAndClockReproduceTheSameEvents() {
        PlayerState a = new PopulationFactory(props).create(RUN_START).get(6);
        PlayerState b = new PopulationFactory(props).create(RUN_START).get(6);
        List<MatchEvent> first = new ArrayList<>();
        List<MatchEvent> second = new ArrayList<>();
        SplittableRandom r1 = new SplittableRandom(11);
        SplittableRandom r2 = new SplittableRandom(11);
        MatchRoom roomA = new MatchRoom(1, RUN_ID, RUN_START, List.of(a, population.get(7)));
        MatchRoom roomB = new MatchRoom(1, RUN_ID, RUN_START, List.of(b, population.get(7)));

        for (int i = 0; i < 500; i++) {
            factory.tick(a, roomA, r1, first::add);
            factory.tick(b, roomB, r2, second::add);
        }

        assertThat(first).isEqualTo(second);
    }

    @Test
    void poissonMeanMatchesItsParameter() {
        SplittableRandom r = new SplittableRandom(3);
        double mean = java.util.stream.IntStream.range(0, 200_000)
                .map(i -> EventFactory.poisson(0.225, r)).average().orElseThrow();

        assertThat(mean).isBetween(0.215, 0.235);
    }

    private static double hitRate(List<MatchEvent> shots) {
        return shots.stream().filter(MatchEvent::hit).count() / (double) shots.size();
    }

    private static double avgReaction(List<MatchEvent> shots) {
        return shots.stream().mapToInt(MatchEvent::reactionMs).average().orElseThrow();
    }

    private static double movingSpeedMean(List<List<MatchEvent>> ticks) {
        return ticks.stream().flatMap(List::stream).filter(e -> e.type() == EventType.POSITION)
                .mapToDouble(EventFactoryTest::speed).filter(s -> s > 0).average().orElseThrow();
    }
}
