package dev.fairplay.telemetry.simulator;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every simulator tunable, bound from {@code telemetry.sim.*}. Startup fails when a validation rule is broken.
 *
 * <p>All rules are checked in the compact constructor instead of with Bean Validation annotations.
 * Hibernate Validator has no {@code @Positive} for {@link Duration}, and the cross-field rules need a constructor
 * anyway, so one mechanism keeps the rules in one place.
 */
@ConfigurationProperties("telemetry.sim")
public record SimulatorProperties(
        long seed,
        int players,
        Duration duration,
        int matchSize,
        Duration matchDuration,
        int tickHz,
        double shotsPerSecond,
        double killChancePerHit,
        int shardThreads,
        double cheaterRatio,
        double proRatio,
        Duration cheatStartMin,
        Duration cheatStartMax,
        double malformedRatio) {

    public SimulatorProperties {
        positive(players, "players");
        positive(duration, "duration");
        // A match needs two players, because every kill needs a victim other than the killer.
        if (matchSize < 2) {
            throw invalid("match-size must be at least 2");
        }
        positive(matchDuration, "match-duration");
        positive(tickHz, "tick-hz");
        // dtMs = 1000 / tick-hz must be a whole number of milliseconds.
        if (1000 % tickHz != 0) {
            throw invalid("tick-hz must divide 1000 so a tick is a whole number of milliseconds");
        }
        positive(shotsPerSecond, "shots-per-second");
        // A probability that must be above zero, or no kill would ever happen.
        positive(killChancePerHit, "kill-chance-per-hit");
        ratio(killChancePerHit, "kill-chance-per-hit");
        positive(shardThreads, "shard-threads");
        ratio(cheaterRatio, "cheater-ratio");
        ratio(proRatio, "pro-ratio");
        ratio(malformedRatio, "malformed-ratio");
        if (cheaterRatio + proRatio > 1.0) {
            throw invalid("cheater-ratio + pro-ratio must not exceed 1.0");
        }
        positive(cheatStartMin, "cheat-start-min");
        positive(cheatStartMax, "cheat-start-max");
        if (cheatStartMax.compareTo(cheatStartMin) <= 0) {
            throw invalid("cheat-start-max must be greater than cheat-start-min");
        }
        if (cheatStartMax.compareTo(duration) >= 0) {
            throw invalid("cheat-start-max must be less than duration");
        }
        int roomsPerRound = matchSize * shardThreads;
        if (players % roomsPerRound != 0) {
            throw invalid("players (" + players + ") must be a multiple of match-size x shard-threads ("
                    + roomsPerRound + ")");
        }
    }

    /** Milliseconds per tick; also the {@code dtMs} of every POSITION event (1000 / tick-hz). */
    public int tickMs() {
        return 1000 / tickHz;
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(name + " must be positive");
        }
    }

    private static void positive(double value, String name) {
        if (!(value > 0)) {
            throw invalid(name + " must be positive");
        }
    }

    private static void ratio(double value, String name) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw invalid(name + " must be between 0.0 and 1.0");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("telemetry.sim." + message);
    }
}
