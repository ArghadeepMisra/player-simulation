package dev.fairplay.telemetry.detector.bridge;

import java.time.Duration;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.MatchSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Posts one finished match to Arena's {@code POST /api/v1/matches}. It logs in as the service account lazily and
 * caches the access token.
 *
 * <pre>
 * 201                                  created, counted as outcome=created
 * 200                                  already stored (a replay), counted as outcome=replayed
 * 401                                  log in again and retry once; a second 401 is non-retryable
 * 429, 5xx, no connection, timeout     RetryableArenaException
 * 400, 404, 409, 422 (and other 4xx)   NonRetryableArenaException carrying Arena's code and detail
 * </pre>
 */
@Component
public class ArenaMatchClient {

    private static final Logger log = LoggerFactory.getLogger(ArenaMatchClient.class);

    private record Reply(int status, String body) {
    }

    private final ArenaProperties props;
    private final RestClient http;
    private final Counter created;
    private final Counter replayed;
    private volatile String token;

    public ArenaMatchClient(ArenaProperties props, MeterRegistry registry) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        this.created = Counter.builder("telemetry.bridge.matches.posted").tag("outcome", "created").register(registry);
        this.replayed = Counter.builder("telemetry.bridge.matches.posted").tag("outcome", "replayed").register(registry);
    }

    public void recordMatch(MatchSummary summary) {
        String json = JsonSerde.MAPPER.writeValueAsString(summary);
        Reply reply = post(json);
        if (reply.status() == 401) {
            // The token expired or was never valid: log in again and retry once inside this same call.
            token = null;
            reply = post(json);
        }
        switch (reply.status()) {
            case 201 -> {
                created.increment();
                log.info("Arena stored match {}", summary.externalId());
            }
            case 200 -> {
                replayed.increment();
                log.info("Arena already had match {} (replay)", summary.externalId());
            }
            case 401 -> throw new NonRetryableArenaException("UNAUTHORIZED", "Arena rejected the service token twice");
            case 429 -> throw new RetryableArenaException("Arena answered 429 (rate limited)");
            default -> {
                if (reply.status() >= 500) {
                    throw new RetryableArenaException("Arena answered " + reply.status());
                }
                throw problem(reply);
            }
        }
    }

    private Reply post(String json) {
        try {
            String bearer = token != null ? token : login();
            return http.post().uri("/api/v1/matches").contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).body(json)
                    .exchange((request, response) -> new Reply(response.getStatusCode().value(), body(response.bodyTo(String.class))));
        } catch (ResourceAccessException e) {
            throw new RetryableArenaException("Arena is unreachable: " + e.getMessage(), e);
        }
    }

    private synchronized String login() {
        Reply reply = http.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(JsonSerde.MAPPER.writeValueAsString(
                        java.util.Map.of("username", props.serviceUsername(), "password", props.servicePassword())))
                .exchange((request, response) -> new Reply(response.getStatusCode().value(), body(response.bodyTo(String.class))));
        if (reply.status() == 200) {
            String accessToken = JsonSerde.MAPPER.readTree(reply.body()).path("accessToken").asString();
            if (accessToken == null || accessToken.isBlank()) {
                throw new NonRetryableArenaException("LOGIN_FAILED", "Arena login returned no accessToken");
            }
            token = accessToken;
            return accessToken;
        }
        if (reply.status() == 429 || reply.status() >= 500) {
            throw new RetryableArenaException("Arena login answered " + reply.status());
        }
        throw new NonRetryableArenaException("LOGIN_FAILED", "Arena login answered " + reply.status());
    }

    /** Arena's problem JSON has a {@code code} and a {@code detail}. Anything else is reported as the raw status. */
    private static NonRetryableArenaException problem(Reply reply) {
        String code = "HTTP_" + reply.status();
        String detail = reply.body().length() > 300 ? reply.body().substring(0, 300) : reply.body();
        try {
            JsonNode node = JsonSerde.MAPPER.readTree(reply.body());
            if (node.hasNonNull("code")) {
                code = node.path("code").asString();
            }
            if (node.hasNonNull("detail")) {
                detail = node.path("detail").asString();
            }
        } catch (RuntimeException e) {
            // Not JSON: keep the raw body as the detail.
        }
        return new NonRetryableArenaException(code, detail);
    }

    private static String body(String body) {
        return body == null ? "" : body;
    }
}
