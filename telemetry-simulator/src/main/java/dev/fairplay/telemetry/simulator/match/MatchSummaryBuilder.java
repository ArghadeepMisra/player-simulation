package dev.fairplay.telemetry.simulator.match;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.model.ParticipantSummary;
import dev.fairplay.telemetry.simulator.population.PlayerState;

/** Turns a finished {@link MatchRoom} into a body Arena's {@code POST /api/v1/matches} accepts. */
public final class MatchSummaryBuilder {

    private static final String GAME_MODE = "FFA";

    private MatchSummaryBuilder() {
    }

    public static MatchSummary build(MatchRoom room, long endedAtMs) {
        List<PlayerState> ranked = new ArrayList<>(room.members());
        // Placement: kills descending, then deaths ascending, then player id ascending.
        ranked.sort(Comparator
                .comparingInt((PlayerState p) -> room.totalsOf(p.playerId()).kills()).reversed()
                .thenComparingInt(p -> room.totalsOf(p.playerId()).deaths())
                .thenComparing(PlayerState::playerId));

        List<ParticipantSummary> participants = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            PlayerState player = ranked.get(i);
            MatchRoom.PlayerTotals t = room.totalsOf(player.playerId());
            int avgReaction = t.shotsFired() == 0
                    ? 1
                    : (int) Math.max(1, Math.round((double) t.sumReactionMs() / t.shotsFired()));
            participants.add(new ParticipantSummary(player.playerId(), i + 1, t.kills(), t.deaths(),
                    t.headshotKills(), t.shotsFired(), t.shotsHit(), avgReaction));
        }
        return new MatchSummary(room.externalId(), GAME_MODE, room.mapName(),
                Instant.ofEpochMilli(room.startedAtMs()).toString(),
                Instant.ofEpochMilli(endedAtMs).toString(),
                participants);
    }
}
