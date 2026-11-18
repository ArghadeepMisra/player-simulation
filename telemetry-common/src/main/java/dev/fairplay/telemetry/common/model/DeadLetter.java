package dev.fairplay.telemetry.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeadLetter(
        String sourceTopic,
        int partition,
        long offset,
        String key,
        String payloadBase64,
        String error,
        long receivedAt) {

    public static final int MAX_ERROR_LENGTH = 500;

    public DeadLetter {
        if (error != null && error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH);
        }
    }
}
