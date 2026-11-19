package dev.fairplay.telemetry.detector.bridge;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.MatchSummary;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Forwards each finished match from {@code match-summaries} to Arena. Delivery is at-least-once, and Arena's
 * {@code externalId} idempotency (a replay returns 200 and changes nothing) makes the end result exactly-once.
 *
 * <p>The value is read as a {@code String} and parsed here, so one {@code KafkaTemplate<String, String>} can publish
 * dead letters for both bad JSON and Arena rejections. The container exists always but only starts when
 * {@code telemetry.arena.enabled=true}.
 */
@Component
class ArenaBridgeListener {

    private final ArenaMatchClient client;

    ArenaBridgeListener(ArenaMatchClient client) {
        this.client = client;
    }

    // idIsGroup = false: by default the listener id would also become the consumer group and override the
    // group-id in the YAML (telemetry-arena-bridge).
    @KafkaListener(id = "arena-bridge", idIsGroup = false, topics = Topics.MATCH_SUMMARIES,
            autoStartup = "${telemetry.arena.enabled:false}")
    void onSummary(ConsumerRecord<String, String> record) {
        MatchSummary summary;
        try {
            summary = JsonSerde.MAPPER.readValue(record.value(), MatchSummary.class);
        } catch (RuntimeException e) {
            throw new NonRetryableArenaException("MALFORMED_SUMMARY", String.valueOf(e.getMessage()));
        }
        client.recordMatch(summary);
    }
}
