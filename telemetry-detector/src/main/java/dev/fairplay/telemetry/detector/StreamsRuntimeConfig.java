package dev.fairplay.telemetry.detector;

import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.StreamsBuilderFactoryBeanConfigurer;

/**
 * Runtime behavior of the streams client: a thread that dies is replaced instead of taking the instance down, and
 * every state change is logged (the timestamps are a second source for the failover time, next to chaos-kill.sh).
 */
@Configuration
class StreamsRuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(StreamsRuntimeConfig.class);

    @Bean
    StreamsBuilderFactoryBeanConfigurer streamsRuntimeConfigurer() {
        return factoryBean -> {
            factoryBean.setStreamsUncaughtExceptionHandler(exception -> {
                log.error("Stream thread died, replacing it", exception);
                return StreamThreadExceptionResponse.REPLACE_THREAD;
            });
            factoryBean.setStateListener((newState, oldState) ->
                    log.info("Kafka Streams state transition from {} to {}", oldState, newState));
        };
    }
}
