package dev.fairplay.telemetry.detector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.support.KafkaTestContainer;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Plain-client helpers for the integration tests: produce events and read whole topics with read_committed. */
final class KafkaIt {

    private KafkaIt() {
    }

    static String bootstrap() {
        return KafkaTestContainer.KAFKA.getBootstrapServers();
    }

    static KafkaProducer<String, byte[]> producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        return new KafkaProducer<>(p);
    }

    /** Sends one event keyed by its player id, with the event time as the record timestamp. */
    static void send(KafkaProducer<String, byte[]> producer, MatchEvent event) {
        producer.send(new ProducerRecord<>("match-events", null, event.ts(), event.playerId(), TestEvents.bytes(event)));
    }

    static void sendJson(KafkaProducer<String, byte[]> producer, String topic, String key, Object value, long ts) {
        producer.send(new ProducerRecord<>(topic, null, ts, key, JsonSerde.MAPPER.writeValueAsBytes(value)));
    }

    /** Everything currently committed in {@code topic}, from the beginning. Empty when the topic does not exist yet. */
    static List<ConsumerRecord<String, byte[]>> readTopic(String topic) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "it-reader-" + UUID.randomUUID());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            var infos = consumer.partitionsFor(topic, Duration.ofSeconds(5));
            if (infos == null || infos.isEmpty()) {
                return List.of();
            }
            List<TopicPartition> partitions = infos.stream()
                    .map(i -> new TopicPartition(i.topic(), i.partition())).collect(Collectors.toList());
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);

            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!reachedEnds(consumer, ends) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private static boolean reachedEnds(KafkaConsumer<String, byte[]> consumer, Map<TopicPartition, Long> ends) {
        return ends.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
    }

    static <T> List<T> readValues(String topic, Class<T> type) {
        return readTopic(topic).stream()
                .filter(r -> r.value() != null)
                .map(r -> JsonSerde.MAPPER.readValue(r.value(), type))
                .collect(Collectors.toList());
    }

    /** Partition counts of every topic on the broker. */
    static Map<String, Integer> partitionCounts() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap()))) {
            Map<String, Integer> counts = new HashMap<>();
            admin.describeTopics(admin.listTopics().names().get()).allTopicNames().get()
                    .forEach((name, d) -> counts.put(name, d.partitions().size()));
            return counts;
        }
    }
}
