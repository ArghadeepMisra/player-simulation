package dev.fairplay.telemetry.simulator.arena;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Registers simulated players with Arena. Logs in as the service account lazily, caches the access token, and logs
 * in again once on any 401. A 409 {@code USERNAME_TAKEN} means the player already exists, so its id is looked up.
 */
@Component
@ConditionalOnProperty(name = "telemetry.arena.enabled", havingValue = "true")
public class ArenaClient {

    private static final Logger log = LoggerFactory.getLogger(ArenaClient.class);

    private final ArenaProperties props;
    private final RestClient http;
    private volatile String token;

    public ArenaClient(ArenaProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    /** A service token, from the cache unless there is none yet. */
    String serviceToken() {
        String cached = token;
        return cached != null ? cached : login();
    }

    private synchronized String login() {
        String body = http.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(json(Map.of("username", props.serviceUsername(), "password", props.servicePassword())))
                .retrieve().body(String.class);
        token = JsonSerde.MAPPER.readTree(body).path("accessToken").asString();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Arena login returned no accessToken");
        }
        return token;
    }

    /** Registers the player and returns Arena's id for it. Safe to repeat: an existing username is looked up. */
    public String register(PlayerState player) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("username", player.username());
        request.put("email", player.username() + "@sim.fairplay.local");
        request.put("password", props.playerPassword());
        request.put("country", player.country());
        try {
            String body = http.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .body(json(request)).retrieve().body(String.class);
            return JsonSerde.MAPPER.readTree(body).path("id").asString();
        } catch (HttpClientErrorException.Conflict e) {
            log.debug("{} already exists in Arena, looking it up", player.username());
            return lookup(player.username());
        }
    }

    /** {@code GET /api/v1/players?q=<username>&size=5}, taking the exact (case-insensitive) username match. */
    private String lookup(String username) {
        String body = authorized(() -> http.get()
                .uri(uri -> uri.path("/api/v1/players").queryParam("q", username).queryParam("size", 5).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                .retrieve().body(String.class));
        for (JsonNode player : JsonSerde.MAPPER.readTree(body).path("content")) {
            if (username.equalsIgnoreCase(player.path("username").asString())) {
                return player.path("id").asString();
            }
        }
        throw new IllegalStateException("Arena says " + username + " is taken but the lookup found no such player");
    }

    /** Runs {@code call}; on a 401 it logs in again and tries once more. */
    private <T> T authorized(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            token = null;
            return call.get();
        }
    }

    private static String json(Object value) {
        return JsonSerde.MAPPER.writeValueAsString(value);
    }
}
