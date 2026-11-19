package dev.fairplay.telemetry.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.RunInfo;
import org.junit.jupiter.api.Test;

class IntegrityCheckerTest {

    private static final RunInfo RUN = new RunInfo("r-1", 1000, 2000, 2, 0, 0, 0);

    private static ManifestEntry entry(String id, long finalSeq, long failed) {
        return new ManifestEntry("r-1", id, finalSeq, finalSeq - failed, failed);
    }

    private static IntegrityStats stats(String id, long count, long lastSeq, long duplicates, long gaps) {
        return new IntegrityStats(id, count, lastSeq, duplicates, gaps, 5000);
    }

    private static IntegrityReport.Failure onlyFailure(IntegrityReport report) {
        assertThat(report.failures()).hasSize(1);
        return report.failures().get(0);
    }

    @Test
    void everyPlayerPassesWhenTheCountsMatchExactly() {
        IntegrityReport report = IntegrityChecker.check(RUN,
                List.of(entry("a", 100, 0), entry("b", 50, 0)),
                Map.of("a", stats("a", 100, 100, 0, 0), "b", stats("b", 50, 50, 0, 0)));

        assertThat(report.passed()).isTrue();
        assertThat(report.playersChecked()).isEqualTo(2);
        assertThat(report.playersPassed()).isEqualTo(2);
        assertThat(report.eventsExpected()).isEqualTo(150);
        assertThat(report.eventsCounted()).isEqualTo(150);
        assertThat(report.duplicates()).isZero();
        assertThat(report.gaps()).isZero();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void duplicatesFailAPlayer() {
        IntegrityReport report = IntegrityChecker.check(RUN, List.of(entry("a", 100, 0)),
                Map.of("a", stats("a", 100, 100, 3, 0)));

        assertThat(report.passed()).isFalse();
        assertThat(report.duplicates()).isEqualTo(3);
        assertThat(onlyFailure(report).reasons()).containsExactly("duplicates=3");
    }

    @Test
    void gapsFailAPlayer() {
        IntegrityReport report = IntegrityChecker.check(RUN, List.of(entry("a", 100, 0)),
                Map.of("a", stats("a", 100, 100, 0, 4)));

        assertThat(report.gaps()).isEqualTo(4);
        assertThat(onlyFailure(report).reasons()).containsExactly("gaps=4");
    }

    @Test
    void aWrongLastSeqFailsAPlayer() {
        IntegrityReport report = IntegrityChecker.check(RUN, List.of(entry("a", 100, 0)),
                Map.of("a", stats("a", 100, 99, 0, 0)));

        assertThat(onlyFailure(report).reasons()).containsExactly("lastSeq=99 expected 100");
    }

    @Test
    void aWrongCountFailsAPlayer() {
        IntegrityReport report = IntegrityChecker.check(RUN, List.of(entry("a", 100, 0)),
                Map.of("a", stats("a", 98, 100, 0, 0)));

        assertThat(report.eventsCounted()).isEqualTo(98);
        assertThat(onlyFailure(report).reasons()).containsExactly("count=98 expected 100");
    }

    @Test
    void failedSendsInTheManifestFailAPlayerEvenWhenTheStatsLookRight() {
        IntegrityReport report = IntegrityChecker.check(RUN, List.of(entry("a", 100, 2)),
                Map.of("a", stats("a", 100, 100, 0, 0)));

        assertThat(onlyFailure(report).reasons()).containsExactly("simulator reported failed sends=2");
    }

    @Test
    void aPlayerWithoutStatsFailsAndIsCounted() {
        IntegrityReport report = IntegrityChecker.check(RUN, List.of(entry("a", 100, 0)), Map.of());

        assertThat(report.playersWithoutStats()).isEqualTo(1);
        IntegrityReport.Failure failure = onlyFailure(report);
        assertThat(failure.stats()).isNull();
        assertThat(failure.reasons()).containsExactly("no integrity stats");
    }

    @Test
    void severalReasonsAreAllListed() {
        IntegrityReport report = IntegrityChecker.check(RUN, List.of(entry("a", 100, 0)),
                Map.of("a", stats("a", 97, 97, 2, 5)));

        assertThat(onlyFailure(report).reasons()).containsExactly(
                "duplicates=2", "gaps=5", "lastSeq=97 expected 100", "count=97 expected 100");
    }

    @Test
    void listsAtMostTwentyFailuresButCountsThemAll() {
        List<ManifestEntry> manifest = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            manifest.add(entry("p%02d".formatted(i), 10, 0));
        }

        IntegrityReport report = IntegrityChecker.check(RUN, manifest, new HashMap<>());

        assertThat(report.playersChecked()).isEqualTo(30);
        assertThat(report.playersPassed()).isZero();
        assertThat(report.failures()).hasSize(IntegrityChecker.MAX_LISTED_FAILURES);
    }

    @Test
    void anEmptyManifestIsNotAPass() {
        assertThat(IntegrityChecker.check(RUN, List.of(), Map.of()).passed()).isFalse();
    }
}
