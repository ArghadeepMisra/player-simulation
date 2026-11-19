package dev.fairplay.telemetry.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.RunInfo;
import dev.fairplay.telemetry.common.model.Segment;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import org.junit.jupiter.api.Test;

class DetectionEvaluatorTest {

    private static final String RUN = "r-test01";
    private static final long START = 1_000_000L;
    private static final RunInfo RUN_INFO = new RunInfo(RUN, START, START + 300_000, 7, 0, 0, 0);

    private static GroundTruth cheater(String id, CheatType type, long startsAt) {
        return new GroundTruth(RUN, id, Segment.CHEATER, type, startsAt);
    }

    private static GroundTruth player(String id, Segment segment) {
        return new GroundTruth(RUN, id, segment, CheatType.NONE, null);
    }

    private static SuspiciousPlayer flag(String id, Rule rule, long detectedAt) {
        return new SuspiciousPlayer("f-" + id + "-" + detectedAt, id, null, null, rule, 0.9, detectedAt - 5000,
                detectedAt, detectedAt, Evidence.aimbot(15, 14, 13, 0.9286, 60.0), "1.0.0");
    }

    private static final Map<String, String> NAMES = Map.of("a1", "sim_a1", "a2", "sim_a2", "h1", "sim_h1");

    /**
     * Seven players: a1 caught quickly, a2 (subtle) missed, s1 caught but first by the wrong rule, s2 flagged early and
     * then caught, honest h1 wrongly flagged, honest h2 only flagged before the run, pro p1 never flagged.
     */
    private DetectionReport scenario() {
        long a1Start = START + 10_000;
        long s1Start = START + 10_000;
        long s2Start = START + 30_000;
        List<GroundTruth> truth = List.of(
                cheater("a1", CheatType.AIMBOT, a1Start),
                cheater("a2", CheatType.AIMBOT_SUBTLE, START + 20_000),
                cheater("s1", CheatType.SPEEDHACK, s1Start),
                cheater("s2", CheatType.SPEEDHACK_SUBTLE, s2Start),
                player("h1", Segment.HONEST),
                player("h2", Segment.HONEST),
                player("p1", Segment.PRO));
        List<SuspiciousPlayer> flags = List.of(
                flag("a1", Rule.AIMBOT, a1Start + 3_000),
                flag("a1", Rule.AIMBOT, a1Start + 20_000),
                flag("s1", Rule.AIMBOT, s1Start + 7_000),
                flag("s1", Rule.SPEEDHACK, s1Start + 12_000),
                flag("s2", Rule.SPEEDHACK, s2Start - 5_000),
                flag("s2", Rule.SPEEDHACK, s2Start + 9_000),
                flag("h1", Rule.SPEEDHACK, START + 50_000),
                flag("h2", Rule.AIMBOT, START - 1),
                flag("nobody", Rule.AIMBOT, START + 40_000));
        return DetectionEvaluator.evaluate(RUN_INFO, truth, flags, NAMES);
    }

    @Test
    void countsPlayersBySegment() {
        DetectionReport r = scenario();

        assertThat(r.cheaters()).isEqualTo(4);
        assertThat(r.honest()).isEqualTo(2);
        assertThat(r.pro()).isEqualTo(1);
        assertThat(r.runId()).isEqualTo(RUN);
    }

    @Test
    void recallPrecisionAndFalsePositiveRates() {
        DetectionReport r = scenario();

        assertThat(r.detected()).isEqualTo(3);
        assertThat(r.recall()).isEqualTo(0.75);
        assertThat(r.falsePositivesHonest()).isEqualTo(1);
        assertThat(r.falsePositivesPro()).isZero();
        assertThat(r.falsePositiveCount()).isEqualTo(1);
        assertThat(r.fpr()).isEqualTo(1.0 / 3);
        assertThat(r.fprHonest()).isEqualTo(0.5);
        assertThat(r.fprPro()).isZero();
        assertThat(r.precision()).isEqualTo(0.75);
    }

    @Test
    void ignoresFlagsBeforeTheRunAndFlagsForUnknownPlayers() {
        DetectionReport r = scenario();

        // h2's only flag predates the run, and "nobody" is not in the ground truth.
        assertThat(r.falsePositives()).extracting(DetectionReport.FalsePositive::username).containsExactly("sim_h1");
        assertThat(r.flagsByRule()).containsEntry(Rule.AIMBOT, 3L).containsEntry(Rule.SPEEDHACK, 4L);
    }

    @Test
    void correctRuleLooksAtTheEarliestQualifyingFlagOnly() {
        DetectionReport r = scenario();

        // a1 AIMBOT ok; s1's earliest qualifying flag is AIMBOT (wrong); s2's earliest qualifying is SPEEDHACK ok.
        assertThat(r.correctRule()).isEqualTo(2);
    }

    @Test
    void aFlagBeforeTheCheatStartedCountsAsEarlyButNotAsDetection() {
        DetectionReport r = scenario();

        assertThat(r.earlyFlags()).isEqualTo(1);
        // s2 is still detected, by the flag after the cheat started, 9 s in.
        assertThat(r.latency().stream().filter(l -> l.cheatType() == CheatType.SPEEDHACK_SUBTLE).findFirst().orElseThrow())
                .satisfies(l -> {
                    assertThat(l.detected()).isEqualTo(1);
                    assertThat(l.p50()).isEqualTo(9.0);
                });
    }

