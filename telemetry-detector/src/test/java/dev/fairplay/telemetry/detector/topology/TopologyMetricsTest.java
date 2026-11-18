package dev.fairplay.telemetry.detector.topology;

import static org.assertj.core.api.Assertions.assertThatCode;

import dev.fairplay.telemetry.common.model.Rule;
import org.junit.jupiter.api.Test;

class TopologyMetricsTest {

    @Test
    void theNoOpImplementationAcceptsEveryCall() {
        assertThatCode(() -> {
            TopologyMetrics.NOOP.parsed();
            TopologyMetrics.NOOP.deadLettered();
            TopologyMetrics.NOOP.flagged(Rule.AIMBOT);
            TopologyMetrics.NOOP.flagged(Rule.SPEEDHACK);
        }).doesNotThrowAnyException();
    }
}
