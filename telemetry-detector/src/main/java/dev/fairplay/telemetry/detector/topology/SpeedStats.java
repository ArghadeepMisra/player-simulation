package dev.fairplay.telemetry.detector.topology;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.fairplay.telemetry.common.model.MatchEvent;

/** What one speedhack window has seen of a player's POSITION events. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpeedStats(int movingSamples, int overspeedSamples, double maxSpeed) {

    public static SpeedStats empty() {
        return new SpeedStats(0, 0, 0.0);
    }

    /**
     * A new value with this POSITION event added. {@code speed = sqrt(dx^2 + dy^2) / (dtMs / 1000)}. A sample is
     * moving when {@code speed >= movingSpeedFloor}, and overspeed when it is moving and {@code speed > speedLimit}.
     * Idle samples count toward nothing.
     */
    public SpeedStats add(MatchEvent position, double movingSpeedFloor, double speedLimit) {
        double speed = speedOf(position);
        boolean moving = speed >= movingSpeedFloor;
        return new SpeedStats(
                movingSamples + (moving ? 1 : 0),
                overspeedSamples + (moving && speed > speedLimit ? 1 : 0),
                Math.max(maxSpeed, speed));
    }

    static double speedOf(MatchEvent position) {
        return Math.hypot(position.dx(), position.dy()) / (position.dtMs() / 1000.0);
    }

    /** Overspeed samples per moving sample, 0 when nothing moved. */
    @JsonIgnore
    public double overspeedRatio() {
        return movingSamples == 0 ? 0.0 : (double) overspeedSamples / movingSamples;
    }
}
