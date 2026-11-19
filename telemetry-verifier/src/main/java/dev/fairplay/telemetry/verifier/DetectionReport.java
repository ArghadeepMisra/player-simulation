package dev.fairplay.telemetry.verifier;

import java.util.List;
import java.util.Map;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.Segment;

/**
 * The result of scoring the flags against ground truth. All ratios are 0.0 when their denominator is 0
 * (DECISION: one rule for every undefined ratio, so a report never contains NaN).
 */
public record DetectionReport(
        String runId,
        long startedAt,
        long endedAt,
        int cheaters,
        int honest,
        int pro,
        int detected,
        /** Detected cheaters whose earliest qualifying flag has the rule matching their cheat family. */
        int correctRule,
        /** Cheaters flagged before their cheat started. Reported only; it does not change recall. */
        int earlyFlags,
        int falsePositivesHonest,
        int falsePositivesPro,
        double recall,
        double fpr,
        double fprHonest,
        double fprPro,
        double precision,
        Map<Rule, Long> flagsByRule,
        List<LatencyRow> latency,
        List<Missed> missed,
        List<FalsePositive> falsePositives) {

    public int falsePositiveCount() {
        return falsePositivesHonest + falsePositivesPro;
    }

    /** One row of the latency table; {@code cheatType} is null for the overall row. Latencies are in seconds. */
    public record LatencyRow(CheatType cheatType, int count, int detected, Double p50, Double p95, Double max) {
    }

    public record Missed(String username, CheatType cheatType, long cheatStartsAt) {
    }

    /** A honest or pro player with a flag, described by their first flag. */
    public record FalsePositive(String username, Segment segment, Rule rule, double score, Evidence evidence) {
    }
}
