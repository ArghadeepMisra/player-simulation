package dev.fairplay.telemetry.detector.topology;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fairplay.telemetry.common.model.PlayerProfile;
import dev.fairplay.telemetry.common.model.SkillTier;
import dev.fairplay.telemetry.common.model.SuspiciousPlayer;
import dev.fairplay.telemetry.common.support.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProfileJoinTopologyTest {

    private static final String P = TestEvents.player(1);
    private static final long T0 = TopologyFixture.START.toEpochMilli();

    private final TopologyFixture fixture = new TopologyFixture();

    @AfterEach
    void close() {
        fixture.close();
    }

    private void aimbotBurst() {
        for (int i = 0; i < 15; i++) {
            fixture.send(TestEvents.shot(P, i + 1, T0 + i * 200L, true, true, 60));
        }
    }

    @Test
    void aFlagForAKnownPlayerCarriesUsernameAndSkillTier() {
        fixture.profiles.pipeInput(P, new PlayerProfile(P, "sim_0042", "IN", SkillTier.GOLD, T0), T0 - 1000);

        aimbotBurst();

        SuspiciousPlayer flag = fixture.flags.readValue();
        assertThat(flag.username()).isEqualTo("sim_0042");
        assertThat(flag.skillTier()).isEqualTo(SkillTier.GOLD);
        assertThat(flag.playerId()).isEqualTo(P);
    }

    @Test
    void aFlagForAnUnknownPlayerIsStillEmittedWithNulls() {
        aimbotBurst();

        SuspiciousPlayer flag = fixture.flags.readValue();
        assertThat(flag.playerId()).isEqualTo(P);
        assertThat(flag.username()).isNull();
        assertThat(flag.skillTier()).isNull();
    }

    @Test
    void theLatestProfileWins() {
        fixture.profiles.pipeInput(P, new PlayerProfile(P, "old_name", "IN", SkillTier.BRONZE, T0), T0 - 2000);
        fixture.profiles.pipeInput(P, new PlayerProfile(P, "new_name", "IN", SkillTier.PRO, T0), T0 - 1000);

        aimbotBurst();

        SuspiciousPlayer flag = fixture.flags.readValue();
        assertThat(flag.username()).isEqualTo("new_name");
        assertThat(flag.skillTier()).isEqualTo(SkillTier.PRO);
    }

    @Test
    void anotherPlayersProfileIsNotUsed() {
        String other = TestEvents.player(2);
        fixture.profiles.pipeInput(other, new PlayerProfile(other, "someone_else", "US", SkillTier.SILVER, T0),
                T0 - 1000);

        aimbotBurst();

        assertThat(fixture.flags.readValue().username()).isNull();
    }

    @Test
    void withProfileLeavesTheFlagAloneWhenTheProfileIsNull() {
        FlagCandidate candidate = new FlagCandidate(P, dev.fairplay.telemetry.common.model.Rule.AIMBOT, 0.7, 1, 2,
                null, null, null);

        assertThat(candidate.withProfile(null)).isSameAs(candidate);
    }
}
