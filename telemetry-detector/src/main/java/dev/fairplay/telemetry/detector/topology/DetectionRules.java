package dev.fairplay.telemetry.detector.topology;

import java.math.BigDecimal;
import java.math.RoundingMode;

import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.detector.DetectionProperties;

/**
 * Pure threshold checks and score formulas. Boundaries are inclusive, using
 * {@code >=} and {@code <=}. A score of 0.5 means just over the line and 1.0 means far over it.
 */
public final class DetectionRules {

    // The "far over the line" ends of the score ranges. They are part of the formula, not tunables.
    static final double AIMBOT_TOP_HEADSHOT_RATE = 0.95;
    static final double AIMBOT_BEST_REACTION_MS = 30.0;
    static final double SPEEDHACK_TOP_RATIO = 1.0;

    private final DetectionProperties.Aimbot aim;
    private final DetectionProperties.Speedhack speed;

    public DetectionRules(DetectionProperties props) {
        this.aim = props.aimbot();
        this.speed = props.speedhack();
    }

    public boolean aimbotTriggered(AimStats s) {
        return s.hits() >= aim.minHits()
                && s.avgReactionMs() <= aim.maxAvgReactionMs()
                && s.headshotRate() >= aim.minHeadshotRate();
    }

    /** {@code 0.5 + 0.25 x c((rate - min) / (0.95 - min)) + 0.25 x c((maxReaction - avg) / (maxReaction - 30))}. */
    public double aimbotScore(AimStats s) {
        double rateTerm = clamp((s.headshotRate() - aim.minHeadshotRate())
                / (AIMBOT_TOP_HEADSHOT_RATE - aim.minHeadshotRate()));
        double reactionTerm = clamp((aim.maxAvgReactionMs() - s.avgReactionMs())
                / (aim.maxAvgReactionMs() - AIMBOT_BEST_REACTION_MS));
        return round(0.5 + 0.25 * rateTerm + 0.25 * reactionTerm, 3);
    }

    public Evidence aimbotEvidence(AimStats s) {
        return Evidence.aimbot(s.shots(), s.hits(), s.headshotHits(), round(s.headshotRate(), 4),
                round(s.avgReactionMs(), 4));
    }

    public boolean speedhackTriggered(SpeedStats s) {
        return s.movingSamples() >= speed.minMovingSamples() && s.overspeedRatio() >= speed.minOverspeedRatio();
    }

    /** {@code 0.5 + 0.5 x c((ratio - min) / (1 - min))}. */
    public double speedhackScore(SpeedStats s) {
        double ratioTerm = clamp((s.overspeedRatio() - speed.minOverspeedRatio())
                / (SPEEDHACK_TOP_RATIO - speed.minOverspeedRatio()));
        return round(0.5 + 0.5 * ratioTerm, 3);
    }

    public Evidence speedhackEvidence(SpeedStats s) {
        return Evidence.speedhack(s.movingSamples(), s.overspeedSamples(), round(s.overspeedRatio(), 4),
                round(s.maxSpeed(), 4));
    }

    /** The detector's own movement-speed settings, for the window aggregator. */
    public double movingSpeedFloor() {
        return speed.movingSpeedFloor();
    }

    public double speedLimit() {
        return speed.speedLimit();
    }

    static double clamp(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    /** HALF_UP, as the {@code suspicious-players} contract requires. */
    static double round(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
}
