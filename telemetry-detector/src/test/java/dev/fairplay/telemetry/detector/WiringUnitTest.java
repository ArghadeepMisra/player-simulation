package dev.fairplay.telemetry.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.fairplay.telemetry.common.model.Rule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

/** The small Spring-facing classes of the detector, tested without a context or a broker. */
class WiringUnitTest {

    // --- MicrometerTopologyMetrics -------------------------------------------------------------------------------

    @Test
    void metricsCountParsedDeadLetteredAndFlaggedPerRule() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerTopologyMetrics metrics = new MicrometerTopologyMetrics(registry);

        metrics.parsed();
        metrics.parsed();
        metrics.deadLettered();
        metrics.flagged(Rule.AIMBOT);
        metrics.flagged(Rule.AIMBOT);
        metrics.flagged(Rule.SPEEDHACK);

        assertThat(registry.get("telemetry.detect.events.parsed").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("telemetry.detect.events.dead_lettered").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("telemetry.detect.flags").tag("rule", "AIMBOT").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("telemetry.detect.flags").tag("rule", "SPEEDHACK").counter().count()).isEqualTo(1.0);
    }

    // --- KafkaStreamsHealthIndicator -----------------------------------------------------------------------------

    private static Health healthIn(KafkaStreams.State state) {
        StreamsBuilderFactoryBean factory = mock(StreamsBuilderFactoryBean.class);
        KafkaStreams streams = mock(KafkaStreams.class);
        when(factory.getKafkaStreams()).thenReturn(streams);
        when(streams.state()).thenReturn(state);
        return new KafkaStreamsHealthIndicator(factory).health();
    }

    @Test
    void healthIsUpOnlyWhileRunning() {
        Health health = healthIn(KafkaStreams.State.RUNNING);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "RUNNING");
    }

    @Test
    void healthIsOutOfServiceWhileCreatedOrRebalancing() {
        assertThat(healthIn(KafkaStreams.State.CREATED).getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(healthIn(KafkaStreams.State.REBALANCING).getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void healthIsDownInEveryOtherState() {
        assertThat(healthIn(KafkaStreams.State.ERROR).getStatus()).isEqualTo(Status.DOWN);
        assertThat(healthIn(KafkaStreams.State.NOT_RUNNING).getStatus()).isEqualTo(Status.DOWN);
        assertThat(healthIn(KafkaStreams.State.PENDING_SHUTDOWN).getStatus()).isEqualTo(Status.DOWN);
        assertThat(healthIn(KafkaStreams.State.PENDING_ERROR).getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void healthIsOutOfServiceBeforeTheClientExists() {
        StreamsBuilderFactoryBean factory = mock(StreamsBuilderFactoryBean.class);
        when(factory.getKafkaStreams()).thenReturn(null);

        Health health = new KafkaStreamsHealthIndicator(factory).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    // --- StreamsRuntimeConfig ------------------------------------------------------------------------------------

    @Test
    void aDeadStreamThreadIsReplaced() {
        StreamsBuilderFactoryBean factoryBean = new StreamsBuilderFactoryBean();

        new StreamsRuntimeConfig().streamsRuntimeConfigurer().configure(factoryBean);

        assertThat(factoryBean.getStreamsUncaughtExceptionHandler().handle(new IllegalStateException("boom")))
                .isEqualTo(StreamThreadExceptionResponse.REPLACE_THREAD);
    }

    @Test
    void theClockIsUtcSystemTime() {
        assertThat(new ClockConfig().clock().getZone().getId()).isEqualTo("Z");
    }
}
