package dev.fairplay.telemetry.common.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Shaped exactly like the body of Arena's POST /api/v1/matches. Times are ISO-8601 strings. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchSummary(
        String externalId,
        String gameMode,
        String mapName,
        String startedAt,
        String endedAt,
        List<ParticipantSummary> participants) {
}
