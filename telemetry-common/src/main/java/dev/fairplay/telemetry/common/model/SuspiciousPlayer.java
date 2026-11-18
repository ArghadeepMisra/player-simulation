package dev.fairplay.telemetry.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The contract with downstream consumers of {@code suspicious-players}; renaming a field breaks them.
 * username and skillTier are null when no profile was found. Rates are rounded to 4 decimals and score to 3
 * (HALF_UP) by the producer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuspiciousPlayer(
        String flagId,
        String playerId,
        String username,
        SkillTier skillTier,
        Rule rule,
        double score,
        long windowStart,
        long windowEnd,
        long detectedAt,
        Evidence evidence,
        String detectorVersion) {
}
