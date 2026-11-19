package dev.fairplay.telemetry.detector.bridge;

import dev.fairplay.telemetry.common.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Retries temporary Arena failures with exponential backoff and dead-letters permanent ones. Boot applies a single
 * {@code CommonErrorHandler} bean to its default listener container factory automatically. With the defaults a
 * failing record is tried 4 times (the first attempt plus 3 retries after 1 s, 2 s and 4 s), then dead-lettered.
 * The DLT has the same 3 partitions as the source, because the recoverer writes to the same partition number.
 */
@Configuration
class BridgeErrorHandlingConfig {

    @Bean
    DefaultErrorHandler bridgeErrorHandler(KafkaTemplate<String, String> template, ArenaProperties arena,
                                           MeterRegistry registry) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(Topics.MATCH_SUMMARIES_DLT, rec.partition()));
        Counter deadLettered = registry.counter("telemetry.bridge.dead_lettered");
        ConsumerRecordRecoverer counting = (rec, ex) -> {
            deadLettered.increment();
            dlt.accept(rec, ex);
        };

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(arena.maxRetries());
        backOff.setInitialInterval(arena.initialBackoff().toMillis());
        backOff.setMultiplier(arena.backoffMultiplier());

        DefaultErrorHandler handler = new DefaultErrorHandler(counting, backOff);
        handler.addNotRetryableExceptions(NonRetryableArenaException.class);
        return handler;
    }
}
