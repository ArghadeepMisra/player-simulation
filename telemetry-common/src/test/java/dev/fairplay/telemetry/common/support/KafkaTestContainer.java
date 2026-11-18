package dev.fairplay.telemetry.common.support;

import org.testcontainers.kafka.KafkaContainer;

/**
 * One Kafka broker per JVM for the integration tests. Expose it with a {@code @DynamicPropertySource} that sets
 * {@code spring.kafka.bootstrap-servers} to {@code KAFKA.getBootstrapServers()}.
 */
public final class KafkaTestContainer {

    // The tag is the kafka-clients version Boot 4.1.2 resolves (4.2.2). Update it with the Boot version.
    public static final String KAFKA_VERSION = "4.2.2";

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:" + KAFKA_VERSION);

    static {
        KAFKA.start();
    }

    private KafkaTestContainer() {
    }
}
