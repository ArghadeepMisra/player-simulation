package dev.fairplay.telemetry.simulator.population;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.fairplay.telemetry.simulator.TestSimProps;
import org.junit.jupiter.api.Test;

class PlayerStateTest {

    @Test
    void withPlayerIdKeepsEverythingButTheIdAndStartsANewSequence() {
        List<PlayerState> population = new PopulationFactory(TestSimProps.of(42, 40)).create(1000);
        PlayerState original = population.stream().filter(p -> p.cheat() != null).findFirst().orElseThrow();
        original.nextSeq();
        original.nextSeq();

        PlayerState moved = original.withPlayerId("arena-id");

        assertThat(moved.playerId()).isEqualTo("arena-id");
        assertThat(moved.username()).isEqualTo(original.username());
        assertThat(moved.country()).isEqualTo(original.country());
        assertThat(moved.segment()).isEqualTo(original.segment());
        assertThat(moved.cheatType()).isEqualTo(original.cheatType());
        assertThat(moved.cheatStartsAt()).isEqualTo(original.cheatStartsAt());
        assertThat(moved.base()).isSameAs(original.base());
        assertThat(moved.cheat()).isSameAs(original.cheat());
        assertThat(moved.lastSeq()).isZero();
        assertThat(original.lastSeq()).isEqualTo(2);
    }
}
