package dev.fairplay.telemetry.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class VerifierPropertiesTest {

    @Test
    void acceptsValidValues() {
        VerifierProperties props = new VerifierProperties("./reports", Duration.ofSeconds(30), Duration.ofSeconds(5));

        assertThat(props.reportsDir()).isEqualTo("./reports");
    }

    @Test
    void rejectsABlankReportsDirectory() {
        assertThatThrownBy(() -> new VerifierProperties(" ", Duration.ofSeconds(30), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reports-dir");
    }

    @Test
    void rejectsNonPositiveDurations() {
        assertThatThrownBy(() -> new VerifierProperties("r", Duration.ZERO, Duration.ofSeconds(5)))
                .hasMessageContaining("read-timeout");
        assertThatThrownBy(() -> new VerifierProperties("r", Duration.ofSeconds(1), Duration.ofSeconds(-1)))
                .hasMessageContaining("settle-poll-interval");
        assertThatThrownBy(() -> new VerifierProperties("r", null, Duration.ofSeconds(1)))
                .hasMessageContaining("read-timeout");
    }
}
