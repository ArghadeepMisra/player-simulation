package dev.fairplay.telemetry.verifier;

import java.util.List;

import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.ManifestEntry;

/** The outcome of comparing the simulator's manifest with the detector's integrity stats. */
public record IntegrityReport(
        String runId,
        long startedAt,
        long endedAt,
        int playersChecked,
        int playersPassed,
        long eventsExpected,
        long eventsCounted,
        long duplicates,
        long gaps,
        int playersWithoutStats,
        /** At most {@link IntegrityChecker#MAX_LISTED_FAILURES}; {@code playersChecked - playersPassed} in total. */
        List<Failure> failures) {

    public boolean passed() {
        return playersChecked > 0 && playersPassed == playersChecked;
    }

    /** One failing player with both records side by side. {@code stats} is null when none was found. */
    public record Failure(ManifestEntry manifest, IntegrityStats stats, List<String> reasons) {
    }
}
