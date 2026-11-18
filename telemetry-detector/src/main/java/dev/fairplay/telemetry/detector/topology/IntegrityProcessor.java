package dev.fairplay.telemetry.detector.topology;

import java.time.Clock;
import java.time.Duration;

import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.MatchEvent;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Proves exactly-once. Each player's events carry a gapless {@code seq}, so comparing it with the last seen value
 * counts what arrived in order, what arrived twice and what never arrived. The counts live in a state store and
 * are re-emitted for <b>every</b> player on a wall-clock interval, so the verifier only ever needs the latest value
 * per key and a task restored after a failover needs no memory of what changed.
 */
public final class IntegrityProcessor implements Processor<String, MatchEvent, String, IntegrityStats> {

    private final Duration emitInterval;
    private final Clock clock;
    private ProcessorContext<String, IntegrityStats> context;
    private KeyValueStore<String, IntegrityStats> store;

    public IntegrityProcessor(Duration emitInterval, Clock clock) {
        this.emitInterval = emitInterval;
        this.clock = clock;
    }

    @Override
    public void init(ProcessorContext<String, IntegrityStats> context) {
        this.context = context;
        this.store = context.getStateStore(TelemetryTopology.SEQ_STORE);
        context.schedule(emitInterval, PunctuationType.WALL_CLOCK_TIME, timestamp -> emitAll());
    }

    @Override
    public void process(Record<String, MatchEvent> record) {
        MatchEvent event = record.value();
        IntegrityStats current = store.get(event.playerId());
        if (current == null) {
            current = IntegrityStats.empty(event.playerId());
        }
        // Nothing is forwarded here: stats go out from the punctuator.
        store.put(event.playerId(), apply(current, event.seq()));
    }

    /** The three cases: next in order, seen before, or something missing. */
    static IntegrityStats apply(IntegrityStats s, long seq) {
        if (seq == s.lastSeq() + 1) {
            return new IntegrityStats(s.playerId(), s.count() + 1, seq, s.duplicates(), s.gaps(), s.updatedAt());
        }
        if (seq <= s.lastSeq()) {
            return new IntegrityStats(s.playerId(), s.count(), s.lastSeq(), s.duplicates() + 1, s.gaps(),
                    s.updatedAt());
        }
        return new IntegrityStats(s.playerId(), s.count() + 1, seq, s.duplicates(),
                s.gaps() + (seq - s.lastSeq() - 1), s.updatedAt());
    }

    private void emitAll() {
        long now = clock.millis();
        try (KeyValueIterator<String, IntegrityStats> all = store.all()) {
            while (all.hasNext()) {
                KeyValue<String, IntegrityStats> entry = all.next();
                IntegrityStats s = entry.value;
                IntegrityStats stamped = new IntegrityStats(s.playerId(), s.count(), s.lastSeq(), s.duplicates(),
                        s.gaps(), now);
                context.forward(new Record<>(entry.key, stamped, now));
            }
        }
    }
}
