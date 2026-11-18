package dev.fairplay.telemetry.simulator.population;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.GroundTruth;
import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.Segment;
import dev.fairplay.telemetry.common.model.SkillTier;
import dev.fairplay.telemetry.simulator.SimulatorProperties;
import net.datafaker.Faker;

/** Builds the seeded population: ids, usernames, countries, roles, behavior profiles and cheat start times. */
public final class PopulationFactory {

    /**
     * XORed into the seed so the population draws never replay a shard's stream: shard k uses
     * {@code SplittableRandom(seed + k)}, and shard 0 would otherwise share this one.
     */
    private static final long POPULATION_STREAM = 0x9E3779B97F4A7C15L;

    private final SimulatorProperties props;

    public PopulationFactory(SimulatorProperties props) {
        this.props = props;
    }

    /**
     * Player {@code i} gets id {@code nameUUIDFromBytes(seed + ":" + i)} and username {@code sim_0001} and up.
     * Behavior profiles, cheat profiles and cheat start offsets are drawn in player-index order from one
     * {@code SplittableRandom(seed ^ POPULATION_STREAM)}, separate from the shuffle in {@link CheaterAssignment}, so the whole population
     * depends only on the seed. Countries come from a Datafaker seeded the same way.
     */
    public List<PlayerState> create(long runStartedAtMs) {
        List<CheaterAssignment.Role> roles = CheaterAssignment.assign(
                props.players(), props.cheaterRatio(), props.proRatio(), props.seed());
        SplittableRandom rng = new SplittableRandom(props.seed() ^ POPULATION_STREAM);
        Faker faker = new Faker(new Random(props.seed()));
        long minMs = props.cheatStartMin().toMillis();
        long maxMs = props.cheatStartMax().toMillis();

        List<PlayerState> players = new ArrayList<>(props.players());
        for (int i = 0; i < props.players(); i++) {
            CheaterAssignment.Role role = roles.get(i);
            BehaviorProfile base = role.segment() == Segment.PRO ? BehaviorProfile.pro(rng) : BehaviorProfile.honest(rng);
            BehaviorProfile cheat = null;
            Long cheatStartsAt = null;
            if (role.segment() == Segment.CHEATER) {
                cheat = base.asCheater(role.cheatType(), rng);
                cheatStartsAt = runStartedAtMs + minMs + Math.round(rng.nextDouble() * (maxMs - minMs));
            }
            // Cheaters keep their base profile's tier, so the tier does not give them away.
            SkillTier tier = role.segment() == Segment.PRO ? SkillTier.PRO : base.skillTier();
            players.add(new PlayerState(
                    i,
                    UUID.nameUUIDFromBytes((props.seed() + ":" + i).getBytes(UTF_8)).toString(),
                    "sim_%04d".formatted(i + 1),
                    faker.country().countryCode2().toUpperCase(),
                    role.segment(),
                    role.cheatType(),
                    tier,
                    base,
                    cheat,
                    cheatStartsAt,
                    new AtomicLong()));
        }
        return players;
    }

    public static PlayerProfile profileOf(PlayerState player, long registeredAtMs) {
        return new PlayerProfile(player.playerId(), player.username(), player.country(), player.skillTier(),
                registeredAtMs);
    }

    public static GroundTruth truthOf(String runId, PlayerState player) {
        CheatType type = player.cheatType();
        return new GroundTruth(runId, player.playerId(), player.segment(), type, player.cheatStartsAt());
    }
}
