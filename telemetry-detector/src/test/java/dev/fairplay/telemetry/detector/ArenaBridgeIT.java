package dev.fairplay.telemetry.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import dev.fairplay.telemetry.common.Topics;
import dev.fairplay.telemetry.common.json.JsonSerde;
import dev.fairplay.telemetry.common.model.MatchSummary;
import dev.fairplay.telemetry.common.model.ParticipantSummary;
import dev.fairplay.telemetry.common.support.KafkaTestContainer;
import dev.fairplay.telemetry.common.support.StubArenaServer;
import dev.fairplay.telemetry.common.support.StubArenaServer.Response;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The bridge against a real broker and a stub Arena: the retry and dead-letter behavior of spec section 10. The
 * streams client is not started (auto-startup=false), so only the Spring Kafka listener runs.
 * {@code max-retries=2} means 3 attempts in total.
 */
@SpringBootTest(classes = DetectorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.kafka.streams.auto-startup=false",
                "spring.kafka.streams.application-id=bridge-it-${random.uuid}",
                "spring.kafka.streams.properties.state.dir=${java.io.tmpdir}/ks-${random.uuid}",
                "telemetry.arena.enabled=true",
                "telemetry.arena.service-password=test-password",
                "telemetry.arena.max-retries=2",
                "telemetry.arena.initial-backoff=100ms"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArenaBridgeIT {

    private static final String MATCHES = "/api/v1/matches";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final StubArenaServer ARENA = new StubArenaServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainer.KAFKA::getBootstrapServers);
        registry.add("telemetry.arena.base-url", ARENA::baseUrl);
    }

    @AfterAll
    static void stopArena() {
        ARENA.close();
    }

    @Autowired
    MeterRegistry registry;

    @BeforeEach
    void resetArena() {
        ARENA.reset();
    }

    private static String summaryJson(String externalId) {
        return JsonSerde.MAPPER.writeValueAsString(new MatchSummary(externalId, "FFA", "Harbor",
                "2026-11-17T10:00:00Z", "2026-11-17T10:02:00Z",
                List.of(new ParticipantSummary("p1", 1, 3, 1, 1, 10, 5, 200),
                        new ParticipantSummary("p2", 2, 1, 3, 0, 8, 2, 240))));
    }

    private static String send(String json) {
        String externalId = "sim-it-" + UUID.randomUUID();
        try (KafkaProducer<String, byte[]> producer = KafkaIt.producer()) {
            producer.send(new ProducerRecord<>(Topics.MATCH_SUMMARIES, externalId, json.replace("@ID@", externalId)
                    .getBytes(StandardCharsets.UTF_8)));
            producer.flush();
        }
        return externalId;
    }

    private static String sendSummary() {
        return send(summaryJson("@ID@"));
    }

    private static long callsFor(String externalId) {
        return ARENA.requests("POST", MATCHES).stream().filter(r -> r.body().contains(externalId)).count();
    }

    private static List<ConsumerRecord<String, byte[]>> deadLetters(String externalId) {
        return KafkaIt.readTopic(Topics.MATCH_SUMMARIES_DLT).stream().filter(r -> externalId.equals(r.key())).toList();
    }

    private double deadLetteredCount() {
        return registry.counter("telemetry.bridge.dead_lettered").count();
    }

    private static void waitForCalls(String externalId, long calls) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(callsFor(externalId)).isEqualTo(calls));
    }

    @Test
    void a201IsOneCallAndNoDeadLetter() throws Exception {
        ARENA.enqueue("POST", MATCHES, Response.json(201, "{}"));

        String id = sendSummary();

        waitForCalls(id, 1);
        Thread.sleep(1500);
        assertThat(callsFor(id)).isEqualTo(1);
        assertThat(deadLetters(id)).isEmpty();
    }

    @Test
    void twoTemporaryFailuresThenSuccessIsThreeCallsAndNoDeadLetter() throws Exception {
        ARENA.enqueue("POST", MATCHES, Response.json(503, ""), Response.json(503, ""), Response.json(201, "{}"));

        String id = sendSummary();

        waitForCalls(id, 3);
        Thread.sleep(1500);
        assertThat(callsFor(id)).isEqualTo(3);
        assertThat(deadLetters(id)).isEmpty();
    }

    @Test
    void aTemporaryFailureThatNeverEndsIsThreeCallsThenTheDeadLetterTopic() {
        ARENA.enqueue("POST", MATCHES, Response.json(503, ""), Response.json(503, ""), Response.json(503, ""),
                Response.json(503, ""));
        double before = deadLetteredCount();

        String id = sendSummary();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(deadLetters(id)).hasSize(1));
        assertThat(callsFor(id)).isEqualTo(3);
        ConsumerRecord<String, byte[]> letter = deadLetters(id).get(0);
        // The original JSON is kept as it was, and Spring adds its exception headers.
        assertThat(new String(letter.value(), StandardCharsets.UTF_8)).contains("\"externalId\":\"" + id + "\"");
        assertThat(letter.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        assertThat(letter.headers().lastHeader("kafka_dlt-original-offset")).isNotNull();
        assertThat(letter.partition()).isBetween(0, 2);
        assertThat(deadLetteredCount()).isEqualTo(before + 1);
    }

    @Test
    void aPermanentRejectionIsOneCallThenTheDeadLetterTopic() {
        ARENA.enqueue("POST", MATCHES, Response.problem(422, "PLAYER_NOT_ELIGIBLE", "Player is banned"));

        String id = sendSummary();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(deadLetters(id)).hasSize(1));
        assertThat(callsFor(id)).isEqualTo(1);
        assertThat(new String(deadLetters(id).get(0).headers().lastHeader("kafka_dlt-exception-message").value(),
                StandardCharsets.UTF_8)).contains("PLAYER_NOT_ELIGIBLE");
    }

    @Test
    void garbageJsonNeverReachesArenaAndIsDeadLettered() {
        String id = send("{\"broken\":");

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(deadLetters(id)).hasSize(1));
        assertThat(ARENA.requests("POST", MATCHES)).isEmpty();
        assertThat(new String(deadLetters(id).get(0).value(), StandardCharsets.UTF_8)).isEqualTo("{\"broken\":");
    }

    @Test
    void anExpiredTokenIsRefreshedAndTheMatchStillArrives() throws Exception {
        ARENA.enqueue("POST", MATCHES, Response.problem(401, "UNAUTHORIZED", "expired"), Response.json(201, "{}"));

        String id = sendSummary();

        waitForCalls(id, 2);
        Thread.sleep(1500);
        List<StubArenaServer.Request> calls = ARENA.requests("POST", MATCHES);
        assertThat(calls).hasSize(2);
        // The retry carries a freshly issued token, so a login happened in between.
        assertThat(calls.get(1).authorization()).isNotEqualTo(calls.get(0).authorization());
        assertThat(ARENA.requests("POST", LOGIN)).isNotEmpty();
        assertThat(deadLetters(id)).isEmpty();
    }
}
