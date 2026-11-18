package dev.fairplay.telemetry.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerProfile(
        String playerId,
        String username,
        String country,
        SkillTier skillTier,
        long registeredAt) {
}
