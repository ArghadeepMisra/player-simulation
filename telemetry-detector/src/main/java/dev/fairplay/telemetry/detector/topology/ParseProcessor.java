package dev.fairplay.telemetry.detector.topology;

import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

import dev.fairplay.telemetry.common.model.DeadLetter;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;

/**
 * Parses the raw bytes of {@code match-events} and keeps the key. Bad records are never dropped silently: null bytes,
 * unparseable JSON, an invalid event or a key that differs from the event's player id all become
 * {@link ParseResult.Failed}, which the topology routes to {@code match-events.DLT}.
 */
public final class ParseProcessor implements Processor<String, byte[], String, ParseResult> {

    private final Clock clock;
    private final TopologyMetrics metrics;
    private ProcessorContext<String, ParseResult> context;

    public ParseProcessor(Clock clock, TopologyMetrics metrics) {
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public void init(ProcessorContext<String, ParseResult> context) {
        this.context = context;
    }

    @Override
    public void process(Record<String, byte[]> record) {
        EventParser.Outcome outcome = EventParser.parse(record.key(), record.value());
        if (outcome.problem() == null) {
            metrics.parsed();
            context.forward(record.withValue(new ParseResult.Parsed(outcome.event())));
        } else {
            metrics.deadLettered();
            context.forward(record.withValue(new ParseResult.Failed(deadLetter(record, outcome.problem()))));
        }
    }

    private DeadLetter deadLetter(Record<String, byte[]> record, String problem) {
        Optional<RecordMetadata> metadata = context.recordMetadata();
        String payload = record.value() == null ? null : Base64.getEncoder().encodeToString(record.value());
        return new DeadLetter(
                metadata.map(RecordMetadata::topic).orElse("unknown"),
                metadata.map(RecordMetadata::partition).orElse(-1),
                metadata.map(RecordMetadata::offset).orElse(-1L),
                record.key(),
                payload,
                problem,
                clock.millis());
    }
}
