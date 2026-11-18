package dev.fairplay.telemetry.simulator.population;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.Segment;
import dev.fairplay.telemetry.common.model.SkillTier;
import dev.fairplay.telemetry.simulator.TestSimProps;
import org.junit.jupiter.api.Test;

class PopulationFactoryTest {

    private static final long RUN_START = 1_763_372_100_000L;

    /** PlayerState holds an AtomicLong, so compare everything else through this projection. */
    private record Fingerprint(String id, String username, String country, Segment segment, CheatType cheat,
                               SkillTier tier, BehaviorProfile base, BehaviorProfile cheatProfile, Long start) {
        static Fingerprint of(PlayerState p) {
            return new Fingerprint(p.playerId(), p.username(), p.country(), p.segment(), p.cheatType(),
                    p.skillTier(), p.base(), p.cheat(), p.cheatStartsAt());
        }
    }

    private static List<Fingerprint> population(long seed) {
        return new PopulationFactory(TestSimProps.of(seed, 1000)).create(RUN_START).stream()
                .map(Fingerprint::of).collect(Collectors.toList());
    }

    @Test
    void idsAndUsernamesFollowTheNamingFormulas() {
        List<PlayerState> players = new PopulationFactory(TestSimProps.defaults()).create(RUN_START);

        assertThat(players).hasSize(1000);
        assertThat(players.get(0).username()).isEqualTo("sim_0001");
        assertThat(players.get(999).username()).isEqualTo("sim_1000");
        assertThat(players.get(7).playerId())
                .isEqualTo(UUID.nameUUIDFromBytes("42:7".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        assertThat(players.stream().map(PlayerState::playerId).distinct()).hasSize(1000);
        assertThat(players).allSatisfy(p -> assertThat(p.country()).matches("[A-Z]{2}"));
    }

    @Test
    void sameSeedGivesTheSamePopulationIncludingProfilesAndStartTimes() {
        assertThat(population(42)).isEqualTo(population(42));
    }

    @Test
    void differentSeedGivesADifferentPopulation() {
        assertThat(population(42)).isNotEqualTo(population(43));
    }

    @Test
    void onlyCheatersHaveACheatProfileAndAStartInsideTheWindow() {
        List<PlayerState> players = new PopulationFactory(TestSimProps.defaults()).create(RUN_START);

        for (PlayerState p : players) {
            if (p.segment() == Segment.CHEATER) {
                assertThat(p.cheat()).isNotNull();
                assertThat(p.cheatStartsAt()).isBetween(RUN_START + 10_000, RUN_START + 60_000);
            } else {
                assertThat(p.cheat()).isNull();
                assertThat(p.cheatStartsAt()).isNull();
            }
        }
    }

    @Test
    void prosAreTierProAndCheatersKeepTheirBaseTier() {
        List<PlayerState> players = new PopulationFactory(TestSimProps.defaults()).create(RUN_START);

        assertThat(players).filteredOn(p -> p.segment() == Segment.PRO)
                .isNotEmpty().allMatch(p -> p.skillTier() == SkillTier.PRO);
        assertThat(players).filteredOn(p -> p.segment() != Segment.PRO)
                .allMatch(p -> p.skillTier() == p.base().skillTier());
    }

    @Test
    void profileSwitchesExactlyAtCheatStart() {
        PlayerState cheater = new PopulationFactory(TestSimProps.defaults()).create(RUN_START).stream()
                .filter(p -> p.cheatType() == CheatType.AIMBOT).findFirst().orElseThrow();
        long start = cheater.cheatStartsAt();

        assertThat(cheater.profileAt(start - 1)).isSameAs(cheater.base());
        assertThat(cheater.profileAt(start)).isSameAs(cheater.cheat());
        assertThat(cheater.isCheatingAt(start - 1)).isFalse();
        assertThat(cheater.isCheatingAt(start)).isTrue();
    }

    @Test
    void honestPlayersNeverSwitch() {
        PlayerState honest = new PopulationFactory(TestSimProps.defaults()).create(RUN_START).stream()
                .filter(p -> p.segment() == Segment.HONEST).findFirst().orElseThrow();

        assertThat(honest.profileAt(Long.MAX_VALUE)).isSameAs(honest.base());
        assertThat(honest.isCheatingAt(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void sequenceNumbersCountUpFromOneWithoutGaps() {
        PlayerState p = new PopulationFactory(TestSimProps.defaults()).create(RUN_START).get(0);

        assertThat(p.lastSeq()).isZero();
        assertThat(p.nextSeq()).isEqualTo(1);
        assertThat(p.nextSeq()).isEqualTo(2);
        assertThat(p.lastSeq()).isEqualTo(2);
    }

    @Test
    void profileAndGroundTruthRecordsMirrorThePlayer() {
        List<PlayerState> players = new PopulationFactory(TestSimProps.defaults()).create(RUN_START);
        PlayerState cheater = players.stream().filter(p -> p.segment() == Segment.CHEATER).findFirst().orElseThrow();
        PlayerState honest = players.stream().filter(p -> p.segment() == Segment.HONEST).findFirst().orElseThrow();

        PlayerProfile profile = PopulationFactory.profileOf(cheater, RUN_START);
        GroundTruth truth = PopulationFactory.truthOf("r-abc123", cheater);
        GroundTruth honestTruth = PopulationFactory.truthOf("r-abc123", honest);

        assertThat(profile.playerId()).isEqualTo(cheater.playerId());
        assertThat(profile.username()).isEqualTo(cheater.username());
        assertThat(profile.skillTier()).isEqualTo(cheater.skillTier());
        assertThat(profile.registeredAt()).isEqualTo(RUN_START);
        assertThat(truth).isEqualTo(new GroundTruth("r-abc123", cheater.playerId(), Segment.CHEATER,
                cheater.cheatType(), cheater.cheatStartsAt()));
        assertThat(honestTruth.cheatType()).isEqualTo(CheatType.NONE);
        assertThat(honestTruth.cheatStartsAt()).isNull();
        assertThat(Objects.requireNonNull(truth.cheatStartsAt())).isGreaterThan(RUN_START);
    }
}