    @Test
    void latencyRowsPerCheatTypeAndOverall() {
        DetectionReport r = scenario();

        assertThat(r.latency()).extracting(DetectionReport.LatencyRow::cheatType)
                .containsExactly(CheatType.AIMBOT, CheatType.AIMBOT_SUBTLE, CheatType.SPEEDHACK,
                        CheatType.SPEEDHACK_SUBTLE, null);
        DetectionReport.LatencyRow aimbot = r.latency().get(0);
        assertThat(aimbot.count()).isEqualTo(1);
        assertThat(aimbot.detected()).isEqualTo(1);
        assertThat(aimbot.p50()).isEqualTo(3.0);
        DetectionReport.LatencyRow subtle = r.latency().get(1);
        assertThat(subtle.count()).isEqualTo(1);
        assertThat(subtle.detected()).isZero();
        assertThat(subtle.p50()).isNull();
        assertThat(subtle.max()).isNull();
        DetectionReport.LatencyRow overall = r.latency().get(4);
        assertThat(overall.count()).isEqualTo(4);
        assertThat(overall.detected()).isEqualTo(3);
        assertThat(overall.p50()).isEqualTo(7.0);
        assertThat(overall.p95()).isEqualTo(9.0);
        assertThat(overall.max()).isEqualTo(9.0);
    }

    @Test
    void listsMissedCheatersWithTheirUsername() {
        DetectionReport r = scenario();

        assertThat(r.missed()).containsExactly(
                new DetectionReport.Missed("sim_a2", CheatType.AIMBOT_SUBTLE, START + 20_000));
    }

    @Test
    void falsePositivesCarryTheEvidenceOfTheirFirstFlag() {
        DetectionReport r = scenario();

        DetectionReport.FalsePositive fp = r.falsePositives().get(0);
        assertThat(fp.segment()).isEqualTo(Segment.HONEST);
        assertThat(fp.rule()).isEqualTo(Rule.SPEEDHACK);
        assertThat(fp.score()).isEqualTo(0.9);
        assertThat(fp.evidence()).isNotNull();
    }

    @Test
    void aProFlaggedIsAProFalsePositive() {
        DetectionReport r = DetectionEvaluator.evaluate(RUN_INFO,
                List.of(player("p1", Segment.PRO), player("h1", Segment.HONEST)),
                List.of(flag("p1", Rule.AIMBOT, START + 1000)), Map.of());

        assertThat(r.falsePositivesPro()).isEqualTo(1);
        assertThat(r.fprPro()).isEqualTo(1.0);
        assertThat(r.fprHonest()).isZero();
        // With no username known, the player id is shown.
        assertThat(r.falsePositives().get(0).username()).isEqualTo("p1");
    }

    @Test
    void groundTruthOfAnotherRunIsIgnored() {
        GroundTruth stale = new GroundTruth("r-old", "a1", Segment.CHEATER, CheatType.AIMBOT, START);

        DetectionReport r = DetectionEvaluator.evaluate(RUN_INFO, List.of(stale), List.of(), Map.of());

        assertThat(r.cheaters()).isZero();
    }

    @Test
    void undefinedRatiosAreZeroNotNaN() {
        DetectionReport r = DetectionEvaluator.evaluate(RUN_INFO, List.of(), List.of(), Map.of());

        assertThat(r.recall()).isZero();
        assertThat(r.fpr()).isZero();
        assertThat(r.precision()).isZero();
        assertThat(r.latency()).hasSize(5);
    }

    @Test
    void nearestRankPercentiles() {
        assertThat(DetectionEvaluator.percentile(List.of(), 50)).isNull();
        // n = 1: every percentile is the one value.
        assertThat(DetectionEvaluator.percentile(List.of(4.0), 50)).isEqualTo(4.0);
        assertThat(DetectionEvaluator.percentile(List.of(4.0), 95)).isEqualTo(4.0);
        // n = 2: p50 is the first value (ceil(1) - 1 = 0), p95 the second (ceil(1.9) - 1 = 1).
        assertThat(DetectionEvaluator.percentile(List.of(8.0, 2.0), 50)).isEqualTo(2.0);
        assertThat(DetectionEvaluator.percentile(List.of(8.0, 2.0), 95)).isEqualTo(8.0);
        // n = 10: p50 is the 5th value, p95 the 10th (ceil(9.5) - 1 = 9).
        List<Double> ten = List.of(10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0);
        assertThat(DetectionEvaluator.percentile(ten, 50)).isEqualTo(5.0);
        assertThat(DetectionEvaluator.percentile(ten, 95)).isEqualTo(10.0);
        assertThat(DetectionEvaluator.percentile(ten, 100)).isEqualTo(10.0);
    }

    @Test
    void cheatFamiliesMatchTheRules() {
        assertThat(DetectionEvaluator.familyOf(CheatType.AIMBOT)).isEqualTo(Rule.AIMBOT);
        assertThat(DetectionEvaluator.familyOf(CheatType.AIMBOT_SUBTLE)).isEqualTo(Rule.AIMBOT);
        assertThat(DetectionEvaluator.familyOf(CheatType.SPEEDHACK)).isEqualTo(Rule.SPEEDHACK);
        assertThat(DetectionEvaluator.familyOf(CheatType.SPEEDHACK_SUBTLE)).isEqualTo(Rule.SPEEDHACK);
        assertThatThrownBy(() -> DetectionEvaluator.familyOf(CheatType.NONE)).isInstanceOf(IllegalArgumentException.class);
    }
}
