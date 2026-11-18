package dev.fairplay.telemetry.detector.topology;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.MatchEvent;

/**
 * The single definition of "a good record": parseable JSON, a valid event, and a record key equal to the event's
 * player id. {@link ParseProcessor} uses it to decide what goes to the dead-letter topic, and the window branches
 * of the topology use it to decide what to count, so the two can never disagree.
 */
final class EventParser {

    /** Exactly one of {@code event} and {@code problem} is non-null. */
    record Outcome(MatchEvent event, String problem) {
    }

    private EventParser() {
    }

    static Outcome parse(String key, byte[] bytes) {
        if (bytes == null) {
            return new Outcome(null, "null payload");
        }
        try {
            MatchEvent event = JsonSerde.MAPPER.readValue(bytes, MatchEvent.class);
            if (event == null) {
                return new Outcome(null, "payload is JSON null");
            }
            if (!event.isValid()) {
                return new Outcome(null, "invalid event: a required field is missing or malformed");
            }
            if (!event.playerId().equals(key)) {
                return new Outcome(null, "key mismatch: record key '" + key + "' is not playerId '"
                        + event.playerId() + "'");
            }
            return new Outcome(event, null);
        } catch (RuntimeException e) {
            return new Outcome(null, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
