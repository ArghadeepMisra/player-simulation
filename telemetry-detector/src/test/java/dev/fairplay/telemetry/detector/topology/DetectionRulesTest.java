package dev.fairplay.telemetry.detector.topology;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fairplay.telemetry.common.model.Evidence;
import dev.fairplay.telemetry.detector.TestDetection;
import org.junit.jupiter.api.Test;

class DetectionRulesTest {

    private final DetectionRules rules = new DetectionRules(TestDetection.defaults());

    /** An aimbot window with the given counts and an exact average reaction time. */
    private static AimStats aim(int hits, int headshotHits, int shots, long avgReactionMs) {
        return new AimStats(shots, hits, headshotHits, avgReactionMs * shots);
    }

    @Test
    void aimbotNeedsTwelveHitsInclusive() {
        assertThat(rules.aimbotTriggered(aim(11, 11, 11, 60))).isFalse();
        assertThat(rules.aimbotTriggered(aim(12, 12, 12, 60))).isTrue();
        assertThat(rules.aimbotTriggered(aim(13, 13, 13, 60))).isTrue();
    }

    @Test
    void aimbotReactionBoundaryIs140Inclusive() {
        assertThat(rules.aimbotTriggered(aim(20, 20, 20, 140))).isTrue();
        assertThat(rules.aimbotTriggered(aim(20, 20, 20, 141))).isFalse();
    }

    @Test
    void aimbotHeadshotRateBoundaryIs062Inclusive() {
        // 31 of 50 hits is exactly 0.62, 30 of 50 is 0.60.
        assertThat(rules.aimbotTriggered(aim(50, 31, 50, 60))).isTrue();
        assertThat(rules.aimbotTriggered(aim(50, 30, 50, 60))).isFalse();
    }

    @Test
    void aimbotNeedsAllThreeConditionsAtOnce() {
        assertThat(rules.aimbotTriggered(aim(11, 11, 11, 141))).isFalse();
        assertThat(rules.aimbotTriggered(aim(50, 30, 50, 141))).isFalse();
        assertThat(rules.aimbotTriggered(aim(12, 0, 12, 60))).isFalse();
    }

    @Test
    void aimbotScoreIsHalfAtTheBoundary() {
        // Headshot rate 0.62 and reaction 140 are both exactly on the line.
        assertThat(rules.aimbotScore(aim(50, 31, 50, 140))).isEqualTo(0.5);
    }

    @Test
    void aimbotScoreIsOneWhenFarOver() {
        // Rate 0.95 or more and reaction 30 or less are both at the top of the range.
        assertThat(rules.aimbotScore(aim(100, 100, 100, 30))).isEqualTo(1.0);
        assertThat(rules.aimbotScore(aim(100, 100, 100, 10))).isEqualTo(1.0);
    }

    @Test
    void aimbotScoreFollowsTheFormula() {
        // rate 0.8235 -> (0.8235 - 0.62) / 0.33 = 0.6167; reaction 61.4 -> (140 - 61.4) / 110 = 0.7145
        AimStats stats = new AimStats(23, 17, 14, Math.round(61.4 * 23));
        double rate = 14 / 17.0;
        double reaction = Math.round(61.4 * 23) / 23.0;
        double expected = 0.5 + 0.25 * ((rate - 0.62) / 0.33) + 0.25 * ((140 - reaction) / 110);

        assertThat(rules.aimbotScore(stats)).isEqualTo(Math.round(expected * 1000) / 1000.0);
    }

    @Test
    void speedhackNeedsFortyMovingSamplesInclusive() {
        assertThat(rules.speedhackTriggered(new SpeedStats(39, 39, 11))).isFalse();
        assertThat(rules.speedhackTriggered(new SpeedStats(40, 40, 11))).isTrue();
    }

    @Test
    void speedhackOverspeedRatioBoundaryIs015Inclusive() {
        // 6 of 40 is exactly 0.15, 5 of 40 is 0.125.
        assertThat(rules.speedhackTriggered(new SpeedStats(40, 6, 8))).isTrue();
        assertThat(rules.speedhackTriggered(new SpeedStats(40, 5, 8))).isFalse();
    }

    @Test
    void speedhackScoreIsHalfAtTheBoundaryAndOneWhenEverySampleIsOverspeed() {
        assertThat(rules.speedhackScore(new SpeedStats(40, 6, 8))).isEqualTo(0.5);
        assertThat(rules.speedhackScore(new SpeedStats(60, 60, 11))).isEqualTo(1.0);
    }

    @Test
    void speedhackScoreFollowsTheFormula() {
        // ratio 0.5 -> 0.5 + 0.5 x (0.5 - 0.15) / 0.85 = 0.70588 -> 0.706
        assertThat(rules.speedhackScore(new SpeedStats(100, 50, 9))).isEqualTo(0.706);
    }

    @Test
    void evidenceRatesAreRoundedToFourDecimalsHalfUp() {
        Evidence aimEvidence = rules.aimbotEvidence(new AimStats(23, 17, 14, 1412)); // 14/17 = 0.82352..., 1412/23 = 61.3913...

        assertThat(aimEvidence.shots()).isEqualTo(23);
        assertThat(aimEvidence.hits()).isEqualTo(17);
        assertThat(aimEvidence.headshotHits()).isEqualTo(14);
        assertThat(aimEvidence.headshotRate()).isEqualTo(0.8235);
        assertThat(aimEvidence.avgReactionMs()).isEqualTo(61.3913);

        Evidence speedEvidence = rules.speedhackEvidence(new SpeedStats(3, 1, 10.123456));

        assertThat(speedEvidence.movingSamples()).isEqualTo(3);
        assertThat(speedEvidence.overspeedSamples()).isEqualTo(1);
        assertThat(speedEvidence.overspeedRatio()).isEqualTo(0.3333);
        assertThat(speedEvidence.maxSpeed()).isEqualTo(10.1235);
    }

    @Test
    void roundingIsHalfUp() {
        assertThat(DetectionRules.round(0.12345, 4)).isEqualTo(0.1235);
        assertThat(DetectionRules.round(0.2225, 3)).isEqualTo(0.223);
        assertThat(DetectionRules.round(2.5, 0)).isEqualTo(3.0);
    }

    @Test
    void clampKeepsValuesInZeroToOne() {
        assertThat(DetectionRules.clamp(-3)).isZero();
        assertThat(DetectionRules.clamp(0.4)).isEqualTo(0.4);
        assertThat(DetectionRules.clamp(7)).isEqualTo(1.0);
    }

    @Test
    void exposesTheSpeedSettingsForTheAggregator() {
        assertThat(rules.movingSpeedFloor()).isEqualTo(0.5);
        assertThat(rules.speedLimit()).isEqualTo(7.5);
    }
}
