package dev.fairplay.telemetry.common;

/** Topic names are shared with downstream consumers, Arena and the verifier, so renaming one breaks them. */
public final class Topics {

    public static final String MATCH_EVENTS = "match-events";
    public static final String MATCH_EVENTS_DLT = "match-events.DLT";
    public static final String PLAYER_PROFILES = "player-profiles";
    public static final String SUSPICIOUS_PLAYERS = "suspicious-players";
    public static final String INTEGRITY_STATS = "integrity-stats";
    public static final String MATCH_SUMMARIES = "match-summaries";
    public static final String MATCH_SUMMARIES_DLT = "match-summaries.DLT";
    public static final String CHEATER_GROUND_TRUTH = "cheater-ground-truth";
    public static final String SIMULATION_MANIFEST = "simulation-manifest";

    /** Key under which {@code RunInfo} is stored in {@link #SIMULATION_MANIFEST}. */
    public static final String RUN_KEY = "__run__";

    /** Partitions of every topic keyed by player id, so joined streams are co-partitioned. */
    public static final int PLAYER_PARTITIONS = 6;

    private Topics() {
    }
}
