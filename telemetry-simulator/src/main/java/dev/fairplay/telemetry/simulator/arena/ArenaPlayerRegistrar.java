package dev.fairplay.telemetry.simulator.arena;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * Makes sure every simulated player exists in Arena and returns Arena's id for each username. Registration runs on
 * virtual threads limited by a semaphore, because Arena hashes passwords with bcrypt and is slow per request.
 *
 * <p>The mapping is saved to {@code mapping-file} (a JSON object {@code {"sim_0001": "<arena uuid>"}}). A later run
 * loads it and skips players already mapped. If any player cannot be mapped the run must not start, so this throws.
 */
@Component
@ConditionalOnProperty(name = "telemetry.arena.enabled", havingValue = "true")
public class ArenaPlayerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ArenaPlayerRegistrar.class);
    private static final TypeReference<TreeMap<String, String>> MAPPING = new TypeReference<>() {
    };

    private final ArenaClient client;
    private final Path mappingFile;
    private final int concurrency;

    public ArenaPlayerRegistrar(ArenaClient client, ArenaProperties props) {
        this.client = client;
        this.mappingFile = Path.of(props.mappingFile());
        this.concurrency = props.registerConcurrency();
    }

    /** Username to Arena id for every player. Throws when any player could not be registered. */
    public Map<String, String> register(List<PlayerState> players) throws InterruptedException {
        Map<String, String> mapping = new ConcurrentHashMap<>(load());
        List<PlayerState> todo = players.stream().filter(p -> !mapping.containsKey(p.username())).toList();
        log.info("Arena registration: {} players already mapped, {} to register", players.size() - todo.size(), todo.size());

        Semaphore permits = new Semaphore(concurrency);
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(todo.size());
            for (PlayerState player : todo) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        mapping.put(player.username(), client.register(player));
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        }
        // Save what worked even when something failed, so a rerun only retries the rest.
        save(mapping);
        if (!failures.isEmpty()) {
            throw new IllegalStateException(failures.size() + " of " + todo.size()
                    + " players could not be registered with Arena; first error: " + failures.get(0), failures.get(0));
        }
        return Map.copyOf(mapping);
    }

    private Map<String, String> load() {
        if (!Files.exists(mappingFile)) {
            return Map.of();
        }
        try {
            return JsonSerde.MAPPER.readValue(Files.readString(mappingFile, StandardCharsets.UTF_8), MAPPING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + mappingFile, e);
        }
    }

    private void save(Map<String, String> mapping) {
        try {
            Path parent = mappingFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(mappingFile, JsonSerde.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new TreeMap<>(mapping)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + mappingFile, e);
        }
    }
}
