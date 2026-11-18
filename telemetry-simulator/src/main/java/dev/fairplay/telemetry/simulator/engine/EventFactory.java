package dev.fairplay.telemetry.simulator.engine;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

import dev.fairplay.telemetry.common.model.EventType;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.simulator.SimulatorProperties;
import dev.fairplay.telemetry.simulator.match.MatchRoom;
import dev.fairplay.telemetry.simulator.population.BehaviorProfile;
import dev.fairplay.telemetry.simulator.population.PlayerState;

/**
 * Produces the events of one tick for one player, in this order: exactly one POSITION, then zero or more SHOTs,
 * each followed by at most one KILL. All events in a tick share {@code ts = clock.millis()}, and each takes the
 * player's next sequence number.
 */
public final class EventFactory {

    private static final double MEASUREMENT_NOISE_SD = 0.15;

    private final SimulatorProperties props;
    private final String runId;
    private final Clock clock;
    private final double meanShotsPerTick;
    private final int dtMs;

    public EventFactory(SimulatorProperties props, String runId, Clock clock) {
        this.props = props;
        this.runId = runId;
        this.clock = clock;
        this.dtMs = props.tickMs();
        this.meanShotsPerTick = props.shotsPerSecond() / props.tickHz();
    }

    /** Plays one tick for {@code player}, recording shots and kills in {@code room} and passing events to {@code sink}. */
    public void tick(PlayerState player, MatchRoom room, RandomGenerator rng, Consumer<MatchEvent> sink) {
        long now = clock.millis();
        BehaviorProfile profile = player.profileAt(now);

        sink.accept(position(player, room, profile.move(), now, rng));

        BehaviorProfile.Aim aim = profile.aim();
        int shots = poisson(meanShotsPerTick, rng);
        for (int i = 0; i < shots; i++) {
            boolean hit = rng.nextDouble() < aim.hitChance();
            boolean headshot = hit && rng.nextDouble() < aim.headshotChance();
            int reactionMs = (int) Math.max(aim.reactionFloor(),
                    Math.round(aim.reactionMean() + aim.reactionSd() * rng.nextGaussian()));
            room.recordShot(player, hit, reactionMs);
            long shotSeq = player.nextSeq();
            sink.accept(new MatchEvent(eventId(player.playerId(), shotSeq), runId, player.playerId(),
                    room.externalId(), shotSeq, EventType.SHOT, now,
                    hit, headshot, reactionMs, null, null, null, null));

            if (hit && rng.nextDouble() < props.killChancePerHit()) {
                PlayerState victim = room.randomOpponent(player, rng);
                room.recordKill(player, victim, headshot);
                long killSeq = player.nextSeq();
                sink.accept(new MatchEvent(eventId(player.playerId(), killSeq), runId, player.playerId(),
                        room.externalId(), killSeq, EventType.KILL, now,
                        null, headshot, null, null, null, null, victim.playerId()));
            }
        }
    }

    private MatchEvent position(PlayerState player, MatchRoom room, BehaviorProfile.Move move, long now,
                                RandomGenerator rng) {
        double dx = 0;
        double dy = 0;
        if (rng.nextDouble() < move.moveChance()) {
            double base = move.speedMin() + rng.nextDouble() * (move.speedMax() - move.speedMin());
            double speed = base * move.speedMultiplier();
            double measured = Math.max(0, speed + MEASUREMENT_NOISE_SD * rng.nextGaussian());
            double angle = rng.nextDouble() * 2 * Math.PI;
            dx = measured * Math.cos(angle) * dtMs / 1000.0;
            dy = measured * Math.sin(angle) * dtMs / 1000.0;
        }
        long seq = player.nextSeq();
        return new MatchEvent(eventId(player.playerId(), seq), runId, player.playerId(),
                room.externalId(), seq, EventType.POSITION, now,
                null, null, null, dx, dy, dtMs, null);
    }

    /** Knuth's algorithm. Fine for the small means used here (0.225). */
    static int poisson(double mean, RandomGenerator rng) {
        double limit = Math.exp(-mean);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= rng.nextDouble();
        } while (p > limit);
        return k - 1;
    }

    /**
     * The event id is a name-based UUID of run, player and seq, not {@code UUID.randomUUID()}, so every
     * value the simulator emits is a function of the seed and the run id.
     */
    private String eventId(String playerId, long seq) {
        return UUID.nameUUIDFromBytes((runId + ":" + playerId + ":" + seq).getBytes(UTF_8)).toString();
    }
}
