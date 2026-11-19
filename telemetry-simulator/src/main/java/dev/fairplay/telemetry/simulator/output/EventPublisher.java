package dev.fairplay.telemetry.simulator.output;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.EventType;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.RunInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The only class that sends to Kafka. Events are serialized to {@code byte[]} here, so {@link #publishMalformed}
 * can send raw garbage through the same producer. Sends are never retried by hand: the idempotent producer
 * already retries safely, and a send that still fails is counted in the manifest.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, byte[]> template;
    private final ManifestTracker manifest;
    private final Clock clock;
    private final Map<EventType, Counter> sentByType = new EnumMap<>(EventType.class);
    private final Counter sendFailures;
    private final Counter malformedCounter;
    private final Counter matchesCompleted;
    private final LongAdder malformedSent = new LongAdder();

    public EventPublisher(KafkaTemplate<String, byte[]> template, ManifestTracker manifest, MeterRegistry registry,
                          Clock clock) {
        this.template = template;
        this.manifest = manifest;
        this.clock = clock;
        // Counters are looked up once here: registering one per event would cost real time at 25,000 events/s.
        for (EventType type : EventType.values()) {
            sentByType.put(type, Counter.builder("telemetry.sim.events.sent").tag("type", type.name()).register(registry));
        }
        this.sendFailures = Counter.builder("telemetry.sim.send.failures").register(registry);
        this.malformedCounter = Counter.builder("telemetry.sim.malformed.sent").register(registry);
        this.matchesCompleted = Counter.builder("telemetry.sim.matches.completed").register(registry);
    }

    /** Sends one event keyed by its player id, with the event time as the Kafka record timestamp. */
    public void publish(MatchEvent event) {
        byte[] bytes = JsonSerde.MAPPER.writeValueAsBytes(event);
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(Topics.MATCH_EVENTS, null, event.ts(), event.playerId(), bytes);
        CompletableFuture<?> future;
        try {
            future = template.send(record);
        } catch (RuntimeException e) {
            onFailure(event, e);
            return;
        }
        future.whenComplete((result, error) -> {
            if (error == null) {
                manifest.acked(event.playerId(), event.seq());
                sentByType.get(event.type()).increment();
            } else {
                onFailure(event, error);
            }
        });
    }

    /** Sends a deliberately bad record keyed like a real one. It has no seq and is not in the manifest. */
    public void publishMalformed(String playerId, byte[] bytes) {
        template.send(new ProducerRecord<>(Topics.MATCH_EVENTS, null, clock.millis(), playerId, bytes));
        malformedSent.increment();
        malformedCounter.increment();
    }

    public long malformedSent() {
        return malformedSent.sum();
    }

    public CompletableFuture<?> publishProfile(PlayerProfile profile, long ts) {
        return sendJson(Topics.PLAYER_PROFILES, profile.playerId(), profile, ts);
    }

    public CompletableFuture<?> publishGroundTruth(GroundTruth truth, long ts) {
        return sendJson(Topics.CHEATER_GROUND_TRUTH, truth.playerId(), truth, ts);
    }

    /** Keyed by the match's external id, as the Arena bridge expects. */
    public void publishSummary(MatchSummary summary) {
        sendJson(Topics.MATCH_SUMMARIES, summary.externalId(), summary, clock.millis());
        matchesCompleted.increment();
    }

    public CompletableFuture<?> publishManifestEntry(ManifestEntry entry, long ts) {
        return sendJson(Topics.SIMULATION_MANIFEST, entry.playerId(), entry, ts);
    }

    public CompletableFuture<?> publishRunInfo(RunInfo info, long ts) {
        return sendJson(Topics.SIMULATION_MANIFEST, Topics.RUN_KEY, info, ts);
    }

    /** Blocks until every earlier send has completed and its callback has run. */
    public void flush() {
        template.flush();
    }

    private CompletableFuture<?> sendJson(String topic, String key, Object value, long ts) {
        byte[] bytes = JsonSerde.MAPPER.writeValueAsBytes(value);
        return template.send(new ProducerRecord<>(topic, null, ts, key, bytes));
    }

    private void onFailure(MatchEvent event, Throwable error) {
        manifest.failed(event.playerId());
        sendFailures.increment();
        log.warn("Send failed for player {} seq {}: {}", event.playerId(), event.seq(), error.toString());
    }
}
