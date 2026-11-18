package dev.fairplay.telemetry.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelHelpersTest {

    @Test
    void deadLetterCapsTheErrorAt500Characters() {
        DeadLetter letter = new DeadLetter("match-events", 0, 1, "k", "e30=", "x".repeat(900), 1);

        assertThat(letter.error()).hasSize(500);
    }

    @Test
    void deadLetterKeepsShortAndNullErrors() {
        assertThat(new DeadLetter("t", 0, 1, "k", "e30=", "short", 1).error()).isEqualTo("short");
        assertThat(new DeadLetter("t", 0, 1, "k", "e30=", null, 1).error()).isNull();
        assertThat(new DeadLetter("t", 0, 1, "k", "e30=", "y".repeat(500), 1).error()).hasSize(500);
    }

    @Test
    void integrityStatsStartFromZeros() {
        IntegrityStats empty = IntegrityStats.empty("p");

        assertThat(empty).isEqualTo(new IntegrityStats("p", 0, 0, 0, 0, 0));
    }

    @Test
    void evidenceFactoriesFillOnlyTheirOwnFields() {
        Evidence aim = Evidence.aimbot(23, 17, 14, 0.8235, 61.4);
        Evidence speed = Evidence.speedhack(50, 20, 0.4, 11.0);

        assertThat(aim.shots()).isEqualTo(23);
        assertThat(aim.movingSamples()).isNull();
        assertThat(speed.maxSpeed()).isEqualTo(11.0);
        assertThat(speed.shots()).isNull();
    }
}
