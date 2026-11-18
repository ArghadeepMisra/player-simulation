package dev.fairplay.telemetry.common.json;

import java.util.Map;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Jackson 3 JSON serde for one record class. Used instead of Spring Kafka's Jackson 2 JsonSerde. */
public final class JsonSerde<T> implements Serde<T>, Serializer<T>, Deserializer<T> {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Class<T> type;

    public JsonSerde(Class<T> type) {
        this.type = type;
    }

    @Override
    public byte[] serialize(String topic, T value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (RuntimeException e) {
            throw new SerializationException("Cannot serialize " + type.getSimpleName(), e);
        }
    }

    @Override
    public T deserialize(String topic, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return MAPPER.readValue(bytes, type);
        } catch (RuntimeException e) {
            throw new SerializationException("Cannot deserialize " + type.getSimpleName(), e);
        }
    }

    @Override
    public Serializer<T> serializer() {
        return this;
    }

    @Override
    public Deserializer<T> deserializer() {
        return this;
    }

    // Serde, Serializer and Deserializer each define default configure/close, so one must be chosen.
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public void close() {
    }
}
