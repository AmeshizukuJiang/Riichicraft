package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** A structural explanation using tile types 0..33, independent of physical copy or red status. */
public record HandDecomposition(Form form, int pairType, List<Group> groups, List<Integer> sevenPairTypes) {
    public enum Form {
        STANDARD,
        SEVEN_PAIRS,
        THIRTEEN_ORPHANS
    }

    public enum Kind {
        SEQUENCE,
        TRIPLET,
        QUAD
    }

    /** For a sequence, tileType is its first tile; for a triplet or quad it is the repeated tile. */
    public record Group(Kind kind, int tileType, boolean open) {
        public Group {
            Objects.requireNonNull(kind, "kind");
            requireType(tileType);
            if (kind == Kind.SEQUENCE && (tileType >= 27 || tileType % 9 > 6)) {
                throw new IllegalArgumentException("A sequence must fit within one numbered suit");
            }
        }
    }

    public HandDecomposition {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(sevenPairTypes, "sevenPairTypes");
        ArrayList<Group> orderedGroups = new ArrayList<>(List.copyOf(groups));
        orderedGroups.sort(Comparator.comparing(Group::kind).thenComparingInt(Group::tileType)
                .thenComparing(Group::open));
        groups = List.copyOf(orderedGroups);
        ArrayList<Integer> orderedPairs = new ArrayList<>(List.copyOf(sevenPairTypes));
        orderedPairs.sort(Integer::compareTo);
        sevenPairTypes = List.copyOf(orderedPairs);

        switch (form) {
            case STANDARD -> {
                requireType(pairType);
                if (groups.size() != 4 || !sevenPairTypes.isEmpty()) {
                    throw new IllegalArgumentException("A standard hand has one pair and four groups");
                }
            }
            case SEVEN_PAIRS -> {
                if (pairType != -1 || !groups.isEmpty() || sevenPairTypes.size() != 7
                        || new HashSet<>(sevenPairTypes).size() != 7) {
                    throw new IllegalArgumentException("Seven pairs requires seven distinct pair types");
                }
                sevenPairTypes.forEach(HandDecomposition::requireType);
            }
            case THIRTEEN_ORPHANS -> {
                requireType(pairType);
                if (!isOrphan(pairType) || !groups.isEmpty() || !sevenPairTypes.isEmpty()) {
                    throw new IllegalArgumentException("Thirteen orphans requires a terminal or honor pair");
                }
            }
        }
    }

    public HandDecomposition(Form form, int pairType, List<Group> groups) {
        this(form, pairType, groups, List.of());
    }

    static boolean isOrphan(int type) {
        return type >= 27 || type % 9 == 0 || type % 9 == 8;
    }

    private static void requireType(int tileType) {
        if (tileType < 0 || tileType >= TileCatalog.TYPE_COUNT) {
            throw new IllegalArgumentException("Invalid tile type: " + tileType);
        }
    }
}
