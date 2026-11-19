package dev.fairplay.telemetry.simulator.engine;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.simulator.SimulatorProperties;
import dev.fairplay.telemetry.simulator.match.MatchRoom;
import dev.fairplay.telemetry.simulator.match.MatchSummaryBuilder;
import dev.fairplay.telemetry.simulator.output.EventPublisher;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One worker thread's share of the simulation: it owns a fixed set of rooms and only their players, with its own
 * {@code SplittableRandom(seed + shardIndex)} and its own {@link MalformedInjector}, so no state is shared between
 * threads except the global match counter.
 *
 * <p>DECISION: every room of a shard starts and ends together. When the first room is over, the whole shard's
 * matches finish, their summaries are published, and the shard's players are reshuffled into new rooms. The spec
 * says a worker "reshuffles its own players into new rooms" and does not say how to do that while rooms end at
 * different times; ending them together keeps the reshuffle trivial.
 */
public final class ShardWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ShardWorker.class);

    private final int shardIndex;
    private final SimulatorProperties props;
    private final String runId;
    private final Clock clock;
    private final AtomicInteger matchCounter;
    private final EventPublisher publisher;
    private final EventFactory eventFactory;
    private final SplittableRandom rng;
    private final MalformedInjector injector;
    private final Consumer<MatchEvent> sink;
    private final long matchDurationMs;
    private final List<PlayerState> players;
    private List<MatchRoom> rooms;

    public ShardWorker(int shardIndex, List<PlayerState> players, SimulatorProperties props, String runId,
                       Clock clock, AtomicInteger matchCounter, EventPublisher publisher) {
        this.shardIndex = shardIndex;
        this.players = new ArrayList<>(players);
        this.props = props;
        this.runId = runId;
        this.clock = clock;
        this.matchCounter = matchCounter;
        this.publisher = publisher;
        this.eventFactory = new EventFactory(props, runId, clock);
        this.rng = new SplittableRandom(props.seed() + shardIndex);
        this.injector = new MalformedInjector(props.malformedRatio());
        this.matchDurationMs = props.matchDuration().toMillis();
        // After each real event, with probability malformed-ratio, also send one bad record for the same player.
        this.sink = event -> {
            publisher.publish(event);
            injector.maybeNext(rng).ifPresent(bytes -> publisher.publishMalformed(event.playerId(), bytes));
        };
        this.rooms = newRooms(clock.millis());
    }

    /**
     * Splits the whole population into rooms of {@code match-size} in index order and deals room {@code r} to
     * shard {@code r % shards}, so worker {@code k} owns rooms k, k+shards, k+2*shards and so on.
     */
    public static List<List<PlayerState>> partition(List<PlayerState> population, SimulatorProperties props) {
        List<List<PlayerState>> shards = new ArrayList<>();
        for (int k = 0; k < props.shardThreads(); k++) {
            shards.add(new ArrayList<>());
        }
        int rooms = population.size() / props.matchSize();
        for (int r = 0; r < rooms; r++) {
            shards.get(r % props.shardThreads())
                    .addAll(population.subList(r * props.matchSize(), (r + 1) * props.matchSize()));
        }
        return shards;
    }

    /**
     * What the scheduler calls. An exception thrown from a task given to {@code scheduleAtFixedRate} silently
     * cancels every later run, which would freeze this shard's players without any visible error, so each tick is
     * guarded and the next one still happens.
     */
    @Override
    public void run() {
        try {
            tick();
        } catch (Throwable t) {
            log.error("Shard {} tick failed", shardIndex, t);
        }
    }

    /** One tick for every player in the shard. Public so tests can drive it with a mutable clock. */
    public void tick() {
        long now = clock.millis();
        if (rooms.get(0).isOver(now, matchDurationMs)) {
            endRound(now);
        }
        for (MatchRoom room : rooms) {
            for (PlayerState player : room.members()) {
                eventFactory.tick(player, room, rng, sink);
            }
        }
    }

    public List<MatchRoom> rooms() {
        return rooms;
    }

    private void endRound(long now) {
        for (MatchRoom room : rooms) {
            publisher.publishSummary(MatchSummaryBuilder.build(room, now));
        }
        rooms = newRooms(now);
    }

    private List<MatchRoom> newRooms(long startedAtMs) {
        List<PlayerState> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled, rng);
        List<MatchRoom> next = new ArrayList<>(shuffled.size() / props.matchSize());
        for (int i = 0; i + props.matchSize() <= shuffled.size(); i += props.matchSize()) {
            next.add(new MatchRoom(matchCounter.incrementAndGet(), runId, startedAtMs,
                    shuffled.subList(i, i + props.matchSize())));
        }
        return next;
    }
}
