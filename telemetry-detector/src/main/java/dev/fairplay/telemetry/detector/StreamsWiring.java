package dev.fairplay.telemetry.detector;

import java.time.Clock;

import dev.fairplay.telemetry.detector.topology.TelemetryTopology;
import dev.fairplay.telemetry.detector.topology.TopologyMetrics;
import org.apache.kafka.streams.StreamsBuilder;
import org.springframework.stereotype.Component;

/**
 * The only place Spring touches the topology: it hands the auto-configured {@link StreamsBuilder} to
 * {@link TelemetryTopology}. The streams client starts after all beans exist, so the topology is complete by then.
 */
@Component
class StreamsWiring {

    StreamsWiring(StreamsBuilder builder, DetectionProperties props, Clock clock, TopologyMetrics metrics) {
        new TelemetryTopology(props, clock, metrics).addTo(builder);
    }
}
