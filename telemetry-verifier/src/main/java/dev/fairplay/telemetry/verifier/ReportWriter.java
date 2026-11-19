package dev.fairplay.telemetry.verifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.common.model.Rule;
import org.springframework.stereotype.Component;

/**
 * Renders a report as Markdown and writes it to {@code reports/detection-<runId>.md} or
 * {@code reports/integrity-<runId>.md}. The first lines are the run id, the start and end times (ISO-8601) and the
 * verifier mode.
 */
@Component
public class ReportWriter {

    private final Path reportsDir;

    public ReportWriter(VerifierProperties props) {
        this.reportsDir = Path.of(props.reportsDir());
    }

    public String renderDetection(DetectionReport r) {
        StringBuilder md = header("detection", r.runId(), r.startedAt(), r.endedAt());
        md.append("## Summary\n\n| Measure | Value |\n| --- | --- |\n");
        row(md, "Cheaters", r.cheaters());
        row(md, "Detected cheaters", r.detected());
        row(md, "Detected with the correct rule", r.correctRule());
        row(md, "Recall", percent(r.recall()));
        row(md, "False-positive rate (honest + pro)", percent(r.fpr()));
        row(md, "False-positive rate (honest only)", percent(r.fprHonest()) + " of " + r.honest());
        row(md, "False-positive rate (pro only)", percent(r.fprPro()) + " of " + r.pro());
        row(md, "Precision", percent(r.precision()));
        row(md, "Cheaters flagged before their cheat started", r.earlyFlags());
        for (Rule rule : Rule.values()) {
            row(md, "Flags, rule " + rule, r.flagsByRule().getOrDefault(rule, 0L));
        }

        md.append("\n## Detection latency (seconds after the cheat started)\n\n")
                .append("| Cheat type | Cheaters | Detected | p50 | p95 | max |\n| --- | --- | --- | --- | --- | --- |\n");
        for (DetectionReport.LatencyRow l : r.latency()) {
            md.append("| ").append(l.cheatType() == null ? "**ALL**" : l.cheatType().name())
                    .append(" | ").append(l.count()).append(" | ").append(l.detected())
                    .append(" | ").append(seconds(l.p50())).append(" | ").append(seconds(l.p95()))
                    .append(" | ").append(seconds(l.max())).append(" |\n");
        }

        md.append("\n## Missed cheaters\n\n");
        if (r.missed().isEmpty()) {
            md.append("None.\n");
        } else {
            md.append("| Player | Cheat type | Cheat started |\n| --- | --- | --- |\n");
            for (DetectionReport.Missed m : r.missed()) {
                md.append("| ").append(m.username()).append(" | ").append(m.cheatType()).append(" | ")
                        .append(Instant.ofEpochMilli(m.cheatStartsAt())).append(" |\n");
            }
        }

        md.append("\n## False positives\n\n");
        if (r.falsePositives().isEmpty()) {
            md.append("None.\n");
        } else {
            md.append("| Player | Segment | Rule | Score | Evidence of the first flag |\n| --- | --- | --- | --- | --- |\n");
            for (DetectionReport.FalsePositive f : r.falsePositives()) {
                md.append("| ").append(f.username()).append(" | ").append(f.segment()).append(" | ")
                        .append(f.rule()).append(" | ").append(String.format(Locale.ROOT, "%.3f", f.score()))
                        .append(" | ").append(evidence(f.evidence())).append(" |\n");
            }
        }
        return md.toString();
    }

    public String renderIntegrity(IntegrityReport r) {
        StringBuilder md = header("integrity", r.runId(), r.startedAt(), r.endedAt());
        md.append("## Result: ").append(r.passed() ? "PASS" : "FAIL").append("\n\n");
        md.append("| Measure | Value |\n| --- | --- |\n");
        row(md, "Players checked", r.playersChecked());
        row(md, "Players passed", r.playersPassed());
        row(md, "Events expected (sum of finalSeq)", r.eventsExpected());
        row(md, "Events counted (sum of count)", r.eventsCounted());
        row(md, "Duplicates", r.duplicates());
        row(md, "Gaps", r.gaps());
        row(md, "Players with no stats", r.playersWithoutStats());

        md.append("\n## Failing players");
        int failing = r.playersChecked() - r.playersPassed();
        if (r.failures().isEmpty()) {
            md.append("\n\nNone.\n");
        } else {
            md.append(" (").append(r.failures().size()).append(" of ").append(failing).append(" shown)\n\n")
                    .append("| Player | Manifest | Integrity stats | Reasons |\n| --- | --- | --- | --- |\n");
            for (IntegrityReport.Failure f : r.failures()) {
                md.append("| ").append(f.manifest().playerId()).append(" | finalSeq=").append(f.manifest().finalSeq())
                        .append(" acked=").append(f.manifest().acked()).append(" failed=").append(f.manifest().failed())
                        .append(" | ");
                if (f.stats() == null) {
                    md.append("none");
                } else {
                    md.append("count=").append(f.stats().count()).append(" lastSeq=").append(f.stats().lastSeq())
                            .append(" duplicates=").append(f.stats().duplicates())
                            .append(" gaps=").append(f.stats().gaps());
                }
                md.append(" | ").append(String.join("; ", f.reasons())).append(" |\n");
            }
        }
        return md.toString();
    }

    /** Writes {@code content} to {@code <reports-dir>/<mode>-<runId>.md}, creating the directory, and returns the path. */
    public Path write(String mode, String runId, String content) {
        Path file = reportsDir.resolve(mode + "-" + runId + ".md");
        try {
            Files.createDirectories(reportsDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
        return file;
    }

    private static StringBuilder header(String mode, String runId, long startedAt, long endedAt) {
        return new StringBuilder("# Telemetry verifier: ").append(mode).append("\n\n")
                .append("- Run id: ").append(runId).append('\n')
                .append("- Started: ").append(Instant.ofEpochMilli(startedAt)).append('\n')
                .append("- Ended: ").append(Instant.ofEpochMilli(endedAt)).append('\n')
                .append("- Mode: ").append(mode).append("\n\n");
    }

    private static void row(StringBuilder md, String label, Object value) {
        md.append("| ").append(label).append(" | ").append(value).append(" |\n");
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100);
    }

    private static String seconds(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String evidence(Evidence e) {
        if (e == null) {
            return "-";
        }
        if (e.shots() != null) {
            return "shots=" + e.shots() + " hits=" + e.hits() + " headshotHits=" + e.headshotHits()
                    + " headshotRate=" + e.headshotRate() + " avgReactionMs=" + e.avgReactionMs();
        }
        return "movingSamples=" + e.movingSamples() + " overspeedSamples=" + e.overspeedSamples()
                + " overspeedRatio=" + e.overspeedRatio() + " maxSpeed=" + e.maxSpeed();
    }
}
