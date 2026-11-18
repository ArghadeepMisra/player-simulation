package dev.fairplay.telemetry.detector;

import org.apache.kafka.streams.KafkaStreams;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

/**
 * UP when the streams client is RUNNING, OUT_OF_SERVICE while it is CREATED or REBALANCING, DOWN otherwise. Before
 * the client has been created at all there is nothing to ask, which also counts as out of service.
 */
@Component
class KafkaStreamsHealthIndicator implements HealthIndicator {

    private final StreamsBuilderFactoryBean factoryBean;

    KafkaStreamsHealthIndicator(StreamsBuilderFactoryBean factoryBean) {
        this.factoryBean = factoryBean;
    }

    @Override
    public Health health() {
        KafkaStreams streams = factoryBean.getKafkaStreams();
        if (streams == null) {
            return Health.outOfService().withDetail("state", "NOT_CREATED").build();
        }
        KafkaStreams.State state = streams.state();
        Health.Builder builder = switch (state) {
            case RUNNING -> Health.up();
            case CREATED, REBALANCING -> Health.outOfService();
            default -> Health.down();
        };
        return builder.withDetail("state", state.name()).build();
    }
}
