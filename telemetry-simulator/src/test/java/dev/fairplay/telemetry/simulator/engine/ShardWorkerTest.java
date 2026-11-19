package dev.fairplay.telemetry.simulator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dev.fairplay.telemetry.common.model.EventType;
import dev.fairplay.telemetry.common.model.MatchEvent;
import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.support.MutableClock;
import dev.fairplay.telemetry.simulator.SimulatorProperties;
import dev.fairplay.telemetry.simulator.TestSimProps;
import dev.fairplay.telemetry.simulator.output.EventPublisher;
import dev.fairplay.telemetry.simulator.population.PlayerState;
import dev.fairplay.telemetry.simulator.population.PopulationFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ShardWorkerTest {

    private static final String RUN_ID = "r-test01";
    private static final long START = 1_763_372_100_000L;

    private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(START));
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final AtomicInteger matchCounter = new AtomicInteger();

    private ShardWorker worker(SimulatorProperties props, List<PlayerState> players) {
        return new ShardWorker(0, players, props, RUN_ID, clock, matchCounter, publisher);
    }

    @Test
    void partitionDealsRoomsToShardsRoundRobin() {
        SimulatorProperties props = TestSimProps.of(42, 80);
        List<PlayerState> population = new PopulationFactory(props).create(START);

        List<List<PlayerState>> shards = ShardWorker.partition(population, props);

        assertThat(shards).hasSize(4);
        assertThat(shards).allSatisfy(shard -> assertThat(shard).hasSize(20));
        // Shard 0 owns rooms 0 and 4, that is players 0..9 and 40..49.
        assertThat(shards.get(0).subList(0, 10)).isEqualTo(population.subList(0, 10));
        assertThat(shards.get(0).subList(10, 20)).isEqualTo(population.subList(40, 50));
        Set<String> everyone = new HashSet<>();
        shards.forEach(shard -> shard.forEach(p -> everyone.add(p.playerId())));
        assertThat(everyone).hasSize(80);
    }

    @Test
    void aTickEmitsAtLeastOnePositionForEveryPlayerWithGaplessSeqs() {
        SimulatorProperties props = TestSimProps.of(42, 40);
        List<PlayerState> shard = ShardWorker.partition(new PopulationFactory(props).create(START), props).get(0);
        ShardWorker worker = worker(props, shard);

        for (int i = 0; i < 20; i++) {
            clock.advance(Duration.ofMillis(50));
            worker.tick();
        }

        ArgumentCaptor<MatchEvent> events = ArgumentCaptor.forClass(MatchEvent.class);
        verify(publisher, atLeastOnce()).publish(events.capture());
        for (PlayerState player : shard) {
            List<Long> seqs = events.getAllValues().stream()
                    .filter(e -> e.playerId().equals(player.playerId())).map(MatchEvent::seq).toList();
            assertThat(seqs).as("seqs of %s", player.username()).isNotEmpty();
            assertThat(seqs).isEqualTo(java.util.stream.LongStream.rangeClosed(1, seqs.size()).boxed().toList());
            assertThat(player.lastSeq()).isEqualTo(seqs.size());
        }
        assertThat(events.getAllValues().stream().filter(e -> e.type() == EventType.POSITION).count())
                .isEqualTo(20L * shard.size());
        verify(publisher, never()).publishSummary(any());
    }

    @Test
    void aFinishedMatchIsPublishedAndTheShardMovesOnToNewRooms() {
        SimulatorProperties props = TestSimProps.of(42, 40);
        List<PlayerState> shard = ShardWorker.partition(new PopulationFactory(props).create(START), props).get(0);
        ShardWorker worker = worker(props, shard);
        String firstMatch = worker.rooms().get(0).externalId();
        assertThat(firstMatch).isEqualTo("sim-r-test01-000001");

        worker.tick();
        clock.advance(Duration.ofSeconds(121));
        worker.tick();

        ArgumentCaptor<MatchSummary> summary = ArgumentCaptor.forClass(MatchSummary.class);
        verify(publisher).publishSummary(summary.capture());
        assertThat(summary.getValue().externalId()).isEqualTo(firstMatch);
        assertThat(summary.getValue().participants()).hasSize(10);
        assertThat(worker.rooms()).hasSize(1);
        assertThat(worker.rooms().get(0).externalId()).isEqualTo("sim-r-test01-000002");
        assertThat(worker.rooms().get(0).members()).containsExactlyInAnyOrderElementsOf(shard);
    }

    @Test
    void everyEventCanBeFollowedByABadRecordForTheSamePlayer() {
        SimulatorProperties props = TestSimProps.of(42, 40, 1.0);
        List<PlayerState> shard = ShardWorker.partition(new PopulationFactory(props).create(START), props).get(0);
        ShardWorker worker = worker(props, shard);

        worker.tick();

        ArgumentCaptor<MatchEvent> events = ArgumentCaptor.forClass(MatchEvent.class);
        verify(publisher, atLeastOnce()).publish(events.capture());
        verify(publisher, org.mockito.Mockito.times(events.getAllValues().size()))
                .publishMalformed(anyString(), any(byte[].class));
    }

    @Test
    void noBadRecordsWhenTheRatioIsZero() {
        SimulatorProperties props = TestSimProps.of(42, 40, 0.0);
        List<PlayerState> shard = ShardWorker.partition(new PopulationFactory(props).create(START), props).get(0);

        worker(props, shard).tick();

        verify(publisher, never()).publishMalformed(anyString(), any(byte[].class));
    }

    @Test
    void aFailingTickIsLoggedAndDoesNotEscapeSoTheScheduleSurvives() {
        SimulatorProperties props = TestSimProps.of(42, 40);
        List<PlayerState> shard = ShardWorker.partition(new PopulationFactory(props).create(START), props).get(0);
        doThrow(new IllegalStateException("boom")).when(publisher).publish(any());
        ShardWorker worker = worker(props, shard);

        worker.run();
        worker.run();

        verify(publisher, org.mockito.Mockito.times(2)).publish(any());
    }
}
