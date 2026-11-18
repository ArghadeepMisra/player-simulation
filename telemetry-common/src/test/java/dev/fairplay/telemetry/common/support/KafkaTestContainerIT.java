package dev.fairplay.telemetry.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;

/** Smoke test: failsafe runs *IT classes, Docker is reachable, and the pinned Kafka image starts. */
class KafkaTestContainerIT {

    @Test
    void brokerStartsAndAnswers() throws Exception {
        String bootstrap = KafkaTestContainer.KAFKA.getBootstrapServers();
        assertThat(bootstrap).isNotBlank();

        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            assertThat(admin.describeCluster().nodes().get()).hasSize(1);
        }
    }
}
