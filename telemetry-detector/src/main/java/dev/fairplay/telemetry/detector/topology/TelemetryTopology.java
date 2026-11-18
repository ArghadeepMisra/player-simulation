package dev.fairplay.telemetry.detector.topology;

import java.time.Clock;
import java.util.Map;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerdes;
import dev.fairplay.telemetry.common.model.EventType;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.detector.DetectionProperties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

/**
 * The whole detector as plain Kafka Streams code. It takes plain objects and adds itself to a {@link StreamsBuilder},
 * so it can be built and tested with {@code TopologyTestDriver} and no Spring. Spring only wires it.
 */
public final class TelemetryTopology {

    public static final String SEQ_STORE = "seq-tracker";
    public static final String COOLDOWN_STORE = "flag-cooldown";
    public static final String AIMBOT_WINDOWS = "aimbot-windows";
    public static final String SPEEDHACK_WINDOWS = "speedhack-windows";
    public static final String PROFILES_STORE = "player-profiles-store";
    /** Name of the explicit repartition step, which keeps the internal topic name stable across code changes. */
    public static final String FLAGS_REPARTITION = "flags";

    private final DetectionProperties props;
    private final Clock clock;
    private final TopologyMetrics metrics;
    private final DetectionRules rules;

    public TelemetryTopology(DetectionProperties props, Clock clock, TopologyMetrics metrics) {
        this.props = props;
        this.clock = clock;
        this.metrics = metrics;
        this.rules = new DetectionRules(props);
    }

    public void addTo(StreamsBuilder builder) {
        KTable<String, PlayerProfile> profiles = builder.table(Topics.PLAYER_PROFILES,
                Consumed.with(Serdes.String(), JsonSerdes.profile()),
                Materialized.<String, PlayerProfile, KeyValueStore<Bytes, byte[]>>as(PROFILES_STORE));

        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(SEQ_STORE), Serdes.String(), JsonSerdes.integrity()));
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(COOLDOWN_STORE), Serdes.String(), Serdes.Long()));

        // 1. Parse, and route failures to the DLT. Events are read as raw bytes so that a bad record is handled
        //    by normal topology code instead of a deserialization exception handler.
        KStream<String, byte[]> raw = builder.stream(Topics.MATCH_EVENTS,
                Consumed.with(Serdes.String(), Serdes.ByteArray()));
        KStream<String, ParseResult> parsed = raw.process(() -> new ParseProcessor(clock, metrics));
        Map<String, KStream<String, ParseResult>> outcome = parsed.split(Named.as("parse-"))
                .branch((key, result) -> result instanceof ParseResult.Failed, Branched.as("failed"))
                .defaultBranch(Branched.as("ok"));

        outcome.get("parse-failed")
                .mapValues(result -> ((ParseResult.Failed) result).deadLetter())
                .to(Topics.MATCH_EVENTS_DLT, Produced.with(Serdes.String(), JsonSerdes.deadLetter()));

        KStream<String, MatchEvent> events = outcome.get("parse-ok")
                .mapValues(result -> ((ParseResult.Parsed) result).event());

        // 2. Integrity tracking: per-player seq counts, re-emitted for every player on a wall-clock interval.
        events.process(() -> new IntegrityProcessor(props.integrityEmitInterval(), clock), SEQ_STORE)
                .to(Topics.INTEGRITY_STATS, Produced.with(Serdes.String(), JsonSerdes.integrity()));

        // 3. Detection windows read their own key-preserving parse of the raw stream. They must not use the
        //    `events` stream above. KStream#process marks its output as possibly key-changing, so grouping it would
        //    make Kafka Streams add an internal repartition topic per window and rewrite every event (about 24,000
        //    per second) through the broker. mapValues keeps the key, so no repartition topic appears. EventParser is
        //    the same predicate ParseProcessor uses, so both paths accept exactly the same records.
        KStream<String, MatchEvent> windowEvents = raw
                .mapValues((key, bytes) -> EventParser.parse(key, bytes).event())
                .filter((key, event) -> event != null);

        // 3a. Aimbot: a hopping window over SHOT events.
        var aim = props.aimbot();
        KStream<String, FlagCandidate> aimFlags = windowEvents
                .filter((key, event) -> event.type() == EventType.SHOT)
                .groupByKey(Grouped.with(Serdes.String(), JsonSerdes.matchEvent()))
                .windowedBy(TimeWindows.ofSizeAndGrace(aim.windowSize(), aim.grace()).advanceBy(aim.advance()))
                .aggregate(AimStats::empty, (key, event, stats) -> stats.add(event),
                        Materialized.<String, AimStats, WindowStore<Bytes, byte[]>>as(AIMBOT_WINDOWS)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(JsonSerdes.of(AimStats.class)))
                .toStream()
                .filter((windowedKey, stats) -> stats != null && rules.aimbotTriggered(stats))
                .map((windowedKey, stats) -> KeyValue.pair(windowedKey.key(),
                        FlagCandidate.aimbot(windowedKey.key(), windowedKey.window(), stats, rules)));

        // 3b. Speedhack: the same shape over POSITION events.
        var speed = props.speedhack();
        KStream<String, FlagCandidate> speedFlags = windowEvents
                .filter((key, event) -> event.type() == EventType.POSITION)
                .groupByKey(Grouped.with(Serdes.String(), JsonSerdes.matchEvent()))
                .windowedBy(TimeWindows.ofSizeAndGrace(speed.windowSize(), speed.grace()).advanceBy(speed.advance()))
                .aggregate(SpeedStats::empty,
                        (key, event, stats) -> stats.add(event, rules.movingSpeedFloor(), rules.speedLimit()),
                        Materialized.<String, SpeedStats, WindowStore<Bytes, byte[]>>as(SPEEDHACK_WINDOWS)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(JsonSerdes.of(SpeedStats.class)))
                .toStream()
                .filter((windowedKey, stats) -> stats != null && rules.speedhackTriggered(stats))
                .map((windowedKey, stats) -> KeyValue.pair(windowedKey.key(),
                        FlagCandidate.speedhack(windowedKey.key(), windowedKey.window(), stats, rules)));

        // 4. Merge, repartition explicitly, enrich, de-duplicate, publish. Mapping a Windowed<String> key to a plain
        //    String counts as a key change, so Kafka Streams would add a repartition topic before the join anyway.
        //    Naming it keeps the internal topic name stable. Flags are rare, so the cost is negligible.
        aimFlags.merge(speedFlags)
                .repartition(Repartitioned.with(Serdes.String(), JsonSerdes.of(FlagCandidate.class))
                        .withName(FLAGS_REPARTITION))
                .leftJoin(profiles, FlagCandidate::withProfile,
                        Joined.with(Serdes.String(), JsonSerdes.of(FlagCandidate.class), JsonSerdes.profile()))
                .process(() -> new CooldownProcessor(props.cooldown(), props.detectorVersion(), clock, metrics),
                        COOLDOWN_STORE)
                .to(Topics.SUSPICIOUS_PLAYERS, Produced.with(Serdes.String(), JsonSerdes.suspicious()));
    }
}
