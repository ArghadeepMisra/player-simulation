package dev.fairplay.telemetry.detector.topology;

import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerdes;
import dev.fairplay.telemetry.common.model.DeadLetter;
import dev.fairplay.telemetry.common.model.IntegrityStats;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import dev.fairplay.telemetry.common.support.MutableClock;
import dev.fairplay.telemetry.common.support.TestEvents;
import dev.fairplay.telemetry.detector.DetectionProperties;
import dev.fairplay.telemetry.detector.TestDetection;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;

/**
 * Runs the real {@link TelemetryTopology} in memory with controlled time and no broker. Event time starts at
 * {@link #START}; the injected {@link MutableClock} starts there too.
 */
public final class TopologyFixture implements AutoCloseable {

    public static final Instant START = Instant.parse("2026-11-17T10:00:00Z");

    /** Counts what the topology reports, so tests can assert on it. */
    public static final class RecordingMetrics implements TopologyMetrics {
        public final AtomicInteger parsed = new AtomicInteger();
        public final AtomicInteger deadLettered = new AtomicInteger();
        public final AtomicInteger aimbotFlags = new AtomicInteger();
        public final AtomicInteger speedhackFlags = new AtomicInteger();

        @Override
        public void parsed() {
            parsed.incrementAndGet();
        }

        @Override
        public void deadLettered() {
            deadLettered.incrementAndGet();
        }

        @Override
        public void flagged(Rule rule) {
            (rule == Rule.AIMBOT ? aimbotFlags : speedhackFlags).incrementAndGet();
        }
    }

    public final MutableClock clock = new MutableClock(START);
    public final RecordingMetrics metrics = new RecordingMetrics();
    public final Topology topology;
    public final TopologyTestDriver driver;
    public final TestInputTopic<String, byte[]> events;
    public final TestInputTopic<String, PlayerProfile> profiles;
    public final TestOutputTopic<String, DeadLetter> deadLetters;
    public final TestOutputTopic<String, IntegrityStats> integrity;
    public final TestOutputTopic<String, SuspiciousPlayer> flags;

    public TopologyFixture() {
        this(TestDetection.defaults());
    }

    public TopologyFixture(DetectionProperties props) {
        StreamsBuilder builder = new StreamsBuilder();
        new TelemetryTopology(props, clock, metrics).addTo(builder);
        topology = builder.build();

        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-test");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        p.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0); // emit on every update
        driver = new TopologyTestDriver(topology, p, START);

        events = driver.createInputTopic(Topics.MATCH_EVENTS, new StringSerializer(), new ByteArraySerializer());
        profiles = driver.createInputTopic(Topics.PLAYER_PROFILES, new StringSerializer(),
                JsonSerdes.profile().serializer());
        deadLetters = driver.createOutputTopic(Topics.MATCH_EVENTS_DLT, new StringDeserializer(),
                JsonSerdes.deadLetter().deserializer());
        integrity = driver.createOutputTopic(Topics.INTEGRITY_STATS, new StringDeserializer(),
                JsonSerdes.integrity().deserializer());
        flags = driver.createOutputTopic(Topics.SUSPICIOUS_PLAYERS, new StringDeserializer(),
                JsonSerdes.suspicious().deserializer());
    }

    /** Pipes one event, keyed by its player id, with event time {@code ts}. */
    public void send(MatchEvent event) {
        events.pipeInput(event.playerId(), TestEvents.bytes(event), event.ts());
    }

    public void sendAll(List<MatchEvent> batch) {
        batch.forEach(this::send);
    }

    /** Moves both the injected clock and the driver's wall clock, which triggers wall-clock punctuators. */
    public void advanceTime(java.time.Duration duration) {
        clock.advance(duration);
        driver.advanceWallClockTime(duration);
    }

    @Override
    public void close() {
        driver.close();
    }
}
