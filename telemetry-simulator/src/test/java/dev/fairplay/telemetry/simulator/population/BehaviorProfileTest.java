package dev.fairplay.telemetry.simulator.population;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.SplittableRandom;
import java.util.function.Supplier;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.SkillTier;
import org.junit.jupiter.api.Test;

class BehaviorProfileTest {

    private static final int DRAWS = 10_000;

    private final SplittableRandom rng = new SplittableRandom(99);

    private void repeat(Supplier<BehaviorProfile> draw, java.util.function.Consumer<BehaviorProfile> check) {
        for (int i = 0; i < DRAWS; i++) {
            check.accept(draw.get());
        }
    }

    @Test
    void honestDrawsStayInsideTheTable() {
        repeat(() -> BehaviorProfile.honest(rng), p -> {
            assertThat(p.aim().hitChance()).isBetween(0.20, 0.45);
            assertThat(p.aim().headshotChance()).isBetween(0.25, 0.55);
            assertThat(p.aim().reactionMean()).isBetween(170.0, 400.0);
            assertThat(p.aim().reactionSd()).isEqualTo(35);
            assertThat(p.aim().reactionFloor()).isEqualTo(80);
            assertHonestMove(p);
        });
    }

    @Test
    void proDrawsStayInsideTheTable() {
        repeat(() -> BehaviorProfile.pro(rng), p -> {
            assertThat(p.aim().hitChance()).isBetween(0.40, 0.55);
            assertThat(p.aim().headshotChance()).isBetween(0.55, 0.68);
            assertThat(p.aim().reactionMean()).isBetween(155.0, 185.0);
            assertThat(p.aim().reactionSd()).isEqualTo(30);
            assertThat(p.aim().reactionFloor()).isEqualTo(80);
            assertHonestMove(p);
        });
    }

    @Test
    void aimbotDrawsStayInsideTheTableAndKeepTheBaseMovement() {
        BehaviorProfile base = BehaviorProfile.honest(rng);
        repeat(() -> base.asCheater(CheatType.AIMBOT, rng), p -> {
            assertThat(p.aim().hitChance()).isBetween(0.60, 0.90);
            assertThat(p.aim().headshotChance()).isBetween(0.75, 0.95);
            assertThat(p.aim().reactionMean()).isBetween(40.0, 80.0);
            assertThat(p.aim().reactionSd()).isEqualTo(10);
            assertThat(p.aim().reactionFloor()).isEqualTo(20);
            assertThat(p.move()).isEqualTo(base.move());
        });
    }

    @Test
    void subtleAimbotDrawsStayInsideTheTable() {
        BehaviorProfile base = BehaviorProfile.honest(rng);
        repeat(() -> base.asCheater(CheatType.AIMBOT_SUBTLE, rng), p -> {
            assertThat(p.aim().hitChance()).isBetween(0.45, 0.60);
            assertThat(p.aim().headshotChance()).isBetween(0.60, 0.70);
            assertThat(p.aim().reactionMean()).isBetween(110.0, 135.0);
            assertThat(p.aim().reactionSd()).isEqualTo(20);
            assertThat(p.aim().reactionFloor()).isEqualTo(50);
            assertThat(p.move()).isEqualTo(base.move());
        });
    }

    @Test
    void speedhackDrawsStayInsideTheTableAndKeepTheBaseAim() {
        BehaviorProfile base = BehaviorProfile.honest(rng);
        repeat(() -> base.asCheater(CheatType.SPEEDHACK, rng), p -> {
            assertThat(p.move().moveChance()).isEqualTo(0.85);
            assertThat(p.move().speedMin()).isEqualTo(5.0);
            assertThat(p.move().speedMax()).isEqualTo(7.0);
            assertThat(p.move().speedMultiplier()).isBetween(1.5, 2.0);
            assertThat(p.aim()).isEqualTo(base.aim());
        });
    }

    @Test
    void subtleSpeedhackDrawsStayInsideTheTable() {
        BehaviorProfile base = BehaviorProfile.honest(rng);
        repeat(() -> base.asCheater(CheatType.SPEEDHACK_SUBTLE, rng), p -> {
            assertThat(p.move().moveChance()).isEqualTo(0.85);
            assertThat(p.move().speedMultiplier()).isBetween(1.12, 1.20);
            assertThat(p.aim()).isEqualTo(base.aim());
        });
    }

    @Test
    void noneIsNotACheat() {
        BehaviorProfile base = BehaviorProfile.honest(rng);

        assertThatThrownBy(() -> base.asCheater(CheatType.NONE, rng)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skillTierBoundaries() {
        assertThat(BehaviorProfile.tierOf(0.25)).isEqualTo(SkillTier.BRONZE);
        assertThat(BehaviorProfile.tierOf(0.3299)).isEqualTo(SkillTier.BRONZE);
        assertThat(BehaviorProfile.tierOf(0.33)).isEqualTo(SkillTier.SILVER);
        assertThat(BehaviorProfile.tierOf(0.4099)).isEqualTo(SkillTier.SILVER);
        assertThat(BehaviorProfile.tierOf(0.41)).isEqualTo(SkillTier.GOLD);
        assertThat(BehaviorProfile.tierOf(0.4799)).isEqualTo(SkillTier.GOLD);
        assertThat(BehaviorProfile.tierOf(0.48)).isEqualTo(SkillTier.PLATINUM);
        assertThat(BehaviorProfile.tierOf(0.5499)).isEqualTo(SkillTier.PLATINUM);
        assertThat(BehaviorProfile.tierOf(0.55)).isEqualTo(SkillTier.PRO);
        assertThat(BehaviorProfile.tierOf(0.9)).isEqualTo(SkillTier.PRO);
    }

    @Test
    void profileTierFollowsItsHeadshotChance() {
        BehaviorProfile p = new BehaviorProfile(new BehaviorProfile.Aim(0.3, 0.45, 240, 35, 80),
                new BehaviorProfile.Move(0.7, 2, 7, 1));

        assertThat(p.skillTier()).isEqualTo(SkillTier.GOLD);
    }

    private static void assertHonestMove(BehaviorProfile p) {
        assertThat(p.move().moveChance()).isEqualTo(0.70);
        assertThat(p.move().speedMin()).isEqualTo(2.0);
        assertThat(p.move().speedMax()).isEqualTo(7.0);
        assertThat(p.move().speedMultiplier()).isEqualTo(1.0);
    }
}
