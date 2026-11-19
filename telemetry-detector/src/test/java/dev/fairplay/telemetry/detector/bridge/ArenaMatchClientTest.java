package dev.fairplay.telemetry.detector.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.model.ParticipantSummary;
import dev.fairplay.telemetry.common.support.StubArenaServer;
import dev.fairplay.telemetry.common.support.StubArenaServer.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArenaMatchClientTest {

    private static final String MATCHES = "/api/v1/matches";
    private static final String LOGIN = "/api/v1/auth/login";

    private static final MatchSummary SUMMARY = new MatchSummary("sim-r-1-000001", "FFA", "Harbor",
            "2026-11-17T10:00:00Z", "2026-11-17T10:02:00Z",
            List.of(new ParticipantSummary("p1", 1, 3, 1, 1, 10, 5, 200),
                    new ParticipantSummary("p2", 2, 1, 3, 0, 8, 2, 240)));

    private StubArenaServer arena;
    private SimpleMeterRegistry registry;
    private ArenaMatchClient client;

    @BeforeEach
    void setUp() {
        arena = new StubArenaServer();
        registry = new SimpleMeterRegistry();
        client = new ArenaMatchClient(new ArenaProperties(true, arena.baseUrl(), "telemetry-sim", "service-pw", 3,
                Duration.ofMillis(100), 2.0), registry);
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    private double posted(String outcome) {
        return registry.counter("telemetry.bridge.matches.posted", "outcome", outcome).count();
    }

    private void matchesAnswer(Response... responses) {
        arena.enqueue("POST", MATCHES, responses);
    }

    @Test
    void a201MeansCreated() {
        matchesAnswer(Response.json(201, "{}"));

        client.recordMatch(SUMMARY);

        assertThat(posted("created")).isEqualTo(1);
        assertThat(posted("replayed")).isZero();
        StubArenaServer.Request request = arena.requests("POST", MATCHES).get(0);
        assertThat(request.authorization()).isEqualTo("Bearer token-1");
        assertThat(request.body()).contains("\"externalId\":\"sim-r-1-000001\"").contains("\"participants\"");
    }

    @Test
    void a200MeansAReplayOfAMatchArenaAlreadyHas() {
        matchesAnswer(Response.json(200, "{}"));

        client.recordMatch(SUMMARY);

        assertThat(posted("replayed")).isEqualTo(1);
        assertThat(posted("created")).isZero();
    }

    @Test
    void theLoginTokenIsCachedBetweenMatches() {
        matchesAnswer(Response.json(201, "{}"), Response.json(201, "{}"));

        client.recordMatch(SUMMARY);
        client.recordMatch(SUMMARY);

        assertThat(arena.requests("POST", LOGIN)).hasSize(1);
        assertThat(posted("created")).isEqualTo(2);
    }

    @Test
    void a401LogsInAgainAndRetriesOnceInsideTheSameCall() {
        matchesAnswer(Response.problem(401, "UNAUTHORIZED", "expired"), Response.json(201, "{}"));

        client.recordMatch(SUMMARY);

        assertThat(arena.requests("POST", LOGIN)).hasSize(2);
        assertThat(arena.requests("POST", MATCHES)).extracting(StubArenaServer.Request::authorization)
                .containsExactly("Bearer token-1", "Bearer token-2");
        assertThat(posted("created")).isEqualTo(1);
    }

    @Test
    void aSecond401IsNonRetryable() {
        matchesAnswer(Response.problem(401, "UNAUTHORIZED", "no"), Response.problem(401, "UNAUTHORIZED", "no"));

        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(NonRetryableArenaException.class)
                .extracting(e -> ((NonRetryableArenaException) e).code()).isEqualTo("UNAUTHORIZED");
        assertThat(arena.requests("POST", MATCHES)).hasSize(2);
    }

    @Test
    void tooManyRequestsAndServerErrorsAreRetryable() {
        matchesAnswer(Response.json(429, ""), Response.json(500, ""), Response.json(503, "{}"));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(RetryableArenaException.class);
        }
        assertThat(posted("created")).isZero();
    }

    @Test
    void clientErrorsAreNonRetryableAndCarryArenasCodeAndDetail() {
        matchesAnswer(Response.problem(422, "PLAYER_NOT_ELIGIBLE", "Player sim_0042 is banned"),
                Response.problem(409, "MATCH_CONFLICT", "externalId reused"),
                Response.problem(400, "VALIDATION_FAILED", "bad body"),
                Response.problem(404, "PLAYER_NOT_FOUND", "unknown player"));

        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(NonRetryableArenaException.class)
                .hasMessage("PLAYER_NOT_ELIGIBLE: Player sim_0042 is banned");
        for (String code : List.of("MATCH_CONFLICT", "VALIDATION_FAILED", "PLAYER_NOT_FOUND")) {
            assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(NonRetryableArenaException.class)
                    .extracting(e -> ((NonRetryableArenaException) e).code()).isEqualTo(code);
        }
    }

    @Test
    void aClientErrorWithoutProblemJsonReportsTheRawStatusAndBody() {
        matchesAnswer(Response.json(403, "forbidden"), Response.json(418, ""));

        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(NonRetryableArenaException.class)
                .hasMessage("HTTP_403: forbidden");
        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(NonRetryableArenaException.class)
                .hasMessage("HTTP_418: ");
    }

    @Test
    void aLongRawBodyIsCut() {
        matchesAnswer(Response.json(400, "x".repeat(1000)));

        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(NonRetryableArenaException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(330));
    }

    @Test
    void aRefusedConnectionIsRetryable() {
        arena.close();

        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(RetryableArenaException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void aRejectedLoginIsNonRetryableButAServerErrorOnLoginIsRetryable() {
        arena.enqueue("POST", LOGIN, Response.problem(401, "BAD_CREDENTIALS", "wrong password"));
        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(NonRetryableArenaException.class)
                .extracting(e -> ((NonRetryableArenaException) e).code()).isEqualTo("LOGIN_FAILED");

        arena.enqueue("POST", LOGIN, Response.json(503, ""));
        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(RetryableArenaException.class);

        arena.enqueue("POST", LOGIN, Response.json(429, ""));
        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(RetryableArenaException.class);
    }

    @Test
    void aLoginWithoutATokenIsNonRetryable() {
        arena.enqueue("POST", LOGIN, Response.json(200, "{}"));

        assertThatThrownBy(() -> client.recordMatch(SUMMARY)).isInstanceOf(NonRetryableArenaException.class)
                .hasMessageContaining("accessToken");
    }
}
