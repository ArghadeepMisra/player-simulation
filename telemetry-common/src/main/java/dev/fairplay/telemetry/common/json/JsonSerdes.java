package dev.fairplay.telemetry.common.json;

import dev.fairplay.telemetry.common.model.DeadLetter;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.RunInfo;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;

/** Factories for the serdes of the shared contract types. */
public final class JsonSerdes {

    private JsonSerdes() {
    }

    /**
     * Detector-only types (AimStats, SpeedStats, FlagCandidate) live in the detector module, which
     * cannot be referenced from here, so the detector builds its serdes with this generic factory.
     */
    public static <T> JsonSerde<T> of(Class<T> type) {
        return new JsonSerde<>(type);
    }

    public static JsonSerde<MatchEvent> matchEvent() {
        return of(MatchEvent.class);
    }

    public static JsonSerde<PlayerProfile> profile() {
        return of(PlayerProfile.class);
    }

    public static JsonSerde<SuspiciousPlayer> suspicious() {
        return of(SuspiciousPlayer.class);
    }

    public static JsonSerde<IntegrityStats> integrity() {
        return of(IntegrityStats.class);
    }

    public static JsonSerde<DeadLetter> deadLetter() {
        return of(DeadLetter.class);
    }

    public static JsonSerde<GroundTruth> groundTruth() {
        return of(GroundTruth.class);
    }

    public static JsonSerde<ManifestEntry> manifestEntry() {
        return of(ManifestEntry.class);
    }

    public static JsonSerde<RunInfo> runInfo() {
        return of(RunInfo.class);
    }

    public static JsonSerde<MatchSummary> matchSummary() {
        return of(MatchSummary.class);
    }
}
