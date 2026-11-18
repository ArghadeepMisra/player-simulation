package dev.fairplay.telemetry.common.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.EventType;
import dev.fairplay.telemetry.common.model.MatchEvent;

/** Builders for valid {@link MatchEvent}s. Ids are deterministic so tests are repeatable. */
public final class TestEvents {

    public static final String RUN_ID = "r-test01";
    public static final String MATCH_ID = "sim-r-test01-000001";

    private TestEvents() {
    }

    /** A stable player id (a UUID string) for test player {@code n}. */
    public static String player(int n) {
        return UUID.nameUUIDFromBytes(("player-" + n).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static MatchEvent shot(String player, long seq, long ts, boolean hit, boolean headshot, int reactionMs) {
        return new MatchEvent(eventId(player, seq), RUN_ID, player, MATCH_ID, seq, EventType.SHOT, ts,
                hit, headshot, reactionMs, null, null, null, null);
    }

    public static MatchEvent position(String player, long seq, long ts, double dx, double dy) {
        return new MatchEvent(eventId(player, seq), RUN_ID, player, MATCH_ID, seq, EventType.POSITION, ts,
                null, null, null, dx, dy, 50, null);
    }

    public static MatchEvent kill(String player, long seq, long ts, String victim, boolean headshot) {
        return new MatchEvent(eventId(player, seq), RUN_ID, player, MATCH_ID, seq, EventType.KILL, ts,
                null, headshot, null, null, null, null, victim);
    }

    public static byte[] bytes(MatchEvent event) {
        return JsonSerde.MAPPER.writeValueAsBytes(event);
    }

    private static String eventId(String player, long seq) {
        return UUID.nameUUIDFromBytes((player + ":" + seq).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
