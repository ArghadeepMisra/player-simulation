package dev.fairplay.telemetry.verifier;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.RunInfo;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

/**
 * Runs one verifier mode and remembers the exit code, which {@link VerifierApplication} hands to the JVM.
 *
 * <pre>
 * --mode=detection [--min-recall=0.98] [--max-fpr=0.01]
 * --mode=integrity [--settle=60s]
 * </pre>
 *
 * <p>Exit codes: 0 success; 1 integrity failed; 2 detection missed a threshold given on the command line;
 * 3 the simulation has not finished (no {@code RunInfo}); 4 bad arguments.
 * DECISION: 4 is not in the spec, which only lists 0 to 3. Bad arguments need a code that does not look like a result.
 */
@Component
public class VerifierRunner implements CommandLineRunner, ExitCodeGenerator {

    static final int EXIT_OK = 0;
    static final int EXIT_INTEGRITY_FAILED = 1;
    static final int EXIT_THRESHOLD_MISSED = 2;
    static final int EXIT_NOT_FINISHED = 3;
    static final int EXIT_USAGE = 4;

    private static final Logger log = LoggerFactory.getLogger(VerifierRunner.class);

    private final TopicSnapshotReader reader;
    private final ReportWriter writer;
    private final Clock clock;
    private final Duration settlePollInterval;
    private volatile int exitCode = EXIT_OK;

    // @Autowired: with two constructors Spring cannot choose, and the package-private one exists for tests.
    @Autowired
    public VerifierRunner(TopicSnapshotReader reader, ReportWriter writer, VerifierProperties props) {
        this(reader, writer, props, Clock.systemUTC());
    }

    VerifierRunner(TopicSnapshotReader reader, ReportWriter writer, VerifierProperties props, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
        this.settlePollInterval = props.settlePollInterval();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run(String... args) {
        exitCode = execute(new DefaultApplicationArguments(args));
    }

    int execute(ApplicationArguments args) {
        String mode = single(args, "mode").orElse("");
        try {
            return switch (mode) {
                case "detection" -> detection(optionalDouble(args, "min-recall"), optionalDouble(args, "max-fpr"));
                case "integrity" -> integrity(single(args, "settle").map(VerifierRunner::duration).orElse(Duration.ZERO));
                default -> usage("--mode must be detection or integrity");
            };
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return usage("interrupted");
        }
    }

    private int detection(Optional<Double> minRecall, Optional<Double> maxFpr) {
        Optional<Manifest> manifest = readManifest();
        if (manifest.isEmpty()) {
            return notFinished();
        }
        RunInfo info = manifest.get().run();
        List<GroundTruth> truth = reader.readLatest(Topics.CHEATER_GROUND_TRUTH, GroundTruth.class).values().stream().toList();
        List<SuspiciousPlayer> flags = reader.readAll(Topics.SUSPICIOUS_PLAYERS, SuspiciousPlayer.class);
        Map<String, String> usernames = new HashMap<>();
        reader.readLatest(Topics.PLAYER_PROFILES, PlayerProfile.class)
                .forEach((id, profile) -> usernames.put(id, profile.username()));

        DetectionReport report = DetectionEvaluator.evaluate(info, truth, flags, usernames);
        publish("detection", info.runId(), writer.renderDetection(report));

        boolean missedRecall = minRecall.isPresent() && report.recall() < minRecall.get();
        boolean missedFpr = maxFpr.isPresent() && report.fpr() > maxFpr.get();
        if (missedRecall || missedFpr) {
            log.warn("Threshold missed: recall={} (min {}), fpr={} (max {})", report.recall(), minRecall.orElse(null),
                    report.fpr(), maxFpr.orElse(null));
            return EXIT_THRESHOLD_MISSED;
        }
        return EXIT_OK;
    }

    /**
     * Integrity stats are emitted every few seconds, so the last ones can arrive after the run ends. With
     * {@code --settle}, re-read until every player passes or the time is up, then report the final attempt.
     */
    private int integrity(Duration settle) throws InterruptedException {
        Optional<Manifest> read = readManifest();
        if (read.isEmpty()) {
            return notFinished();
        }
        RunInfo info = read.get().run();
        List<ManifestEntry> manifest = read.get().entries();

        long deadline = clock.millis() + settle.toMillis();
        IntegrityReport report;
        while (true) {
            Map<String, IntegrityStats> stats = reader.readLatest(Topics.INTEGRITY_STATS, IntegrityStats.class);
            report = IntegrityChecker.check(info, manifest, stats);
            if (report.passed() || clock.millis() >= deadline) {
                break;
            }
            log.info("Integrity not settled yet: {} of {} players pass", report.playersPassed(), report.playersChecked());
            Thread.sleep(settlePollInterval.toMillis());
        }
        publish("integrity", info.runId(), writer.renderIntegrity(report));
        return report.passed() ? EXIT_OK : EXIT_INTEGRITY_FAILED;
    }

    /** The run record and this run's entries, or empty when the simulator has not written {@code __run__} yet. */
    private Optional<Manifest> readManifest() {
        Map<String, byte[]> raw = reader.readLatest(Topics.SIMULATION_MANIFEST);
        byte[] runBytes = raw.get(Topics.RUN_KEY);
        if (runBytes == null) {
            return Optional.empty();
        }
        RunInfo run = JsonSerde.MAPPER.readValue(runBytes, RunInfo.class);
        List<ManifestEntry> entries = new ArrayList<>();
        raw.forEach((key, bytes) -> {
            if (!Topics.RUN_KEY.equals(key)) {
                ManifestEntry entry = JsonSerde.MAPPER.readValue(bytes, ManifestEntry.class);
                // Player ids repeat per seed, so an older run's entries can still be in the compacted topic.
                if (run.runId().equals(entry.runId())) {
                    entries.add(entry);
                }
            }
        });
        return Optional.of(new Manifest(run, entries));
    }

    private record Manifest(RunInfo run, List<ManifestEntry> entries) {
    }

    private int notFinished() {
        log.error("simulation has not finished");
        System.out.println("simulation has not finished");
        return EXIT_NOT_FINISHED;
    }

    private int usage(String problem) {
        log.error("{}. Usage: --mode=detection [--min-recall=0.98] [--max-fpr=0.01] | --mode=integrity [--settle=60s]",
                problem);
        return EXIT_USAGE;
    }

    private void publish(String mode, String runId, String markdown) {
        Path file = writer.write(mode, runId, markdown);
        System.out.println(markdown);
        log.info("Report written to {}", file.toAbsolutePath());
    }

    private static Optional<String> single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    private static Optional<Double> optionalDouble(ApplicationArguments args, String name) {
        return single(args, name).map(value -> {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " must be a number, got " + value);
            }
        });
    }

    private static Duration duration(String value) {
        try {
            return DurationStyle.detectAndParse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("--settle must be a duration such as 60s, got " + value);
        }
    }
}
