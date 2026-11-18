package dev.fairplay.telemetry.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManifestEntry(
        String runId,
        String playerId,
        long finalSeq,
        long acked,
        long failed) {
}
