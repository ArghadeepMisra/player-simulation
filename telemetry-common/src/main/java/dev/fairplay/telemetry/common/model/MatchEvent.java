package dev.fairplay.telemetry.common.model;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One gameplay event. Fields not used by an event's type are null and omitted from the JSON.
 * Times are epoch milliseconds.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchEvent(
        String eventId,
        String runId,
        String playerId,
        String matchId,
        long seq,
        EventType type,
        long ts,
        Boolean hit,
        Boolean headshot,
        Integer reactionMs,
        Double dx,
        Double dy,
        Integer dtMs,
        String victimId) {

    /** False when a field required for the type is missing, seq &lt; 1, ts &lt;= 0 or playerId is not a UUID. */
    @JsonIgnore
    public boolean isValid() {
        if (type == null || seq < 1 || ts <= 0 || !isUuid(playerId)) {
            return false;
        }
        if (isBlank(eventId) || isBlank(runId) || isBlank(matchId)) {
            return false;
        }
        return switch (type) {
            case SHOT -> hit != null && headshot != null && reactionMs != null;
            // dtMs must be positive too, because speed = distance / dt would divide by zero.
            case POSITION -> dx != null && dy != null && dtMs != null && dtMs > 0;
            case KILL -> !isBlank(victimId) && headshot != null;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Strict check: {@link UUID#fromString} alone accepts shortened forms such as "1-1-1-1-1". */
    static boolean isUuid(String s) {
        if (s == null) {
            return false;
        }
        try {
            return UUID.fromString(s).toString().equalsIgnoreCase(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
