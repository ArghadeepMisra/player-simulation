package dev.fairplay.telemetry.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.RunInfo;
import dev.fairplay.telemetry.common.model.Segment;
import dev.fairplay.telemetry.common.model.SkillTier;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifierRunnerTest {

    private static final String RUN = "r-test01";
    private static final long START = 1_763_372_100_000L;

    @TempDir
    Path dir;

    private TopicSnapshotReader reader;
    private VerifierRunner runner;

    @BeforeEach
    void setUp() {
        reader = mock(TopicSnapshotReader.class);
        VerifierProperties props = new VerifierProperties(dir.toString(), Duration.ofSeconds(1), Duration.ofMillis(10));
        runner = new VerifierRunner(reader, new ReportWriter(props), props);
    }

    private void finishedRun() {
        RunInfo info = new RunInfo(RUN, START, START + 300_000, 2, 200, 0, 0);
        when(reader.readLatest(Topics.SIMULATION_MANIFEST)).thenReturn(Map.of(
                Topics.RUN_KEY, JsonSerde.MAPPER.writeValueAsBytes(info),
                "a", JsonSerde.MAPPER.writeValueAsBytes(new ManifestEntry(RUN, "a", 100, 100, 0)),
                "b", JsonSerde.MAPPER.writeValueAsBytes(new ManifestEntry(RUN, "b", 100, 100, 0)),
                "stale", JsonSerde.MAPPER.writeValueAsBytes(new ManifestEntry("r-old", "stale", 7, 7, 0))));
    }

    private void detectionData(boolean catchTheCheater) {
        when(reader.readLatest(Topics.CHEATER_GROUND_TRUTH, GroundTruth.class)).thenReturn(Map.of(
                "a", new GroundTruth(RUN, "a", Segment.CHEATER, CheatType.AIMBOT, START + 10_000),
                "b", new GroundTruth(RUN, "b", Segment.HONEST, CheatType.NONE, null)));
        when(reader.readLatest(Topics.PLAYER_PROFILES, PlayerProfile.class)).thenReturn(Map.of(
                "a", new PlayerProfile("a", "sim_0001", "IN", SkillTier.GOLD, START)));
        List<SuspiciousPlayer> flags = catchTheCheater
                ? List.of(new SuspiciousPlayer("f1", "a", "sim_0001", SkillTier.GOLD, Rule.AIMBOT, 0.9, START, START,
                        START + 12_000, Evidence.aimbot(15, 14, 13, 0.93, 60.0), "1.0.0"))
                : List.of();
        when(reader.readAll(Topics.SUSPICIOUS_PLAYERS, SuspiciousPlayer.class)).thenReturn(flags);
    }

    private void integrityStats(long countOfA) {
        when(reader.readLatest(Topics.INTEGRITY_STATS, IntegrityStats.class)).thenReturn(Map.of(
                "a", new IntegrityStats("a", countOfA, 100, 0, 0, 1),
                "b", new IntegrityStats("b", 100, 100, 0, 0, 1)));
    }

    private int run(String... args) {
        runner.run(args);
        return runner.getExitCode();
    }

    @Test
    void detectionWritesAReportAndExitsZero() throws Exception {
        finishedRun();
        detectionData(true);

        assertThat(run("--mode=detection")).isEqualTo(0);

        String report = Files.readString(dir.resolve("detection-" + RUN + ".md"));
        assertThat(report).contains("| Recall | 100.00% |", "| AIMBOT | 1 | 1 | 2.0 | 2.0 | 2.0 |");
    }

    @Test
    void detectionExitsTwoWhenRecallIsBelowTheMinimum() {
        finishedRun();
        detectionData(false);

        assertThat(run("--mode=detection", "--min-recall=0.98")).isEqualTo(2);
    }

    @Test
    void detectionExitsTwoWhenTheFalsePositiveRateIsAboveTheMaximum() {
        finishedRun();
        detectionData(true);
        when(reader.readAll(Topics.SUSPICIOUS_PLAYERS, SuspiciousPlayer.class)).thenReturn(List.of(
                new SuspiciousPlayer("f1", "b", null, null, Rule.AIMBOT, 0.9, START, START, START + 5_000,
                        Evidence.aimbot(15, 14, 13, 0.93, 60.0), "1.0.0")));

        assertThat(run("--mode=detection", "--max-fpr=0.01")).isEqualTo(2);
    }

    @Test
    void detectionMeetingTheThresholdsExitsZero() {
        finishedRun();
        detectionData(true);

        assertThat(run("--mode=detection", "--min-recall=0.98", "--max-fpr=0.01")).isEqualTo(0);
    }

    @Test
    void bothModesExitThreeWhenTheSimulationHasNotFinished() {
        when(reader.readLatest(Topics.SIMULATION_MANIFEST)).thenReturn(Map.of());

        assertThat(run("--mode=detection")).isEqualTo(3);
        assertThat(run("--mode=integrity")).isEqualTo(3);
    }

    @Test
    void integrityPassingExitsZeroAndIgnoresStaleManifestEntries() throws Exception {
        finishedRun();
        integrityStats(100);

        assertThat(run("--mode=integrity")).isEqualTo(0);

        String report = Files.readString(dir.resolve("integrity-" + RUN + ".md"));
        assertThat(report).contains("## Result: PASS", "| Players checked | 2 |");
    }

    @Test
    void integrityFailingExitsOneWithoutWaitingWhenNoSettleIsGiven() {
        finishedRun();
        integrityStats(97);

        assertThat(run("--mode=integrity")).isEqualTo(1);
        verify(reader, times(1)).readLatest(Topics.INTEGRITY_STATS, IntegrityStats.class);
    }

    @Test
    void integrityWithSettleReReadsUntilEverythingPasses() {
        finishedRun();
        Map<String, IntegrityStats> behind = Map.of(
                "a", new IntegrityStats("a", 90, 90, 0, 0, 1),
                "b", new IntegrityStats("b", 100, 100, 0, 0, 1));
        Map<String, IntegrityStats> caughtUp = Map.of(
                "a", new IntegrityStats("a", 100, 100, 0, 0, 2),
                "b", new IntegrityStats("b", 100, 100, 0, 0, 2));
        when(reader.readLatest(Topics.INTEGRITY_STATS, IntegrityStats.class)).thenReturn(behind, behind, caughtUp);

        assertThat(run("--mode=integrity", "--settle=30s")).isEqualTo(0);
        verify(reader, times(3)).readLatest(Topics.INTEGRITY_STATS, IntegrityStats.class);
    }

    @Test
    void integrityWithSettleGivesUpWhenTheTimeIsUp() {
        finishedRun();
        integrityStats(90);

        assertThat(run("--mode=integrity", "--settle=1s")).isEqualTo(1);
    }

    @Test
    void badArgumentsExitFour() {
        assertThat(run()).isEqualTo(4);
        assertThat(run("--mode=nonsense")).isEqualTo(4);
        assertThat(run("--mode=detection", "--min-recall=lots")).isEqualTo(4);
        assertThat(run("--mode=integrity", "--settle=soon")).isEqualTo(4);
    }

    @Test
    void anInterruptedSettleExitsFourAndKeepsTheInterruptFlag() {
        finishedRun();
        integrityStats(90);
        Thread.currentThread().interrupt();

        try {
            assertThat(run("--mode=integrity", "--settle=30s")).isEqualTo(4);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void theExitCodeIsZeroBeforeAnythingRuns() {
        assertThat(runner.getExitCode()).isZero();
        verify(reader, times(0)).readAll(eq(Topics.SUSPICIOUS_PLAYERS), any());
    }
}
