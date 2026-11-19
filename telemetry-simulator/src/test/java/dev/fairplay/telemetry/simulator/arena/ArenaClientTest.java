package dev.fairplay.telemetry.simulator.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.support.StubArenaServer;
import dev.fairplay.telemetry.common.support.StubArenaServer.Response;
import dev.fairplay.telemetry.simulator.TestSimProps;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import dev.fairplay.telemetry.simulator.population.PopulationFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ArenaClientTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PLAYERS = "/api/v1/players";

    private StubArenaServer arena;
    private ArenaClient client;
    private PlayerState player;

    @BeforeEach
    void setUp() {
        arena = new StubArenaServer();
        client = new ArenaClient(new ArenaProperties(true, arena.baseUrl(), "telemetry-sim", "service-pw",
                "sim-player-pass", 4, "unused.json"));
        player = new PopulationFactory(TestSimProps.of(42, 40)).create(1000).get(0);
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    @Test
    void registersAPlayerAndReturnsArenasId() {
        arena.on("POST", REGISTER, Response.json(201, "{\"id\":\"11111111-2222-3333-4444-555555555555\"}"));

        String id = client.register(player);

        assertThat(id).isEqualTo("11111111-2222-3333-4444-555555555555");
        JsonNode body = JsonSerde.MAPPER.readTree(arena.requests("POST", REGISTER).get(0).body());
        assertThat(body.path("username").asString()).isEqualTo("sim_0001");
        assertThat(body.path("email").asString()).isEqualTo("sim_0001@sim.fairplay.local");
        assertThat(body.path("password").asString()).isEqualTo("sim-player-pass");
        assertThat(body.path("country").asString()).isEqualTo(player.country());
        // Registering is public: no login is needed.
        assertThat(arena.requests("POST", LOGIN)).isEmpty();
    }

    @Test
    void anExistingUsernameIsLookedUpWithTheServiceToken() {
        arena.on("POST", REGISTER, Response.problem(409, "USERNAME_TAKEN", "Username is already taken"));
        arena.on("GET", PLAYERS, Response.json(200, """
                {"content":[{"id":"aaaa","username":"sim_00011"},{"id":"bbbb","username":"SIM_0001"}]}"""));

        String id = client.register(player);

        // The exact match wins, ignoring case, not the first hit of the search.
        assertThat(id).isEqualTo("bbbb");
        StubArenaServer.Request lookup = arena.requests("GET", PLAYERS).get(0);
        assertThat(lookup.query()).contains("q=sim_0001").contains("size=5");
        assertThat(lookup.authorization()).isEqualTo("Bearer token-1");
        assertThat(JsonSerde.MAPPER.readTree(arena.requests("POST", LOGIN).get(0).body()).path("username").asString())
                .isEqualTo("telemetry-sim");
    }

    @Test
    void theServiceTokenIsCachedAcrossLookups() {
        arena.on("POST", REGISTER, Response.problem(409, "USERNAME_TAKEN", "taken"));
        arena.on("GET", PLAYERS, request -> Response.json(200,
                "{\"content\":[{\"id\":\"id-1\",\"username\":\"sim_0001\"}]}"));

        client.register(player);
        client.register(player);

        assertThat(arena.requests("POST", LOGIN)).hasSize(1);
    }

    @Test
    void aRejectedTokenTriggersOneRelogin() {
        arena.on("POST", REGISTER, Response.problem(409, "USERNAME_TAKEN", "taken"));
        arena.enqueue("GET", PLAYERS, Response.problem(401, "UNAUTHORIZED", "expired"));
        arena.on("GET", PLAYERS, Response.json(200, "{\"content\":[{\"id\":\"id-1\",\"username\":\"sim_0001\"}]}"));

        assertThat(client.register(player)).isEqualTo("id-1");

        assertThat(arena.requests("POST", LOGIN)).hasSize(2);
        assertThat(arena.requests("GET", PLAYERS)).extracting(StubArenaServer.Request::authorization)
                .containsExactly("Bearer token-1", "Bearer token-2");
    }

    @Test
    void aTakenUsernameThatCannotBeFoundIsAnError() {
        arena.on("POST", REGISTER, Response.problem(409, "USERNAME_TAKEN", "taken"));
        arena.on("GET", PLAYERS, Response.json(200, "{\"content\":[]}"));

        assertThatThrownBy(() -> client.register(player)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sim_0001");
    }

    @Test
    void aLoginWithoutATokenIsAnError() {
        arena.on("POST", REGISTER, Response.problem(409, "USERNAME_TAKEN", "taken"));
        arena.on("POST", LOGIN, Response.json(200, "{}"));

        assertThatThrownBy(() -> client.register(player)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accessToken");
    }
}
