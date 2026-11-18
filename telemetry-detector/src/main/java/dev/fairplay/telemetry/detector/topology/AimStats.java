package dev.fairplay.telemetry.detector.topology;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.fairplay.telemetry.common.model.MatchEvent;

/** What one aimbot window has seen of a player's SHOT events. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AimStats(int shots, int hits, int headshotHits, long sumReactionMs) {

    public static AimStats empty() {
        return new AimStats(0, 0, 0, 0);
    }

    /** A new value with this SHOT event added: every shot counts toward the reaction time, hits and headshots only when set. */
    public AimStats add(MatchEvent shot) {
        return new AimStats(
                shots + 1,
                hits + (Boolean.TRUE.equals(shot.hit()) ? 1 : 0),
                headshotHits + (Boolean.TRUE.equals(shot.headshot()) ? 1 : 0),
                sumReactionMs + (shot.reactionMs() == null ? 0 : shot.reactionMs()));
    }

    /** Mean reaction time over all shots in the window, 0 when there are none. */
    @JsonIgnore
    public double avgReactionMs() {
        return shots == 0 ? 0.0 : (double) sumReactionMs / shots;
    }

    /** Headshot hits per hit, 0 when there are no hits. */
    @JsonIgnore
    public double headshotRate() {
        return hits == 0 ? 0.0 : (double) headshotHits / hits;
    }
}
