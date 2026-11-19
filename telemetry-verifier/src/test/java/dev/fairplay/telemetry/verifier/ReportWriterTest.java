package dev.fairplay.telemetry.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.Segment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

    @TempDir
    Path dir;

    private ReportWriter writer() {
        return new ReportWriter(new VerifierProperties(dir.resolve("reports").toString(), Duration.ofSeconds(1),
                Duration.ofSeconds(1)));
    }

    private DetectionReport detection(boolean withProblems) {
        Map<Rule, Long> byRule = new EnumMap<>(Rule.class);
        byRule.put(Rule.AIMBOT, 3L);
        return new DetectionReport("r-abc123", 1_763_372_100_000L, 1_763_372_400_000L, 50, 930, 20, 49, 48, 2, 0, 1,
                0.98, 0.0105, 0.0, 0.05, 0.98, byRule,
                List.of(new DetectionReport.LatencyRow(CheatType.AIMBOT, 20, 20, 2.0, 4.0, 5.0),
                        new DetectionReport.LatencyRow(CheatType.SPEEDHACK, 12, 0, null, null, null),
                        new DetectionReport.LatencyRow(null, 32, 20, 2.0, 4.0, 5.0)),
                withProblems ? List.of(new DetectionReport.Missed("sim_0007", CheatType.AIMBOT_SUBTLE, 1_763_372_150_000L))
                        : List.of(),
                withProblems ? List.of(
                        new DetectionReport.FalsePositive("sim_0042", Segment.PRO, Rule.AIMBOT, 0.7,
                                Evidence.aimbot(14, 12, 9, 0.75, 130.0)),
                        new DetectionReport.FalsePositive("sim_0043", Segment.HONEST, Rule.SPEEDHACK, 0.6,
                                Evidence.speedhack(45, 8, 0.1778, 7.9)),
                        new DetectionReport.FalsePositive("sim_0044", Segment.HONEST, Rule.SPEEDHACK, 0.6, null))
                        : List.of());
    }

    @Test
    void detectionReportStartsWithRunIdTimesAndMode() {
        String md = writer().renderDetection(detection(false));

        assertThat(md).startsWith("# Telemetry verifier: detection");
        assertThat(md).contains("- Run id: r-abc123", "- Started: 2025-11-17T", "- Mode: detection");
    }

    @Test
    void detectionReportShowsTheSummaryAndLatencyTables() {
        String md = writer().renderDetection(detection(false));

        assertThat(md).contains("| Recall | 98.00% |", "| False-positive rate (honest + pro) | 1.05% |",
                "| False-positive rate (pro only) | 5.00% of 20 |", "| Flags, rule AIMBOT | 3 |",
                "| Flags, rule SPEEDHACK | 0 |");
        assertThat(md).contains("| AIMBOT | 20 | 20 | 2.0 | 4.0 | 5.0 |", "| SPEEDHACK | 12 | 0 | - | - | - |",
                "| **ALL** | 32 | 20 | 2.0 | 4.0 | 5.0 |");
        assertThat(md).contains("## Missed cheaters\n\nNone.", "## False positives\n\nNone.");
    }

    @Test
    void detectionReportListsMissedCheatersAndFalsePositivesWithEvidence() {
        String md = writer().renderDetection(detection(true));

        assertThat(md).contains("| sim_0007 | AIMBOT_SUBTLE | 2025-11-17T");
        assertThat(md).contains("| sim_0042 | PRO | AIMBOT | 0.700 | shots=14 hits=12 headshotHits=9 headshotRate=0.75 avgReactionMs=130.0 |");
        assertThat(md).contains("movingSamples=45 overspeedSamples=8 overspeedRatio=0.1778 maxSpeed=7.9");
        assertThat(md).contains("| sim_0044 | HONEST | SPEEDHACK | 0.600 | - |");
    }

    @Test
    void integrityReportShowsPassAndTheCounters() {
        IntegrityReport report = new IntegrityReport("r-abc123", 1000, 2000, 1000, 1000, 7_500_000, 7_500_000, 0, 0, 0,
                List.of());

        String md = writer().renderIntegrity(report);

        assertThat(md).contains("# Telemetry verifier: integrity", "## Result: PASS", "| Players passed | 1000 |",
                "| Events expected (sum of finalSeq) | 7500000 |", "| Duplicates | 0 |", "## Failing players\n\nNone.");
    }

    @Test
    void integrityReportListsFailuresSideBySide() {
        ManifestEntry manifest = new ManifestEntry("r-abc123", "p1", 100, 100, 0);
        IntegrityReport report = new IntegrityReport("r-abc123", 1000, 2000, 3, 1, 300, 297, 4, 1, 1, List.of(
                new IntegrityReport.Failure(manifest, new IntegrityStats("p1", 97, 100, 4, 1, 5), List.of("duplicates=4")),
                new IntegrityReport.Failure(new ManifestEntry("r-abc123", "p2", 100, 100, 0), null,
                        List.of("no integrity stats"))));

        String md = writer().renderIntegrity(report);

        assertThat(md).contains("## Result: FAIL", "## Failing players (2 of 2 shown)",
                "| p1 | finalSeq=100 acked=100 failed=0 | count=97 lastSeq=100 duplicates=4 gaps=1 | duplicates=4 |",
                "| p2 | finalSeq=100 acked=100 failed=0 | none | no integrity stats |");
    }

    @Test
    void writesTheFileUnderTheReportsDirectoryCreatingIt() throws IOException {
        Path file = writer().write("detection", "r-abc123", "hello");

        assertThat(file).isEqualTo(dir.resolve("reports").resolve("detection-r-abc123.md"));
        assertThat(Files.readString(file)).isEqualTo("hello");
    }

    @Test
    void reportsAnUnwritableDirectoryAsAnError() throws IOException {
        Path blocker = Files.writeString(dir.resolve("file"), "not a directory");
        ReportWriter writer = new ReportWriter(new VerifierProperties(blocker.toString(), Duration.ofSeconds(1),
                Duration.ofSeconds(1)));

        assertThatThrownBy(() -> writer.write("detection", "r-1", "x")).isInstanceOf(java.io.UncheckedIOException.class);
    }
}
