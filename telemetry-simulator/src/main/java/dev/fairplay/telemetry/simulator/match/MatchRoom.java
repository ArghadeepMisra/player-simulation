package dev.fairplay.telemetry.simulator.match;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import dev.fairplay.telemetry.simulator.population.PlayerState;

/**
 * One match of {@code match-size} players. Only the shard thread that owns the room touches it, so nothing here is
 * synchronized. Keeps the per-player totals the match summary is built from.
 */
public final class MatchRoom {

    /** The six maps rotate by match number. */
    static final List<String> MAPS = List.of("Rust Yard", "Dockside", "Old Town", "Skyline", "Quarry", "Harbor");

    /**
     * Running totals of one player in one match.
     * A small mutable class, not a record. It is a thread-confined accumulator updated thousands of
     * times per second, not an event, DTO or stored state value.
     */
    public static final class PlayerTotals {
        int kills;
        int deaths;
        int headshotKills;
        int shotsFired;
        int shotsHit;
        long sumReactionMs;

        public int kills() {
            return kills;
        }

        public int deaths() {
            return deaths;
        }

        public int headshotKills() {
            return headshotKills;
        }

        public int shotsFired() {
            return shotsFired;
        }

        public int shotsHit() {
            return shotsHit;
        }

        public long sumReactionMs() {
            return sumReactionMs;
        }
    }

    private final int matchNumber;
    private final String externalId;
    private final long startedAtMs;
    private final List<PlayerState> members;
    private final Map<String, PlayerTotals> totals = new LinkedHashMap<>();

    public MatchRoom(int matchNumber, String runId, long startedAtMs, List<PlayerState> members) {
        if (members.size() < 2) {
            throw new IllegalArgumentException("a match needs at least two players");
        }
        this.matchNumber = matchNumber;
        this.externalId = "sim-%s-%06d".formatted(runId, matchNumber);
        this.startedAtMs = startedAtMs;
        this.members = List.copyOf(members);
        for (PlayerState member : this.members) {
            totals.put(member.playerId(), new PlayerTotals());
        }
    }

    public String externalId() {
        return externalId;
    }

    public String mapName() {
        return MAPS.get((matchNumber - 1) % MAPS.size());
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    public List<PlayerState> members() {
        return members;
    }

    public PlayerTotals totalsOf(String playerId) {
        return totals.get(playerId);
    }

    public boolean isOver(long nowMs, long matchDurationMs) {
        return nowMs >= startedAtMs + matchDurationMs;
    }

    public void recordShot(PlayerState shooter, boolean hit, int reactionMs) {
        PlayerTotals t = totals.get(shooter.playerId());
        t.shotsFired++;
        t.sumReactionMs += reactionMs;
        if (hit) {
            t.shotsHit++;
        }
    }

    public void recordKill(PlayerState killer, PlayerState victim, boolean headshot) {
        PlayerTotals k = totals.get(killer.playerId());
        k.kills++;
        if (headshot) {
            k.headshotKills++;
        }
        totals.get(victim.playerId()).deaths++;
    }

    /** A uniformly random room member other than {@code player}. */
    public PlayerState randomOpponent(PlayerState player, RandomGenerator rng) {
        int own = members.indexOf(player);
        int pick = rng.nextInt(members.size() - 1);
        return members.get(pick >= own ? pick + 1 : pick);
    }
}
