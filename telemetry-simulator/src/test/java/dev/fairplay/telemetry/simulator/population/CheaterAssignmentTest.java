package dev.fairplay.telemetry.simulator.population;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.Segment;
import org.junit.jupiter.api.Test;

class CheaterAssignmentTest {

    private static Map<CheatType, Long> cheatCounts(List<CheaterAssignment.Role> roles) {
        return roles.stream().collect(Collectors.groupingBy(CheaterAssignment.Role::cheatType, Collectors.counting()));
    }

    private static long count(List<CheaterAssignment.Role> roles, Segment segment) {
        return roles.stream().filter(r -> r.segment() == segment).count();
    }

    @Test
    void thousandPlayersGiveTheExpectedCounts() {
        List<CheaterAssignment.Role> roles = CheaterAssignment.assign(1000, 0.05, 0.02, 42);

        assertThat(roles).hasSize(1000);
        assertThat(cheatCounts(roles)).containsEntry(CheatType.AIMBOT, 20L)
                .containsEntry(CheatType.AIMBOT_SUBTLE, 10L)
                .containsEntry(CheatType.SPEEDHACK, 12L)
                .containsEntry(CheatType.SPEEDHACK_SUBTLE, 8L)
                .containsEntry(CheatType.NONE, 950L);
        assertThat(count(roles, Segment.CHEATER)).isEqualTo(50);
        assertThat(count(roles, Segment.PRO)).isEqualTo(20);
        assertThat(count(roles, Segment.HONEST)).isEqualTo(930);
    }

    @Test
    void sameSeedGivesTheSameAssignment() {
        assertThat(CheaterAssignment.assign(1000, 0.05, 0.02, 42))
                .isEqualTo(CheaterAssignment.assign(1000, 0.05, 0.02, 42));
    }

    @Test
    void differentSeedGivesADifferentAssignment() {
        assertThat(CheaterAssignment.assign(1000, 0.05, 0.02, 42))
                .isNotEqualTo(CheaterAssignment.assign(1000, 0.05, 0.02, 43));
    }

    @Test
    void roundingRemainderGoesToAimbot() {
        // round(130 x 0.05) = 7 cheaters: 1 subtle aimbot, 1 speedhack, 1 subtle speedhack, and 4 aimbots.
        List<CheaterAssignment.Role> roles = CheaterAssignment.assign(130, 0.05, 0.0, 1);

        assertThat(cheatCounts(roles)).containsEntry(CheatType.AIMBOT, 4L)
                .containsEntry(CheatType.AIMBOT_SUBTLE, 1L)
                .containsEntry(CheatType.SPEEDHACK, 1L)
                .containsEntry(CheatType.SPEEDHACK_SUBTLE, 1L);
        assertThat(count(roles, Segment.CHEATER)).isEqualTo(7);
    }

    @Test
    void zeroRatiosGiveOnlyHonestPlayers() {
        List<CheaterAssignment.Role> roles = CheaterAssignment.assign(40, 0.0, 0.0, 5);

        assertThat(count(roles, Segment.HONEST)).isEqualTo(40);
    }

    @Test
    void honestPlayersAndProsHaveNoCheatType() {
        assertThat(CheaterAssignment.assign(1000, 0.05, 0.02, 42))
                .filteredOn(r -> r.segment() != Segment.CHEATER)
                .allMatch(r -> r.cheatType() == CheatType.NONE);
    }
}
