package dev.fairplay.telemetry.simulator.population;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import dev.fairplay.telemetry.common.model.CheatType;
import dev.fairplay.telemetry.common.model.Segment;

/** Decides, per player index, who is a cheater, a pro or honest. Same seed, same answer. */
public final class CheaterAssignment {

    /** The role of one player. {@code cheatType} is {@link CheatType#NONE} for honest players and pros. */
    public record Role(Segment segment, CheatType cheatType) {
    }

    private static final Role HONEST = new Role(Segment.HONEST, CheatType.NONE);
    private static final Role PRO = new Role(Segment.PRO, CheatType.NONE);

    private CheaterAssignment() {
    }

    /**
     * Shuffles the indices with {@code new Random(seed)}. The first {@code round(players x cheaterRatio)} become
     * cheaters (40% AIMBOT, 20% AIMBOT_SUBTLE, 24% SPEEDHACK, 16% SPEEDHACK_SUBTLE, rounded down, any remainder to
     * AIMBOT). The next {@code round(players x proRatio)} become PRO, and everyone else is HONEST.
     *
     * @return the role of player {@code i} at index {@code i}
     */
    public static List<Role> assign(int players, double cheaterRatio, double proRatio, long seed) {
        List<Integer> order = new ArrayList<>(players);
        for (int i = 0; i < players; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(seed));

        int cheaters = (int) Math.round(players * cheaterRatio);
        int pros = (int) Math.round(players * proRatio);
        int subtleAim = cheaters * 20 / 100;
        int speed = cheaters * 24 / 100;
        int subtleSpeed = cheaters * 16 / 100;
        int aim = cheaters - subtleAim - speed - subtleSpeed; // 40%, plus the rounding remainder

        Role[] roles = new Role[players];
        int next = 0;
        next = fill(roles, order, next, aim, new Role(Segment.CHEATER, CheatType.AIMBOT));
        next = fill(roles, order, next, subtleAim, new Role(Segment.CHEATER, CheatType.AIMBOT_SUBTLE));
        next = fill(roles, order, next, speed, new Role(Segment.CHEATER, CheatType.SPEEDHACK));
        next = fill(roles, order, next, subtleSpeed, new Role(Segment.CHEATER, CheatType.SPEEDHACK_SUBTLE));
        next = fill(roles, order, next, pros, PRO);
        for (int i = 0; i < players; i++) {
            if (roles[i] == null) {
                roles[i] = HONEST;
            }
        }
        return List.of(roles);
    }

    private static int fill(Role[] roles, List<Integer> order, int from, int count, Role role) {
        for (int i = from; i < from + count; i++) {
            roles[order.get(i)] = role;
        }
        return from + count;
    }
}
