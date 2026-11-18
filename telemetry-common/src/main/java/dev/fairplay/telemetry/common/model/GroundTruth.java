package dev.fairplay.telemetry.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Known only to the simulator and the verifier. The detector must never read it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroundTruth(
        String runId,
        String playerId,
        Segment segment,
        CheatType cheatType,
        Long cheatStartsAt) {
}
