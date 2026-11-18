package dev.fairplay.telemetry.simulator.engine;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Decides when to send a deliberately bad record next to a real event, and what it looks like. Three kinds rotate:
 * truncated JSON, valid JSON that is missing required fields, and 16 random bytes. Bad records carry no seq and are
 * not counted in the manifest.
 */
public final class MalformedInjector {

    static final byte[] TRUNCATED_JSON = "{\"broken\":".getBytes(UTF_8);
    static final byte[] MISSING_FIELDS = "{\"playerId\":\"not-a-uuid\",\"type\":\"SHOT\"}".getBytes(UTF_8);
    static final int KINDS = 3;
    static final int RANDOM_BYTES = 16;

    private final double ratio;
    private int nextKind;

    public MalformedInjector(double ratio) {
        this.ratio = ratio;
    }

    /** With probability {@code malformed-ratio}, the next bad payload in the rotation, otherwise empty. */
    public Optional<byte[]> maybeNext(RandomGenerator rng) {
        if (rng.nextDouble() >= ratio) {
            return Optional.empty();
        }
        return Optional.of(payload(nextKind++ % KINDS, rng));
    }

    static byte[] payload(int kind, RandomGenerator rng) {
        return switch (kind) {
            case 0 -> TRUNCATED_JSON.clone();
            case 1 -> MISSING_FIELDS.clone();
            default -> {
                byte[] bytes = new byte[RANDOM_BYTES];
                rng.nextBytes(bytes);
                // 0xFF can never start valid UTF-8 or JSON, so random bytes are guaranteed to be garbage.
                bytes[0] = (byte) 0xFF;
                yield bytes;
            }
        };
    }
}
