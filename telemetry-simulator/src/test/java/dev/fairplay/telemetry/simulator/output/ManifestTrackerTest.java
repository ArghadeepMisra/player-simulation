package dev.fairplay.telemetry.simulator.output;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.fairplay.telemetry.common.model.ManifestEntry;
import dev.fairplay.telemetry.simulator.TestSimProps;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import dev.fairplay.telemetry.simulator.population.PopulationFactory;
import org.junit.jupiter.api.Test;

class ManifestTrackerTest {

    private final ManifestTracker tracker = new ManifestTracker();

    @Test
    void unknownPlayerHasNothing() {
        assertThat(tracker.ackedOf("nobody")).isZero();
        assertThat(tracker.failedOf("nobody")).isZero();
        assertThat(tracker.highestAckedSeq("nobody")).isZero();
        assertThat(tracker.totalAcked()).isZero();
        assertThat(tracker.totalFailed()).isZero();
    }

    @Test
    void countsAcksAndFailuresPerPlayer() {
        tracker.acked("a", 1);
        tracker.acked("a", 2);
        tracker.failed("a");
        tracker.acked("b", 1);
        tracker.failed("c");

        assertThat(tracker.ackedOf("a")).isEqualTo(2);
        assertThat(tracker.failedOf("a")).isEqualTo(1);
        assertThat(tracker.ackedOf("b")).isEqualTo(1);
        assertThat(tracker.failedOf("c")).isEqualTo(1);
        assertThat(tracker.totalAcked()).isEqualTo(3);
        assertThat(tracker.totalFailed()).isEqualTo(2);
    }

    @Test
    void highestAckedSeqIgnoresOutOfOrderAcks() {
        tracker.acked("a", 5);
        tracker.acked("a", 3);
        tracker.acked("a", 4);

        assertThat(tracker.highestAckedSeq("a")).isEqualTo(5);
    }

    @Test
    void entriesUseTheGeneratedSeqAsFinalSeq() {
        List<PlayerState> players = new PopulationFactory(TestSimProps.of(42, 40)).create(1_000L);
        PlayerState first = players.get(0);
        PlayerState second = players.get(1);
        first.nextSeq();
        first.nextSeq();
        first.nextSeq();
        tracker.acked(first.playerId(), 1);
        tracker.acked(first.playerId(), 2);
        tracker.failed(first.playerId());

        List<ManifestEntry> entries = tracker.entries("r-test01", players);

        assertThat(entries).hasSize(40);
        assertThat(entries.get(0)).isEqualTo(new ManifestEntry("r-test01", first.playerId(), 3, 2, 1));
        assertThat(entries.get(1)).isEqualTo(new ManifestEntry("r-test01", second.playerId(), 0, 0, 0));
    }
}
