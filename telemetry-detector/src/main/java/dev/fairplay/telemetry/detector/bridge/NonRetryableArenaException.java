package dev.fairplay.telemetry.detector.bridge;

/**
 * A permanent failure (400, 404, 409, 422, a second 401, or a summary that is not valid JSON). Trying again cannot
 * help, so the error handler sends the record straight to {@code match-summaries.DLT}. {@code code} is Arena's
 * problem code (for example {@code PLAYER_NOT_ELIGIBLE}) or one of ours (for example {@code MALFORMED_SUMMARY}).
 */
public class NonRetryableArenaException extends RuntimeException {

    private final String code;

    public NonRetryableArenaException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
