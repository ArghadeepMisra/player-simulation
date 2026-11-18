package dev.fairplay.telemetry.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntegrityStats(
        String playerId,
        long count,
        long lastSeq,
        long duplicates,
        long gaps,
        long updatedAt) {

    public static IntegrityStats empty(String playerId) {
        return new IntegrityStats(playerId, 0, 0, 0, 0, 0);
    }
}
