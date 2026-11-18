package dev.fairplay.telemetry.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Why a flag fired. AIMBOT fills shots, hits, headshotHits, headshotRate and avgReactionMs.
 * SPEEDHACK fills movingSamples, overspeedSamples, overspeedRatio and maxSpeed. Unused fields are omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Evidence(
        Integer shots,
        Integer hits,
        Integer headshotHits,
        Double headshotRate,
        Double avgReactionMs,
        Integer movingSamples,
        Integer overspeedSamples,
        Double overspeedRatio,
        Double maxSpeed) {

    public static Evidence aimbot(int shots, int hits, int headshotHits, double headshotRate, double avgReactionMs) {
        return new Evidence(shots, hits, headshotHits, headshotRate, avgReactionMs, null, null, null, null);
    }

    public static Evidence speedhack(int movingSamples, int overspeedSamples, double overspeedRatio, double maxSpeed) {
        return new Evidence(null, null, null, null, null, movingSamples, overspeedSamples, overspeedRatio, maxSpeed);
    }
}
