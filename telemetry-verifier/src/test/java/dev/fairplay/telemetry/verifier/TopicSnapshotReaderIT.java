package dev.fairplay.telemetry.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.support.KafkaTestContainer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/** The reader against a real broker: latest value per key, tombstones, read_committed and the end-offset stop. */
class TopicSnapshotReaderIT {

    private static final String BOOTSTRAP = KafkaTestContainer.KAFKA.getBootstrapServers();

    private final TopicSnapshotReader reader = new TopicSnapshotReader(BOOTSTRAP,
            new VerifierProperties("./reports", Duration.ofSeconds(20), Duration.ofSeconds(1)));

    private static String newTopic(int partitions) throws Exception {
        String topic = "it-" + UUID.randomUUID();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", BOOTSTRAP))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        }
        return topic;
    }

    private static Properties producerProps() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return p;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keepsTheLatestValuePerKeyAndTombstonesRemoveKeys() throws Exception {
        String topic = newTopic(3);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(topic, "k1", bytes("v1")));
            producer.send(new ProducerRecord<>(topic, "k2", bytes("v2")));
            producer.send(new ProducerRecord<>(topic, "k1", bytes("v3")));
            producer.send(new ProducerRecord<>(topic, "k2", null));
            producer.send(new ProducerRecord<>(topic, "k3", bytes("v4")));
            producer.flush();
        }

        Map<String, byte[]> latest = reader.readLatest(topic);

        assertThat(latest).containsOnlyKeys("k1", "k3");
        assertThat(new String(latest.get("k1"), StandardCharsets.UTF_8)).isEqualTo("v3");
        assertThat(reader.readAll(topic)).hasSize(5);
    }

    @Test
    void readsTypedValuesAndSkipsNullOnes() throws Exception {
        String topic = newTopic(1);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(topic, "p1",
                    JsonSerde.MAPPER.writeValueAsBytes(new IntegrityStats("p1", 5, 5, 0, 0, 1))));
            producer.send(new ProducerRecord<>(topic, "p2", null));
            producer.flush();
        }

        assertThat(reader.readLatest(topic, IntegrityStats.class)).containsOnlyKeys("p1");
        assertThat(reader.readAll(topic, IntegrityStats.class)).extracting(IntegrityStats::count).containsExactly(5L);
    }

    @Test
    void returnsWhenItReachesTheEndOffsetsOfAnEmptyTopic() throws Exception {
        String topic = newTopic(2);
        long before = System.nanoTime();

        assertThat(reader.readAll(topic)).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void aTopicThatDoesNotExistGivesNothing() {
        // The broker may auto-create it on the metadata request; either way there is nothing in it.
        assertThat(reader.readAll("it-missing-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void neverShowsRecordsOfAnAbortedTransaction() throws Exception {
        String topic = newTopic(1);
        Properties props = producerProps();
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "it-tx-" + UUID.randomUUID());
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            producer.initTransactions();
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(topic, "aborted", bytes("x")));
            producer.flush();
            producer.abortTransaction();
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(topic, "committed", bytes("y")));
            producer.commitTransaction();
        }

        assertThat(reader.readLatest(topic)).containsOnlyKeys("committed");
    }
}
