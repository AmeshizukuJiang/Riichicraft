package dev.riichicraft.table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Pure public geometry. Indices are wall positions, never hidden tile identities or shuffle data. */
public final class TableWallLayout {
    /** layer 0 is on the cloth; layer 1 is above it. indicator -1 means no public face. */
    public record Slot(int index, int side, int stack, int layer, boolean reserve, int indicator) { }
    private TableWallLayout() { }

    public static List<Slot> slots(int players, int live, int replacements, int indicators,
                                   int front, List<Integer> occupiedSides) {
        int total = total(players), capacity = players == 4 ? 4 : 8;
        int tail = total - 14 - replacements, head = tail - live;
        if (replacements < 0 || replacements > capacity || live < 0 || head < 0
                || indicators < 0 || indicators > 5 || front < 0 || front > 3
                || occupiedSides.size() != players || new HashSet<>(occupiedSides).size() != players
                || occupiedSides.stream().anyMatch(s -> s < 0 || s > 3)) throw new IllegalArgumentException("wall geometry");
        List<Integer> sides = new ArrayList<>();
        for (int i = 0; i < 4; i++) if (occupiedSides.contains((front + i) % 4)) sides.add((front + i) % 4);
        int perWall = total / players / 2, replacementStart = players == 4 ? 122 : 100;
        List<Slot> result = new ArrayList<>(); int shown = 0;
        for (int logical = head; logical < total; logical++) {
            if (logical >= replacementStart && logical < replacementStart + replacements) continue;
            int physical = physicalIndex(players, logical), stack = physical / 2;
            int indicator = -1;
            for (int i = 0; i < indicators; i++) {
                int position = players == 4 ? 126 + 2 * i : 98 - 2 * i;
                if (position == logical) { indicator = i; shown++; }
            }
            if (indicator >= 0 && logical < tail) throw new IllegalArgumentException("Indicator still live");
            result.add(new Slot(physical, sides.get(stack / perWall), stack % perWall,
                    physical % 2 == 0 ? 1 : 0, logical >= tail, indicator));
        }
        if (shown != indicators) throw new IllegalArgumentException("Indicator already consumed");
        return List.copyOf(result);
    }

    /** Permute only reserve storage into physical stacks: top before bottom, outer end first. */
    public static int physicalIndex(int players, int logical) {
        int total = total(players);
        if (logical < 0 || logical >= total) throw new IllegalArgumentException("wall position");
        if (players == 3) {
            if (logical < 100) return logical;
            int r = logical - 100;
            return 106 - 2 * (r / 2) + r % 2;
        }
        if (logical < 122) return logical;
        if (logical < 126) {
            int r = logical - 122;
            return 134 - 2 * (r / 2) + r % 2;
        }
        int d = logical - 126;
        return 130 - 2 * (d / 2) + d % 2;
    }

    private static int total(int players) {
        if (players != 3 && players != 4) throw new IllegalArgumentException("wall players");
        return players == 4 ? 136 : 108;
    }
}
