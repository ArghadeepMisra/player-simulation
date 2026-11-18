package dev.fairplay.telemetry.detector;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** The one source of "now" in the detector. Tests replace it with a mutable clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
