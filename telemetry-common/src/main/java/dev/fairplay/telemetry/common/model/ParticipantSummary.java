package dev.fairplay.telemetry.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParticipantSummary(
        String playerId,
        int placement,
        int kills,
        int deaths,
        int headshots,
        int shotsFired,
        int shotsHit,
        int avgReactionMs) {
}
