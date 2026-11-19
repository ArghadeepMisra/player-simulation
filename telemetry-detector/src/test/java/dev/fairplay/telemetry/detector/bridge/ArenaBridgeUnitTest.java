package dev.fairplay.telemetry.detector.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.MatchSummary;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** The listener's parsing and the properties' validation, without Kafka. The error handler is covered by ArenaBridgeIT. */
class ArenaBridgeUnitTest {

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(Topics.MATCH_SUMMARIES, 0, 0L, "sim-r-1-000001", value);
    }

    @Test
    void theListenerParsesTheSummaryAndHandsItToTheClient() {
        ArenaMatchClient client = mock(ArenaMatchClient.class);
        MatchSummary summary = new MatchSummary("sim-r-1-000001", "FFA", "Harbor", "2026-11-17T10:00:00Z",
                "2026-11-17T10:02:00Z", java.util.List.of());

        new ArenaBridgeListener(client).onSummary(record(JsonSerde.MAPPER.writeValueAsString(summary)));

        verify(client).recordMatch(summary);
    }

    @Test
    void garbageIsNonRetryableAndNeverReachesArena() {
        ArenaMatchClient client = mock(ArenaMatchClient.class);

        assertThatThrownBy(() -> new ArenaBridgeListener(client).onSummary(record("{\"broken\":")))
                .isInstanceOf(NonRetryableArenaException.class)
                .extracting(e -> ((NonRetryableArenaException) e).code()).isEqualTo("MALFORMED_SUMMARY");
        verify(client, never()).recordMatch(any());
    }

    @Test
    void aNullValueIsAlsoMalformed() {
        ArenaMatchClient client = mock(ArenaMatchClient.class);

        assertThatThrownBy(() -> new ArenaBridgeListener(client).onSummary(record(null)))
                .isInstanceOf(NonRetryableArenaException.class);
    }

    @Test
    void propertiesRejectBrokenSettings() {
        assertThatThrownBy(() -> new ArenaProperties(true, "http://x", "u", "", 3, Duration.ofSeconds(1), 2.0))
                .hasMessageContaining("TELEMETRY_SERVICE_PASSWORD");
        assertThat(new ArenaProperties(false, "http://x", "u", "", 3, Duration.ofSeconds(1), 2.0).enabled()).isFalse();
        assertThatThrownBy(() -> new ArenaProperties(false, " ", "u", "p", 3, Duration.ofSeconds(1), 2.0))
                .hasMessageContaining("base-url");
        assertThatThrownBy(() -> new ArenaProperties(false, "http://x", "u", "p", -1, Duration.ofSeconds(1), 2.0))
                .hasMessageContaining("max-retries");
        assertThatThrownBy(() -> new ArenaProperties(false, "http://x", "u", "p", 3, Duration.ZERO, 2.0))
                .hasMessageContaining("initial-backoff");
        assertThatThrownBy(() -> new ArenaProperties(false, "http://x", "u", "p", 3, Duration.ofSeconds(1), 0.5))
                .hasMessageContaining("backoff-multiplier");
    }
}
