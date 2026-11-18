package dev.fairplay.telemetry.detector;

import org.springframework.boot.SpringApplication;
import dev.fairplay.telemetry.common.TopicsConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafkaStreams;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafkaStreams
@Import(TopicsConfig.class)
public class DetectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DetectorApplication.class, args);
    }
}
