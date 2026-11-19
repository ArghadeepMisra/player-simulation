package dev.fairplay.telemetry.detector.topology;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes repeats. A hopping window keeps matching for as long as a cheater cheats, so the same player would be
 * flagged every second. The store remembers when each {@code playerId|rule} was last published, and a flag inside
 * the cooldown is dropped. "Now" is the injected {@link Clock}, not event time.
 */
public final class CooldownProcessor implements Processor<String, FlagCandidate, String, SuspiciousPlayer> {

    private static final Logger log = LoggerFactory.getLogger(CooldownProcessor.class);

    private final Duration cooldown;
    private final String detectorVersion;
    private final Clock clock;
    private final TopologyMetrics metrics;
    private ProcessorContext<String, SuspiciousPlayer> context;
    private KeyValueStore<String, Long> store;

    public CooldownProcessor(Duration cooldown, String detectorVersion, Clock clock, TopologyMetrics metrics) {
        this.cooldown = cooldown;
        this.detectorVersion = detectorVersion;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public void init(ProcessorContext<String, SuspiciousPlayer> context) {
        this.context = context;
        this.store = context.getStateStore(TelemetryTopology.COOLDOWN_STORE);
    }

    @Override
    public void process(Record<String, FlagCandidate> record) {
        FlagCandidate candidate = record.value();
        String storeKey = candidate.playerId() + "|" + candidate.rule();
        long now = clock.millis();
        Long lastEmitted = store.get(storeKey);
        if (lastEmitted != null && now - lastEmitted < cooldown.toMillis()) {
            return;
        }
        store.put(storeKey, now);
        metrics.flagged(candidate.rule());
        log.info("Flag player={} rule={} score={}", candidate.playerId(), candidate.rule(), candidate.score());
        SuspiciousPlayer flag = new SuspiciousPlayer(
                UUID.randomUUID().toString(),
                candidate.playerId(),
                candidate.username(),
                candidate.skillTier(),
                candidate.rule(),
                candidate.score(),
                candidate.windowStart(),
                candidate.windowEnd(),
                now,
                candidate.evidence(),
                detectorVersion);
        context.forward(record.withValue(flag));
    }
}
