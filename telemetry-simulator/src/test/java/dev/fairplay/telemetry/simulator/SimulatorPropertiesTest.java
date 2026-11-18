package dev.fairplay.telemetry.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SimulatorPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(SimulatorProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropsConfig.class);

    @Test
    void applicationYmlDefaultsBindAndPassValidation() {
        runner.run(ctx -> {
            SimulatorProperties props = ctx.getBean(SimulatorProperties.class);

            assertThat(props.seed()).isEqualTo(42);
            assertThat(props.players()).isEqualTo(1000);
            assertThat(props.duration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(props.matchSize()).isEqualTo(10);
            assertThat(props.matchDuration()).isEqualTo(Duration.ofSeconds(120));
            assertThat(props.tickHz()).isEqualTo(20);
            assertThat(props.shotsPerSecond()).isEqualTo(4.5);
            assertThat(props.killChancePerHit()).isEqualTo(0.25);
            assertThat(props.shardThreads()).isEqualTo(4);
            assertThat(props.cheaterRatio()).isEqualTo(0.05);
            assertThat(props.proRatio()).isEqualTo(0.02);
            assertThat(props.cheatStartMin()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.cheatStartMax()).isEqualTo(Duration.ofSeconds(60));
            assertThat(props.malformedRatio()).isEqualTo(0.001);
            assertThat(props.tickMs()).isEqualTo(50);
        });
    }

    @Test
    void environmentOverridesAreBound() {
        runner.withPropertyValues("telemetry.sim.players=40", "telemetry.sim.seed=7", "telemetry.sim.duration=90s")
                .run(ctx -> {
                    SimulatorProperties props = ctx.getBean(SimulatorProperties.class);

                    assertThat(props.players()).isEqualTo(40);
                    assertThat(props.seed()).isEqualTo(7);
                    assertThat(props.duration()).isEqualTo(Duration.ofSeconds(90));
                });
    }

    @Test
    void startupFailsWhenPlayersIsNotAMultipleOfMatchSizeTimesShards() {
        runner.withPropertyValues("telemetry.sim.players=1001").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause().hasMessageContaining("multiple of match-size x shard-threads (40)");
        });
    }

    private static void assertRejected(UnaryOperator<Builder> change, String messagePart) {
        assertThatThrownBy(() -> change.apply(new Builder()).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(messagePart);
    }

    @Test
    void rejectsNonPositiveCountsRatesAndDurations() {
        assertRejected(b -> b.players(0), "players must be positive");
        assertRejected(b -> b.duration(Duration.ZERO), "duration must be positive");
        assertRejected(b -> b.duration(null), "duration must be positive");
        assertRejected(b -> b.matchDuration(Duration.ofSeconds(-1)), "match-duration must be positive");
        assertRejected(b -> b.tickHz(0), "tick-hz must be positive");
        assertRejected(b -> b.shotsPerSecond(0), "shots-per-second must be positive");
        assertRejected(b -> b.killChancePerHit(0), "kill-chance-per-hit must be positive");
        assertRejected(b -> b.shardThreads(0), "shard-threads must be positive");
        assertRejected(b -> b.cheatStartMin(Duration.ZERO), "cheat-start-min must be positive");
    }

    @Test
    void rejectsRatiosOutsideZeroToOne() {
        assertRejected(b -> b.cheaterRatio(-0.1), "cheater-ratio must be between 0.0 and 1.0");
        assertRejected(b -> b.cheaterRatio(1.1), "cheater-ratio must be between 0.0 and 1.0");
        assertRejected(b -> b.proRatio(1.5), "pro-ratio must be between 0.0 and 1.0");
        assertRejected(b -> b.malformedRatio(2), "malformed-ratio must be between 0.0 and 1.0");
        assertRejected(b -> b.killChancePerHit(1.5), "kill-chance-per-hit must be between 0.0 and 1.0");
        assertRejected(b -> b.cheaterRatio(0.7).proRatio(0.5), "must not exceed 1.0");
    }

    @Test
    void rejectsBadCheatStartWindow() {
        assertRejected(b -> b.cheatStartMin(Duration.ofSeconds(60)).cheatStartMax(Duration.ofSeconds(60)),
                "cheat-start-max must be greater than cheat-start-min");
        assertRejected(b -> b.cheatStartMax(Duration.ofMinutes(5)), "cheat-start-max must be less than duration");
    }

    @Test
    void rejectsUnusableMatchAndTickSettings() {
        assertRejected(b -> b.matchSize(1), "match-size must be at least 2");
        assertRejected(b -> b.tickHz(30), "tick-hz must divide 1000");
    }

    @Test
    void acceptsTheBoundaryValues() {
        SimulatorProperties props = new Builder().cheaterRatio(0).proRatio(0).malformedRatio(1).build();

        assertThat(props.cheaterRatio()).isZero();
        assertThat(props.malformedRatio()).isEqualTo(1.0);
    }

    /** Starts from the default values so each test changes only the field under test. */
    private static final class Builder {
        private long seed = 42;
        private int players = 1000;
        private Duration duration = Duration.ofMinutes(5);
        private int matchSize = 10;
        private Duration matchDuration = Duration.ofSeconds(120);
        private int tickHz = 20;
        private double shotsPerSecond = 4.5;
        private double killChancePerHit = 0.25;
        private int shardThreads = 4;
        private double cheaterRatio = 0.05;
        private double proRatio = 0.02;
        private Duration cheatStartMin = Duration.ofSeconds(10);
        private Duration cheatStartMax = Duration.ofSeconds(60);
        private double malformedRatio = 0.001;

        Builder players(int v) { players = v; return this; }
        Builder duration(Duration v) { duration = v; return this; }
        Builder matchSize(int v) { matchSize = v; return this; }
        Builder matchDuration(Duration v) { matchDuration = v; return this; }
        Builder tickHz(int v) { tickHz = v; return this; }
        Builder shotsPerSecond(double v) { shotsPerSecond = v; return this; }
        Builder killChancePerHit(double v) { killChancePerHit = v; return this; }
        Builder shardThreads(int v) { shardThreads = v; return this; }
        Builder cheaterRatio(double v) { cheaterRatio = v; return this; }
        Builder proRatio(double v) { proRatio = v; return this; }
        Builder cheatStartMin(Duration v) { cheatStartMin = v; return this; }
        Builder cheatStartMax(Duration v) { cheatStartMax = v; return this; }
        Builder malformedRatio(double v) { malformedRatio = v; return this; }

        SimulatorProperties build() {
            return new SimulatorProperties(seed, players, duration, matchSize, matchDuration, tickHz,
                    shotsPerSecond, killChancePerHit, shardThreads, cheaterRatio, proRatio, cheatStartMin,
                    cheatStartMax, malformedRatio);
        }
    }
}
