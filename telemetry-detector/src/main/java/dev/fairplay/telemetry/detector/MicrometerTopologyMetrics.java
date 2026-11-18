package dev.fairplay.telemetry.detector;

import java.util.EnumMap;
import java.util.Map;

import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.detector.topology.TopologyMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The topology's counters. A retried transaction can count an event twice, so these are for the
 * dashboard only. Exact numbers come from the verifier.
 */
@Component
class MicrometerTopologyMetrics implements TopologyMetrics {

    private final Counter parsed;
    private final Counter deadLettered;
    private final Map<Rule, Counter> flags = new EnumMap<>(Rule.class);

    MicrometerTopologyMetrics(MeterRegistry registry) {
        this.parsed = registry.counter("telemetry.detect.events.parsed");
        this.deadLettered = registry.counter("telemetry.detect.events.dead_lettered");
        for (Rule rule : Rule.values()) {
            flags.put(rule, registry.counter("telemetry.detect.flags", "rule", rule.name()));
        }
    }

    @Override
    public void parsed() {
        parsed.increment();
    }

    @Override
    public void deadLettered() {
        deadLettered.increment();
    }

    @Override
    public void flagged(Rule rule) {
        flags.get(rule).increment();
    }
}
