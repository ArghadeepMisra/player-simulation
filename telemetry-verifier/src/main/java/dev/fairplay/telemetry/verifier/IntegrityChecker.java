package dev.fairplay.telemetry.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.RunInfo;

/**
 * Proves that every acknowledged event was counted exactly once. Pure comparison: no Kafka, no clock.
 *
 * <p>A player passes when stats exist, the simulator reported no failed sends, the detector saw no duplicates and no
 * gaps, and both {@code lastSeq} and {@code count} equal the manifest's {@code finalSeq}.
 */
public final class IntegrityChecker {

    static final int MAX_LISTED_FAILURES = 20;

    private IntegrityChecker() {
    }

    public static IntegrityReport check(RunInfo run, Collection<ManifestEntry> manifest,
                                        Map<String, IntegrityStats> stats) {
        int passed = 0;
        int withoutStats = 0;
        long expected = 0;
        long counted = 0;
        long duplicates = 0;
        long gaps = 0;
        List<IntegrityReport.Failure> failures = new ArrayList<>();

        List<ManifestEntry> ordered = new ArrayList<>(manifest);
        ordered.sort(Comparator.comparing(ManifestEntry::playerId));
        for (ManifestEntry m : ordered) {
            IntegrityStats s = stats.get(m.playerId());
            expected += m.finalSeq();
            List<String> reasons = new ArrayList<>();
            if (s == null) {
                withoutStats++;
                reasons.add("no integrity stats");
            } else {
                counted += s.count();
                duplicates += s.duplicates();
                gaps += s.gaps();
                if (s.duplicates() != 0) {
                    reasons.add("duplicates=" + s.duplicates());
                }
                if (s.gaps() != 0) {
                    reasons.add("gaps=" + s.gaps());
                }
                if (s.lastSeq() != m.finalSeq()) {
                    reasons.add("lastSeq=" + s.lastSeq() + " expected " + m.finalSeq());
                }
                if (s.count() != m.finalSeq()) {
                    reasons.add("count=" + s.count() + " expected " + m.finalSeq());
                }
            }
            if (m.failed() != 0) {
                reasons.add("simulator reported failed sends=" + m.failed());
            }
            if (reasons.isEmpty()) {
                passed++;
            } else if (failures.size() < MAX_LISTED_FAILURES) {
                failures.add(new IntegrityReport.Failure(m, s, List.copyOf(reasons)));
            }
        }
        return new IntegrityReport(run.runId(), run.startedAt(), run.endedAt(), ordered.size(), passed, expected,
                counted, duplicates, gaps, withoutStats, failures);
    }
}
