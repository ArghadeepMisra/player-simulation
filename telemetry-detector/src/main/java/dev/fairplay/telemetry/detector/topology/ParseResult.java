package dev.fairplay.telemetry.detector.topology;

import dev.fairplay.telemetry.common.model.DeadLetter;
import dev.fairplay.telemetry.common.model.MatchEvent;

/** The outcome of parsing one raw record: a valid event, or the dead letter to publish for it. */
public sealed interface ParseResult permits ParseResult.Parsed, ParseResult.Failed {

    record Parsed(MatchEvent event) implements ParseResult {
    }

    record Failed(DeadLetter deadLetter) implements ParseResult {
    }
}
