package dev.fairplay.telemetry.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DetectionPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(DetectionProperties.class)
    static class PropsConfig {
    }

    private static final Duration S1 = Duration.ofSeconds(1);
    private static final Duration S2 = Duration.ofSeconds(2);
    private static final Duration S5 = Duration.ofSeconds(5);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropsConfig.class);

    private static void assertRejected(Supplier<?> create, String messagePart) {
        assertThatThrownBy(create::get).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(messagePart);
    }

    @Test
    void applicationYmlDefaultsBindToTheDocumentedValues() {
        runner.run(ctx -> assertThat(ctx.getBean(DetectionProperties.class)).isEqualTo(TestDetection.defaults()));
    }

    @Test
    void environmentOverridesAreBound() {
        runner.withPropertyValues("telemetry.detection.aimbot.min-hits=20", "telemetry.detection.cooldown=1m")
                .run(ctx -> {
                    DetectionProperties props = ctx.getBean(DetectionProperties.class);
                    assertThat(props.aimbot().minHits()).isEqualTo(20);
                    assertThat(props.cooldown()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void aBrokenRuleFailsStartup() {
        runner.withPropertyValues("telemetry.detection.speedhack.min-overspeed-ratio=1.5")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void rejectsBadTopLevelSettings() {
        DetectionProperties ok = TestDetection.defaults();

        assertRejected(() -> new DetectionProperties(" ", ok.cooldown(), ok.integrityEmitInterval(), ok.aimbot(),
                ok.speedhack()), "detector-version");
        assertRejected(() -> new DetectionProperties(null, ok.cooldown(), ok.integrityEmitInterval(), ok.aimbot(),
                ok.speedhack()), "detector-version");
        assertRejected(() -> new DetectionProperties("1", Duration.ZERO, ok.integrityEmitInterval(), ok.aimbot(),
                ok.speedhack()), "cooldown must be positive");
        assertRejected(() -> new DetectionProperties("1", ok.cooldown(), null, ok.aimbot(), ok.speedhack()),
                "integrity-emit-interval must be positive");
        assertRejected(() -> new DetectionProperties("1", ok.cooldown(), ok.integrityEmitInterval(), null,
                ok.speedhack()), "required");
    }

    @Test
    void rejectsBadAimbotSettings() {
        assertRejected(() -> new DetectionProperties.Aimbot(Duration.ZERO, S1, S2, 12, 140, 0.62), "window-size");
        assertRejected(() -> new DetectionProperties.Aimbot(S5, S1, Duration.ofSeconds(-1), 12, 140, 0.62), "grace");
        assertRejected(() -> new DetectionProperties.Aimbot(S1, S5, S2, 12, 140, 0.62), "must not be greater");
        assertRejected(() -> new DetectionProperties.Aimbot(S5, S1, S2, 0, 140, 0.62), "min-hits");
        assertRejected(() -> new DetectionProperties.Aimbot(S5, S1, S2, 12, 0, 0.62), "max-avg-reaction-ms");
        assertRejected(() -> new DetectionProperties.Aimbot(S5, S1, S2, 12, 140, -0.1), "min-headshot-rate");
        assertRejected(() -> new DetectionProperties.Aimbot(S5, S1, S2, 12, 140, 1.1), "min-headshot-rate");
    }

    @Test
    void rejectsBadSpeedhackSettings() {
        assertRejected(() -> new DetectionProperties.Speedhack(S5, Duration.ZERO, S2, 40, 0.5, 7.5, 0.15), "advance");
        assertRejected(() -> new DetectionProperties.Speedhack(S1, S5, S2, 40, 0.5, 7.5, 0.15), "must not be greater");
        assertRejected(() -> new DetectionProperties.Speedhack(S5, S1, S2, 0, 0.5, 7.5, 0.15), "min-moving-samples");
        assertRejected(() -> new DetectionProperties.Speedhack(S5, S1, S2, 40, 0, 7.5, 0.15), "moving-speed-floor");
        assertRejected(() -> new DetectionProperties.Speedhack(S5, S1, S2, 40, 0.5, 0, 0.15), "speed-limit");
        assertRejected(() -> new DetectionProperties.Speedhack(S5, S1, S2, 40, 0.5, 7.5, 2), "min-overspeed-ratio");
    }

    @Test
    void acceptsTheBoundaryValues() {
        DetectionProperties.Aimbot edge = new DetectionProperties.Aimbot(S1, S1, S1, 1, 1, 0.0);

        assertThat(edge.minHeadshotRate()).isZero();
    }
}
