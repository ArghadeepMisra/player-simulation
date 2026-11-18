package dev.fairplay.telemetry.detector.topology;

import dev.fairplay.telemetry.common.model.Rule;

/**
 * What the topology reports. A plain interface so the topology stays free of Spring and Micrometer. These counters
 * can count an event twice when a transaction is retried after a crash, so they are for dashboards only.
 */
public interface TopologyMetrics {

    TopologyMetrics NOOP = new TopologyMetrics() {
        @Override
        public void parsed() {
        }

        @Override
        public void deadLettered() {
        }

        @Override
        public void flagged(Rule rule) {
        }
    };

    void parsed();

    void deadLettered();

    void flagged(Rule rule);
}
