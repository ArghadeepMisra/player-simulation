package dev.fairplay.telemetry.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Stored in simulation-manifest under the key {@code __run__}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunInfo(
        String runId,
        long startedAt,
        long endedAt,
        int players,
        long totalAcked,
        long totalFailed,
        long malformedSent) {
}
