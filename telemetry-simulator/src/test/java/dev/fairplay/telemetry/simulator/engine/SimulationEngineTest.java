package dev.fairplay.telemetry.simulator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.simulator.SimulatorProperties;
import dev.fairplay.telemetry.simulator.TestSimProps;
import dev.fairplay.telemetry.simulator.arena.ArenaPlayerRegistrar;
import dev.fairplay.telemetry.simulator.output.EventPublisher;
import dev.fairplay.telemetry.simulator.output.ManifestTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The failure and shutdown paths of the run. The happy path runs against a real broker in SimulatorIT. */
class SimulationEngineTest {

    private final SimulatorProperties props = TestSimProps.of(42, 40);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final SimulationEngine engine =
            new SimulationEngine(props, Clock.systemUTC(), publisher, new ManifestTracker(), new SimpleMeterRegistry(),
                    Optional.empty());

    @Test
    void aFailedSetupSendFailsTheRunInsteadOfLeavingItHanging() {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down"))).when(publisher).publishProfile(any(PlayerProfile.class), anyLong());
        doReturn(CompletableFuture.completedFuture(null)).when(publisher).publishGroundTruth(any(GroundTruth.class), anyLong());

        engine.start();

        assertThatThrownBy(() -> engine.completion().get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("broker down");
    }

    @Test
    void stoppingTheAppInterruptsARunInProgress() throws Exception {
        doReturn(CompletableFuture.completedFuture(null)).when(publisher).publishProfile(any(PlayerProfile.class), anyLong());
        doReturn(CompletableFuture.completedFuture(null)).when(publisher).publishGroundTruth(any(GroundTruth.class), anyLong());

        engine.start();
        // Events flowing means the run is past setup and waiting out its 5 minutes.
        verify(publisher, timeout(10_000).atLeastOnce()).publish(any());
        engine.stop();

        assertThatThrownBy(() -> engine.completion().get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(InterruptedException.class);
        assertThat(engine.completion()).isCompletedExceptionally();
        verify(publisher, atLeastOnce()).publishProfile(any(PlayerProfile.class), anyLong());
    }

    @Test
    void withArenaEnabledEverythingIsPublishedUnderArenasIds() throws Exception {
        ArenaPlayerRegistrar registrar = mock(ArenaPlayerRegistrar.class);
        java.util.Map<String, String> ids = new java.util.HashMap<>();
        for (int i = 1; i <= 40; i++) {
            ids.put("sim_%04d".formatted(i), "arena-%04d".formatted(i));
        }
        org.mockito.Mockito.when(registrar.register(any())).thenReturn(ids);
        SimulationEngine withArena = new SimulationEngine(props, Clock.systemUTC(), publisher, new ManifestTracker(),
                new SimpleMeterRegistry(), Optional.of(registrar));
        // A failing setup send ends the run right after the profiles and ground truth were handed over.
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("stop here")))
                .when(publisher).publishProfile(any(PlayerProfile.class), anyLong());
        doReturn(CompletableFuture.completedFuture(null)).when(publisher).publishGroundTruth(any(GroundTruth.class), anyLong());

        withArena.start();
        assertThatThrownBy(() -> withArena.completion().get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);

        ArgumentCaptor<PlayerProfile> profiles = ArgumentCaptor.forClass(PlayerProfile.class);
        verify(publisher, org.mockito.Mockito.times(40)).publishProfile(profiles.capture(), anyLong());
        assertThat(profiles.getAllValues()).extracting(PlayerProfile::playerId).allMatch(id -> id.startsWith("arena-"));
        ArgumentCaptor<GroundTruth> truth = ArgumentCaptor.forClass(GroundTruth.class);
        verify(publisher, org.mockito.Mockito.times(40)).publishGroundTruth(truth.capture(), anyLong());
        assertThat(truth.getAllValues()).extracting(GroundTruth::playerId).allMatch(id -> id.startsWith("arena-"));
    }

    @Test
    void stopBeforeStartIsHarmless() {
        engine.stop();

        assertThat(engine.completion()).isNotDone();
    }
}
