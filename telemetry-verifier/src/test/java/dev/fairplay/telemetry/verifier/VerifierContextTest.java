package dev.fairplay.telemetry.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The whole application context, the way the jar starts it. It needs no broker: the reader connects only when a
 * mode reads a topic, and a bad {@code --mode} stops before that. This is the test that catches wiring mistakes
 * (for example a bean with two constructors) which the plain unit tests, building classes by hand, cannot see.
 */
@SpringBootTest(args = "--mode=nonsense", properties = "telemetry.verifier.reports-dir=target/test-reports")
class VerifierContextTest {

    @Autowired
    VerifierRunner runner;

    @Test
    void theContextStartsAndTheRunnerRanWithTheCommandLineArguments() {
        assertThat(runner.getExitCode()).isEqualTo(VerifierRunner.EXIT_USAGE);
    }
}
