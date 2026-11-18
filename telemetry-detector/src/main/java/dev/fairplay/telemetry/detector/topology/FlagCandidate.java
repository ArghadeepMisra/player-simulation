package dev.fairplay.telemetry.detector.topology;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.Rule;
import dev.fairplay.telemetry.common.model.SkillTier;
import org.apache.kafka.streams.kstream.Window;

/**
 * A window that crossed a threshold, before it is enriched with the player's profile and de-duplicated. It travels
 * through the internal {@code flags} repartition topic, so it must stay serializable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlagCandidate(
        String playerId,
        Rule rule,
        double score,
        long windowStart,
        long windowEnd,
        Evidence evidence,
        String username,
        SkillTier skillTier) {

    public static FlagCandidate aimbot(String playerId, Window window, AimStats stats, DetectionRules rules) {
        return new FlagCandidate(playerId, Rule.AIMBOT, rules.aimbotScore(stats), window.start(), window.end(),
                rules.aimbotEvidence(stats), null, null);
    }

    public static FlagCandidate speedhack(String playerId, Window window, SpeedStats stats, DetectionRules rules) {
        return new FlagCandidate(playerId, Rule.SPEEDHACK, rules.speedhackScore(stats), window.start(), window.end(),
                rules.speedhackEvidence(stats), null, null);
    }

    /** The value joiner for the left join with player-profiles. {@code profile} is null when none was found. */
    public FlagCandidate withProfile(PlayerProfile profile) {
        if (profile == null) {
            return this;
        }
        return new FlagCandidate(playerId, rule, score, windowStart, windowEnd, evidence, profile.username(),
                profile.skillTier());
    }
}
