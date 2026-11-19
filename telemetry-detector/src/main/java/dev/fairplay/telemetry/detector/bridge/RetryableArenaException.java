package dev.fairplay.telemetry.detector.bridge;

/** A temporary failure (429, 5xx, no connection, timeout). The error handler retries it with backoff, then dead-letters it. */
public class RetryableArenaException extends RuntimeException {

    public RetryableArenaException(String message) {
        super(message);
    }

    public RetryableArenaException(String message, Throwable cause) {
        super(message, cause);
    }
}
