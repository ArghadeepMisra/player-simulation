package dev.fairplay.telemetry.verifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.fairplay.telemetry.common.json.JsonSerde;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads a whole topic as it stands now. A plain consumer with no group id, {@code read_committed} (so records of an
 * aborted transaction are never seen), no auto-commit, assigned to every partition and sought to the beginning. It
 * records the end offsets once and polls until each partition's position reaches its end, or until
 * {@code read-timeout} passes.
 */
@Component
public class TopicSnapshotReader {

    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(10);

    private final String bootstrapServers;
    private final Duration readTimeout;

    public TopicSnapshotReader(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                               VerifierProperties props) {
        this.bootstrapServers = bootstrapServers;
        this.readTimeout = props.readTimeout();
    }

    /** Every record, partition by partition in offset order. Empty when the topic does not exist. */
    public List<ConsumerRecord<String, byte[]>> readAll(String topic) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, METADATA_TIMEOUT);
            if (infos == null || infos.isEmpty()) {
                return List.of();
            }
            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);

            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + readTimeout.toNanos();
            while (!reachedEnds(consumer, ends) && System.nanoTime() < deadline) {
                consumer.poll(POLL).forEach(records::add);
            }
            return records;
        }
    }

    /** The latest value per key. A null value (a tombstone) removes the key. */
    public Map<String, byte[]> readLatest(String topic) {
        Map<String, byte[]> latest = new LinkedHashMap<>();
        for (ConsumerRecord<String, byte[]> record : readAll(topic)) {
            if (record.value() == null) {
                latest.remove(record.key());
            } else {
                latest.put(record.key(), record.value());
            }
        }
        return latest;
    }

    public <T> Map<String, T> readLatest(String topic, Class<T> type) {
        Map<String, T> typed = new LinkedHashMap<>();
        readLatest(topic).forEach((key, bytes) -> typed.put(key, JsonSerde.MAPPER.readValue(bytes, type)));
        return typed;
    }

    /** Every non-null value, in offset order within each partition. */
    public <T> List<T> readAll(String topic, Class<T> type) {
        return readAll(topic).stream()
                .filter(record -> record.value() != null)
                .map(record -> JsonSerde.MAPPER.readValue(record.value(), type))
                .toList();
    }

    private static boolean reachedEnds(KafkaConsumer<String, byte[]> consumer, Map<TopicPartition, Long> ends) {
        return ends.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
    }
}
