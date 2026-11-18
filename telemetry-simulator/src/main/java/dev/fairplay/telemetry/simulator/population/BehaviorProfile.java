package dev.fairplay.telemetry.simulator.population;

import java.util.random.RandomGenerator;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.SkillTier;

/**
 * How a player aims and moves. Drawn once per player. Cheaters get a second, cheat profile
 * derived from their base profile: aimbots change only the aim values, speedhackers only the movement values.
 */
public record BehaviorProfile(Aim aim, Move move) {

    /** Aiming parameters. {@code headshotChance} applies given a hit. */
    public record Aim(double hitChance, double headshotChance, double reactionMean, double reactionSd,
                      int reactionFloor) {
    }

    /** Movement parameters. {@code speedMin..speedMax} (units/s) is drawn again on every tick. */
    public record Move(double moveChance, double speedMin, double speedMax, double speedMultiplier) {
    }

    private static final double HONEST_MOVE_CHANCE = 0.70;
    private static final double HONEST_SPEED_MIN = 2.0;
    private static final double HONEST_SPEED_MAX = 7.0;
    private static final double HACK_MOVE_CHANCE = 0.85;
    private static final double HACK_SPEED_MIN = 5.0;
    private static final double HACK_SPEED_MAX = 7.0;

    public static BehaviorProfile honest(RandomGenerator rng) {
        double reactionMean = clamp(240 + 30 * rng.nextGaussian(), 170, 400);
        Aim aim = new Aim(uniform(rng, 0.20, 0.45), uniform(rng, 0.25, 0.55), reactionMean, 35, 80);
        return new BehaviorProfile(aim, honestMove());
    }

    public static BehaviorProfile pro(RandomGenerator rng) {
        Aim aim = new Aim(uniform(rng, 0.40, 0.55), uniform(rng, 0.55, 0.68), uniform(rng, 155, 185), 30, 80);
        return new BehaviorProfile(aim, honestMove());
    }

    /** The profile this player switches to when the cheat starts. Only the cheat's own family changes. */
    public BehaviorProfile asCheater(CheatType type, RandomGenerator rng) {
        return switch (type) {
            case AIMBOT -> new BehaviorProfile(
                    new Aim(uniform(rng, 0.60, 0.90), uniform(rng, 0.75, 0.95), uniform(rng, 40, 80), 10, 20), move);
            case AIMBOT_SUBTLE -> new BehaviorProfile(
                    new Aim(uniform(rng, 0.45, 0.60), uniform(rng, 0.60, 0.70), uniform(rng, 110, 135), 20, 50), move);
            case SPEEDHACK -> new BehaviorProfile(aim, hackMove(uniform(rng, 1.5, 2.0)));
            case SPEEDHACK_SUBTLE -> new BehaviorProfile(aim, hackMove(uniform(rng, 1.12, 1.20)));
            case NONE -> throw new IllegalArgumentException("NONE is not a cheat");
        };
    }

    public SkillTier skillTier() {
        return tierOf(aim.headshotChance());
    }

    /** Below 0.33 BRONZE, below 0.41 SILVER, below 0.48 GOLD, below 0.55 PLATINUM, otherwise PRO. */
    public static SkillTier tierOf(double headshotChance) {
        if (headshotChance < 0.33) {
            return SkillTier.BRONZE;
        }
        if (headshotChance < 0.41) {
            return SkillTier.SILVER;
        }
        if (headshotChance < 0.48) {
            return SkillTier.GOLD;
        }
        if (headshotChance < 0.55) {
            return SkillTier.PLATINUM;
        }
        return SkillTier.PRO;
    }

    private static Move honestMove() {
        return new Move(HONEST_MOVE_CHANCE, HONEST_SPEED_MIN, HONEST_SPEED_MAX, 1.0);
    }

    private static Move hackMove(double multiplier) {
        return new Move(HACK_MOVE_CHANCE, HACK_SPEED_MIN, HACK_SPEED_MAX, multiplier);
    }

    private static double uniform(RandomGenerator rng, double low, double high) {
        return low + rng.nextDouble() * (high - low);
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
