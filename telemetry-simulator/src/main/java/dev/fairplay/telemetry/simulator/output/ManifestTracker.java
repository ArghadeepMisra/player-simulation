package dev.fairplay.telemetry.simulator.output;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import org.springframework.stereotype.Component;

/**
 * Counts, per player, how many events the broker acknowledged and how many sends failed. At the end of a run
 * this becomes one {@link ManifestEntry} per player, which the verifier compares with the detector's
 * {@code integrity-stats}: that comparison is the exactly-once proof.
 *
 * <p>Producer callbacks run on the Kafka I/O thread while shard threads keep sending, so every counter is a
 * {@link LongAdder} or an {@link AtomicLong}.
 */
@Component
public class ManifestTracker {

    private static final class PlayerCounters {
        private final LongAdder acked = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final AtomicLong highestAckedSeq = new AtomicLong();
    }

    private final Map<String, PlayerCounters> counters = new ConcurrentHashMap<>();

    public void acked(String playerId, long seq) {
        PlayerCounters c = counters.computeIfAbsent(playerId, id -> new PlayerCounters());
        c.acked.increment();
        c.highestAckedSeq.accumulateAndGet(seq, Math::max);
    }

    public void failed(String playerId) {
        counters.computeIfAbsent(playerId, id -> new PlayerCounters()).failed.increment();
    }

    public long ackedOf(String playerId) {
        PlayerCounters c = counters.get(playerId);
        return c == null ? 0 : c.acked.sum();
    }

    public long failedOf(String playerId) {
        PlayerCounters c = counters.get(playerId);
        return c == null ? 0 : c.failed.sum();
    }

    /** The highest sequence number the broker acknowledged for the player, 0 when none. */
    public long highestAckedSeq(String playerId) {
        PlayerCounters c = counters.get(playerId);
        return c == null ? 0 : c.highestAckedSeq.get();
    }

    public long totalAcked() {
        return counters.values().stream().mapToLong(c -> c.acked.sum()).sum();
    }

    public long totalFailed() {
        return counters.values().stream().mapToLong(c -> c.failed.sum()).sum();
    }

    /**
     * One entry per player. DECISION: {@code finalSeq} is the last sequence number the simulator generated
     * ({@link PlayerState#lastSeq()}), not the highest acknowledged one, so a failed send shows up as
     * {@code finalSeq > acked} instead of hiding itself.
     */
    public List<ManifestEntry> entries(String runId, List<PlayerState> players) {
        List<ManifestEntry> entries = new ArrayList<>(players.size());
        for (PlayerState player : players) {
            entries.add(new ManifestEntry(runId, player.playerId(), player.lastSeq(),
                    ackedOf(player.playerId()), failedOf(player.playerId())));
        }
        return entries;
    }
}
