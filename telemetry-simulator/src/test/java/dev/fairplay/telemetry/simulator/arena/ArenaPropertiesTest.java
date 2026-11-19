package dev.fairplay.telemetry.simulator.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ArenaPropertiesTest {

    private static ArenaProperties props(boolean enabled, String servicePassword) {
        return new ArenaProperties(enabled, "http://localhost:8080", "telemetry-sim", servicePassword,
                "sim-player-pass", 16, "./sim-players.json");
    }

    @Test
    void aBlankServicePasswordIsFineWhileArenaIsDisabled() {
        assertThat(props(false, "").enabled()).isFalse();
    }

    @Test
    void aBlankServicePasswordFailsStartupWhenArenaIsEnabled() {
        assertThatThrownBy(() -> props(true, " ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TELEMETRY_SERVICE_PASSWORD");
        assertThatThrownBy(() -> props(true, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOtherBrokenSettings() {
        assertThatThrownBy(() -> new ArenaProperties(false, " ", "u", "p", "sim-player-pass", 1, "f"))
                .hasMessageContaining("base-url");
        assertThatThrownBy(() -> new ArenaProperties(false, "http://x", "u", "p", "sim-player-pass", 0, "f"))
                .hasMessageContaining("register-concurrency");
        assertThatThrownBy(() -> new ArenaProperties(false, "http://x", "u", "p", "sim-player-pass", 1, ""))
                .hasMessageContaining("mapping-file");
        assertThatThrownBy(() -> new ArenaProperties(false, "http://x", "u", "p", "short", 1, "f"))
                .hasMessageContaining("player-password");
    }
}
