package dev.fairplay.telemetry.simulator.engine;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.RunInfo;
import dev.fairplay.telemetry.simulator.SimulatorProperties;
import dev.fairplay.telemetry.simulator.arena.ArenaPlayerRegistrar;
import dev.fairplay.telemetry.simulator.output.EventPublisher;
import dev.fairplay.telemetry.simulator.output.ManifestTracker;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import dev.fairplay.telemetry.simulator.population.PopulationFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs one simulation: builds the population, publishes profiles and ground truth, plays the matches on the shard
 * workers for {@code duration}, then publishes the manifest. The run happens on a platform thread named
 * {@code simulation-main}, so the app stays responsive and the actuator stays up. When the run ends the app keeps
 * running, so Prometheus can still scrape it.
 *
 * <p>DECISION: {@code telemetry.sim.autostart} (default true) switches the automatic start off. It is a plain
 * property read by {@code @ConditionalOnProperty}, not a field of {@link SimulatorProperties}, so no constructor
 * call changes. Tests that want a Spring context without a run set it to false.
 */
@Component
@ConditionalOnProperty(name = "telemetry.sim.autostart", havingValue = "true", matchIfMissing = true)
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);
    private static final long RATE_LOG_INTERVAL_MS = 10_000;
    private static final int RUN_ID_BYTES = 3;

    private final SimulatorProperties props;
    private final Clock clock;
    private final EventPublisher publisher;
    private final ManifestTracker manifest;
    private final MeterRegistry registry;
    private final Optional<ArenaPlayerRegistrar> registrar;
    private final CompletableFuture<RunInfo> completion = new CompletableFuture<>();
    private final List<ExecutorService> executors = new ArrayList<>();
    private volatile Thread mainThread;

    public SimulationEngine(SimulatorProperties props, Clock clock, EventPublisher publisher,
                            ManifestTracker manifest, MeterRegistry registry,
                            Optional<ArenaPlayerRegistrar> registrar) {
        this.props = props;
        this.clock = clock;
        this.publisher = publisher;
        this.manifest = manifest;
        this.registry = registry;
        this.registrar = registrar;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread thread = new Thread(this::runAndReport, "simulation-main");
        mainThread = thread;
        thread.start();
    }

    /** Completes with the {@link RunInfo} when the run is finished, or exceptionally when it failed. */
    public CompletableFuture<RunInfo> completion() {
        return completion;
    }

    @PreDestroy
    void stop() {
        Thread thread = mainThread;
        if (thread != null) {
            thread.interrupt();
        }
        shutdownExecutors();
    }

    private void runAndReport() {
        try {
            completion.complete(run());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Simulation interrupted before it finished");
            completion.completeExceptionally(e);
        } catch (Exception e) {
            log.error("Simulation failed", e);
            completion.completeExceptionally(e);
        }
    }

    /** The whole run, synchronously. Steps follow spec section 8, "Run lifecycle". */
    RunInfo run() throws Exception {
        String runId = "r-" + randomHex();
        PopulationFactory factory = new PopulationFactory(props);

        // DECISION: with Arena enabled, players are registered BEFORE the run clock starts. Registration can take a
        // minute (bcrypt), and cheat start times are offsets from runStartedAt, so starting the clock first would
        // let cheats begin before anyone sends an event. Usernames do not depend on the start time, so a first
        // population is only used to know whom to register, and the real one is built after registration.
        Map<String, String> arenaIds = registrar.isPresent()
                ? registrar.get().register(factory.create(clock.millis()))
                : Map.of();

        long runStartedAt = clock.millis();
        log.info("SIMULATION START runId={} players={} duration={} seed={} arena={}", runId, props.players(),
                props.duration(), props.seed(), registrar.isPresent());

        List<PlayerState> population = factory.create(runStartedAt);
        if (registrar.isPresent()) {
            // Arena's id replaces the made-up one, so profiles, ground truth, events and match summaries all use it.
            population = population.stream().map(p -> p.withPlayerId(arenaIds.get(p.username()))).toList();
        }
        List<PlayerState> players = population;
        // Cheaters whose cheat has started: the dashboard's "Cheaters active" panel.
        Gauge.builder("telemetry.sim.players.cheating",
                () -> players.stream().filter(p -> p.isCheatingAt(clock.millis())).count()).register(registry);

        List<CompletableFuture<?>> setup = new ArrayList<>(population.size() * 2);
        for (PlayerState player : population) {
            setup.add(publisher.publishProfile(PopulationFactory.profileOf(player, runStartedAt), runStartedAt));
            setup.add(publisher.publishGroundTruth(PopulationFactory.truthOf(runId, player), runStartedAt));
        }
        CompletableFuture.allOf(setup.toArray(CompletableFuture[]::new)).join();
        log.info("Published {} profiles and {} ground-truth records", population.size(), population.size());

        playMatches(runId, population);
        publisher.flush();

        long endedAt = clock.millis();
        List<ManifestEntry> entries = manifest.entries(runId, population);
        RunInfo info = new RunInfo(runId, runStartedAt, endedAt, population.size(), manifest.totalAcked(),
                manifest.totalFailed(), publisher.malformedSent());
        List<CompletableFuture<?>> finish = new ArrayList<>(entries.size() + 1);
        for (ManifestEntry entry : entries) {
            finish.add(publisher.publishManifestEntry(entry, endedAt));
        }
        finish.add(publisher.publishRunInfo(info, endedAt));
        CompletableFuture.allOf(finish.toArray(CompletableFuture[]::new)).join();

        log.info("SIMULATION COMPLETE runId={} acked={} failed={} malformed={}", runId, info.totalAcked(),
                info.totalFailed(), info.malformedSent());
        return info;
    }

    private void playMatches(String runId, List<PlayerState> population) throws InterruptedException {
        AtomicInteger matchCounter = new AtomicInteger();
        List<List<PlayerState>> shards = ShardWorker.partition(population, props);
        List<ScheduledExecutorService> schedulers = new ArrayList<>(shards.size());
        long startedAt = clock.millis();
        for (int k = 0; k < shards.size(); k++) {
            ShardWorker worker = new ShardWorker(k, shards.get(k), props, runId, clock, matchCounter, publisher);
            int shard = k;
            // One platform thread per shard. A shared pool could move a shard between threads between ticks.
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "shard-" + shard));
            synchronized (executors) {
                executors.add(scheduler);
            }
            schedulers.add(scheduler);
            scheduler.scheduleAtFixedRate(worker, 0, props.tickMs(), TimeUnit.MILLISECONDS);
        }

        long deadline = startedAt + props.duration().toMillis();
        long lastLogAt = startedAt;
        long lastAcked = 0;
        long remaining;
        while ((remaining = deadline - clock.millis()) > 0) {
            Thread.sleep(Math.min(remaining, RATE_LOG_INTERVAL_MS));
            long now = clock.millis();
            long acked = manifest.totalAcked();
            long elapsedMs = Math.max(1, now - lastLogAt);
            log.info("Sending {} events/s (acked so far {})", (acked - lastAcked) * 1000 / elapsedMs, acked);
            lastLogAt = now;
            lastAcked = acked;
        }

        for (ScheduledExecutorService scheduler : schedulers) {
            scheduler.shutdown();
        }
        for (ScheduledExecutorService scheduler : schedulers) {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        }
    }

    private void shutdownExecutors() {
        synchronized (executors) {
            executors.forEach(ExecutorService::shutdownNow);
        }
    }

    private static String randomHex() {
        byte[] bytes = new byte[RUN_ID_BYTES];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
