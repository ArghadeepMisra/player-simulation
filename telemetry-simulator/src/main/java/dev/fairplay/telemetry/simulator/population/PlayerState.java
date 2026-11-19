package dev.fairplay.telemetry.simulator.population;

import java.util.concurrent.atomic.AtomicLong;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.Segment;
import dev.fairplay.telemetry.common.model.SkillTier;

/**
 * One simulated player. Everything is fixed at creation except the per-player sequence counter, which is only
 * advanced by the shard thread that owns the player. {@code cheat} and {@code cheatStartsAt} are null for
 * non-cheaters.
 */
public record PlayerState(
        int index,
        String playerId,
        String username,
        String country,
        Segment segment,
        CheatType cheatType,
        SkillTier skillTier,
        BehaviorProfile base,
        BehaviorProfile cheat,
        Long cheatStartsAt,
        AtomicLong seq) {

    /**
     * The same player under another id, with a fresh sequence counter. Used when Arena assigns the real ids: the
     * simulator makes up ids first (so usernames and behavior stay seeded) and swaps them before anything is sent.
     */
    public PlayerState withPlayerId(String newPlayerId) {
        return new PlayerState(index, newPlayerId, username, country, segment, cheatType, skillTier, base, cheat,
                cheatStartsAt, new AtomicLong());
    }

    /** The profile in force at {@code nowMs}: the cheat profile from {@code cheatStartsAt} on, else the base one. */
    public BehaviorProfile profileAt(long nowMs) {
        return isCheatingAt(nowMs) ? cheat : base;
    }

    public boolean isCheatingAt(long nowMs) {
        return cheatStartsAt != null && nowMs >= cheatStartsAt;
    }

    /** Next per-player sequence number: 1, 2, 3 and so on, with no gaps. */
    public long nextSeq() {
        return seq.incrementAndGet();
    }

    /** The highest sequence number handed out so far, 0 when none. */
    public long lastSeq() {
        return seq.get();
    }
}
