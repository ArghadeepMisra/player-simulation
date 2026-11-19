package dev.fairplay.telemetry.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.RunInfo;
import dev.fairplay.telemetry.common.model.Segment;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;

/**
 * Scores detection against ground truth. Pure math: no Kafka, no clock.
 *
 * <p>Flags before the run's {@code startedAt}, and flags for players that are not in this run's ground truth, are
 * ignored. A cheater is detected when at least one flag has {@code detectedAt >= cheatStartsAt}; the latency is the
 * earliest such flag minus {@code cheatStartsAt}.
 */
public final class DetectionEvaluator {

    private static final List<CheatType> CHEAT_TYPES = List.of(
            CheatType.AIMBOT, CheatType.AIMBOT_SUBTLE, CheatType.SPEEDHACK, CheatType.SPEEDHACK_SUBTLE);

    private DetectionEvaluator() {
    }

    public static DetectionReport evaluate(RunInfo run, Collection<GroundTruth> truth,
                                           Collection<SuspiciousPlayer> flags, Map<String, String> usernames) {
        Map<String, GroundTruth> byPlayer = new HashMap<>();
        for (GroundTruth t : truth) {
            if (run.runId().equals(t.runId())) {
                byPlayer.put(t.playerId(), t);
            }
        }

        Map<String, List<SuspiciousPlayer>> flagsByPlayer = new HashMap<>();
        Map<Rule, Long> flagsByRule = new EnumMap<>(Rule.class);
        for (SuspiciousPlayer flag : flags) {
            if (flag.detectedAt() < run.startedAt() || !byPlayer.containsKey(flag.playerId())) {
                continue;
            }
            flagsByPlayer.computeIfAbsent(flag.playerId(), k -> new ArrayList<>()).add(flag);
            flagsByRule.merge(flag.rule(), 1L, Long::sum);
        }
        flagsByPlayer.values().forEach(list -> list.sort(Comparator.comparingLong(SuspiciousPlayer::detectedAt)));

        int cheaters = 0;
        int honest = 0;
        int pro = 0;
        int detected = 0;
        int correctRule = 0;
        int early = 0;
        int fpHonest = 0;
        int fpPro = 0;
        List<DetectionReport.Missed> missed = new ArrayList<>();
        List<DetectionReport.FalsePositive> falsePositives = new ArrayList<>();
        Map<CheatType, List<Double>> latencies = new EnumMap<>(CheatType.class);
        Map<CheatType, Integer> counts = new EnumMap<>(CheatType.class);

        List<GroundTruth> ordered = new ArrayList<>(byPlayer.values());
        ordered.sort(Comparator.comparing(GroundTruth::playerId));
        for (GroundTruth t : ordered) {
            List<SuspiciousPlayer> own = flagsByPlayer.getOrDefault(t.playerId(), List.of());
            String name = usernames.getOrDefault(t.playerId(), t.playerId());
            if (t.segment() == Segment.CHEATER) {
                cheaters++;
                counts.merge(t.cheatType(), 1, Integer::sum);
                long start = t.cheatStartsAt();
                if (own.stream().anyMatch(f -> f.detectedAt() < start)) {
                    early++;
                }
                SuspiciousPlayer first = own.stream().filter(f -> f.detectedAt() >= start).findFirst().orElse(null);
                if (first == null) {
                    missed.add(new DetectionReport.Missed(name, t.cheatType(), start));
                    continue;
                }
                detected++;
                if (first.rule() == familyOf(t.cheatType())) {
                    correctRule++;
                }
                latencies.computeIfAbsent(t.cheatType(), k -> new ArrayList<>())
                        .add((first.detectedAt() - start) / 1000.0);
            } else {
                if (t.segment() == Segment.PRO) {
                    pro++;
                } else {
                    honest++;
                }
                if (!own.isEmpty()) {
                    if (t.segment() == Segment.PRO) {
                        fpPro++;
                    } else {
                        fpHonest++;
                    }
                    SuspiciousPlayer first = own.get(0);
                    falsePositives.add(new DetectionReport.FalsePositive(name, t.segment(), first.rule(),
                            first.score(), first.evidence()));
                }
            }
        }

        List<DetectionReport.LatencyRow> rows = new ArrayList<>();
        List<Double> all = new ArrayList<>();
        for (CheatType type : CHEAT_TYPES) {
            List<Double> own = latencies.getOrDefault(type, List.of());
            all.addAll(own);
            rows.add(row(type, counts.getOrDefault(type, 0), own));
        }
        rows.add(row(null, cheaters, all));

        int fp = fpHonest + fpPro;
        return new DetectionReport(run.runId(), run.startedAt(), run.endedAt(), cheaters, honest, pro, detected,
                correctRule, early, fpHonest, fpPro,
                ratio(detected, cheaters), ratio(fp, honest + pro), ratio(fpHonest, honest), ratio(fpPro, pro),
                ratio(detected, detected + fp), flagsByRule, rows, missed, falsePositives);
    }

    /** The rule that counts as correct for a cheat type: {@code AIMBOT*} needs AIMBOT, {@code SPEEDHACK*} needs SPEEDHACK. */
    static Rule familyOf(CheatType type) {
        return switch (type) {
            case AIMBOT, AIMBOT_SUBTLE -> Rule.AIMBOT;
            case SPEEDHACK, SPEEDHACK_SUBTLE -> Rule.SPEEDHACK;
            case NONE -> throw new IllegalArgumentException("NONE has no cheat family");
        };
    }

    /** Nearest rank: sort ascending, take index {@code ceil(p / 100 * n) - 1}. Null for an empty list. */
    static Double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(p * sorted.size() / 100.0) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static DetectionReport.LatencyRow row(CheatType type, int count, List<Double> latencies) {
        return new DetectionReport.LatencyRow(type, count, latencies.size(), percentile(latencies, 50),
                percentile(latencies, 95), latencies.isEmpty() ? null : percentile(latencies, 100));
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
