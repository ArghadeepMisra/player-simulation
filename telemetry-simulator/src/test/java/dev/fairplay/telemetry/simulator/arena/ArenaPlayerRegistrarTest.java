package dev.fairplay.telemetry.simulator.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.simulator.TestSimProps;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import dev.fairplay.telemetry.simulator.population.PopulationFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class ArenaPlayerRegistrarTest {

    @TempDir
    Path dir;

    private ArenaClient client;
    private List<PlayerState> players;
    private Path mapping;
    private ArenaPlayerRegistrar registrar;

    @BeforeEach
    void setUp() {
        client = mock(ArenaClient.class);
        players = new PopulationFactory(TestSimProps.of(42, 40)).create(1000);
        mapping = dir.resolve("nested").resolve("sim-players.json");
        registrar = new ArenaPlayerRegistrar(client, new ArenaProperties(true, "http://arena", "telemetry-sim", "pw",
                "sim-player-pass", 8, mapping.toString()));
    }

    @Test
    void registersEveryPlayerAndSavesTheMapping() throws Exception {
        when(client.register(any())).thenAnswer(call -> "arena-" + ((PlayerState) call.getArgument(0)).username());

        Map<String, String> ids = registrar.register(players);

        assertThat(ids).hasSize(40).containsEntry("sim_0001", "arena-sim_0001").containsEntry("sim_0040", "arena-sim_0040");
        JsonNode saved = JsonSerde.MAPPER.readTree(Files.readString(mapping));
        assertThat(saved.size()).isEqualTo(40);
        assertThat(saved.path("sim_0007").asString()).isEqualTo("arena-sim_0007");
    }

    @Test
    void aLaterRunSkipsPlayersAlreadyInTheMappingFile() throws Exception {
        Files.createDirectories(mapping.getParent());
        Files.writeString(mapping, "{\"sim_0001\":\"old-id-1\",\"sim_0002\":\"old-id-2\"}");
        when(client.register(any())).thenAnswer(call -> "new-" + ((PlayerState) call.getArgument(0)).username());

        Map<String, String> ids = registrar.register(players);

        assertThat(ids).hasSize(40).containsEntry("sim_0001", "old-id-1").containsEntry("sim_0003", "new-sim_0003");
        verify(client, times(38)).register(any());
    }

    @Test
    void nothingIsRegisteredWhenTheFileAlreadyCoversEveryone() throws Exception {
        Files.createDirectories(mapping.getParent());
        StringBuilder json = new StringBuilder("{");
        for (PlayerState p : players) {
            json.append("\"").append(p.username()).append("\":\"id-").append(p.index()).append("\",");
        }
        Files.writeString(mapping, json.substring(0, json.length() - 1) + "}");

        assertThat(registrar.register(players)).hasSize(40);
        verify(client, never()).register(any());
    }

    @Test
    void aFailedRegistrationStopsTheRunButKeepsWhatWorked() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(client.register(any())).thenAnswer(call -> {
            PlayerState p = call.getArgument(0);
            if (p.username().equals("sim_0005")) {
                throw new IllegalStateException("Arena is down");
            }
            calls.incrementAndGet();
            return "id-" + p.username();
        });

        assertThatThrownBy(() -> registrar.register(players)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 of 40 players could not be registered").hasMessageContaining("Arena is down");

        // The 39 that worked are saved, so a rerun only retries the failed one.
        JsonNode saved = JsonSerde.MAPPER.readTree(Files.readString(mapping));
        assertThat(saved.size()).isEqualTo(39);
        assertThat(saved.has("sim_0005")).isFalse();
        assertThat(calls.get()).isEqualTo(39);
    }

    @Test
    void anUnreadableMappingFileIsAnError() throws Exception {
        Files.createDirectories(mapping.getParent());
        Files.writeString(mapping, "this is not json");

        assertThatThrownBy(() -> registrar.register(players)).isInstanceOf(RuntimeException.class);
    }
}
