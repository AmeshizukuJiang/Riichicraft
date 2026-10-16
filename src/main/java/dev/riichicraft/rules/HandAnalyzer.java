package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exhaustive structural analysis only. A matching shape does not itself establish a legal win:
 * yaku, furiten, winning method, and turn history are outside this API.
 */
public final class HandAnalyzer {
    private HandAnalyzer() {
    }

    /** All distinct standard decompositions and special forms; a 13-tile state returns no forms. */
    public static List<HandDecomposition> analyze(HandInput hand) {
        Objects.requireNonNull(hand, "hand");
        if (!hand.isComplete()) {
            return List.of();
        }

        int[] concealedCounts = new int[TileCatalog.TYPE_COUNT];
        for (int tile : hand.concealedTiles()) {
            concealedCounts[TileCatalog.typeOf(tile)]++;
        }
        Set<HandDecomposition> results = new LinkedHashSet<>();
        List<HandDecomposition.Group> declaredGroups = new ArrayList<>();
        for (Meld meld : hand.melds()) {
            HandDecomposition.Kind kind = switch (meld.kind()) {
                case CHI -> HandDecomposition.Kind.SEQUENCE;
                case PON -> HandDecomposition.Kind.TRIPLET;
                case OPEN_KAN, CLOSED_KAN -> HandDecomposition.Kind.QUAD;
            };
            int firstType = meld.tiles().stream().mapToInt(TileCatalog::typeOf).min().orElseThrow();
            declaredGroups.add(new HandDecomposition.Group(kind, firstType, meld.isOpen()));
        }

        for (int pair = 0; pair < TileCatalog.TYPE_COUNT; pair++) {
            if (concealedCounts[pair] < 2) {
                continue;
            }
            concealedCounts[pair] -= 2;
            enumerateGroups(concealedCounts, 4 - hand.melds().size(), pair, declaredGroups, results);
            concealedCounts[pair] += 2;
        }

        // Even a concealed kan is a declared group and prevents either special form.
        if (hand.melds().isEmpty()) {
            addSpecialForms(concealedCounts, results);
        }
        return List.copyOf(results);
    }

    /**
     * Tile types that can complete the supplied 13-tile state without exceeding four held copies.
     * The result does not assert that those tiles remain in the wall or that ron would be legal.
     */
    public static Set<Integer> waits(HandInput hand) {
        Objects.requireNonNull(hand, "hand");
        if (!hand.isWaiting()) {
            throw new IllegalArgumentException("Waits require a 13-tile structural state");
        }
        int[] heldCounts = hand.tileCounts();
        boolean[] held = new boolean[TileCatalog.TILE_COUNT];
        for (int tile : hand.concealedTiles()) {
            held[tile] = true;
        }
        for (Meld meld : hand.melds()) {
            for (int tile : meld.tiles()) {
                held[tile] = true;
            }
        }

        Set<Integer> waits = new LinkedHashSet<>();
        for (int type = 0; type < TileCatalog.TYPE_COUNT; type++) {
            if (heldCounts[type] == TileCatalog.COPIES_PER_TYPE) {
                continue;
            }
            int candidate = type * TileCatalog.COPIES_PER_TYPE;
            while (held[candidate]) {
                candidate++;
            }
            List<Integer> completedTiles = new ArrayList<>(hand.concealedTiles());
            completedTiles.add(candidate);
            if (!analyze(new HandInput(completedTiles, hand.melds())).isEmpty()) {
                waits.add(type);
            }
        }
        return Collections.unmodifiableSet(waits);
    }

    private static void enumerateGroups(int[] counts, int neededGroups, int pairType,
                                        List<HandDecomposition.Group> groups,
                                        Set<HandDecomposition> results) {
        int first = 0;
        while (first < counts.length && counts[first] == 0) {
            first++;
        }
        if (first == counts.length) {
            if (neededGroups == 0) {
                results.add(new HandDecomposition(HandDecomposition.Form.STANDARD, pairType, groups));
            }
            return;
        }
        if (neededGroups == 0) {
            return;
        }

        // In any decomposition, the smallest remaining tile belongs to a triplet or starts a sequence.
        if (counts[first] >= 3) {
            counts[first] -= 3;
            groups.add(new HandDecomposition.Group(HandDecomposition.Kind.TRIPLET, first, false));
            enumerateGroups(counts, neededGroups - 1, pairType, groups, results);
            groups.removeLast();
            counts[first] += 3;
        }
        if (first < 27 && first % 9 <= 6 && counts[first + 1] > 0 && counts[first + 2] > 0) {
            counts[first]--;
            counts[first + 1]--;
            counts[first + 2]--;
            groups.add(new HandDecomposition.Group(HandDecomposition.Kind.SEQUENCE, first, false));
            enumerateGroups(counts, neededGroups - 1, pairType, groups, results);
            groups.removeLast();
            counts[first]++;
            counts[first + 1]++;
            counts[first + 2]++;
        }
    }

    private static void addSpecialForms(int[] counts, Set<HandDecomposition> results) {
        List<Integer> pairTypes = new ArrayList<>();
        boolean sevenPairs = true;
        boolean thirteenOrphans = true;
        int orphanPair = -1;
        for (int type = 0; type < counts.length; type++) {
            if (counts[type] == 2) {
                pairTypes.add(type);
            } else if (counts[type] != 0) {
                sevenPairs = false;
            }
            if (HandDecomposition.isOrphan(type)) {
                if (counts[type] == 2 && orphanPair == -1) {
                    orphanPair = type;
                } else if (counts[type] != 1) {
                    thirteenOrphans = false;
                }
            } else if (counts[type] != 0) {
                thirteenOrphans = false;
            }
        }
        if (sevenPairs && pairTypes.size() == 7) {
            results.add(new HandDecomposition(HandDecomposition.Form.SEVEN_PAIRS, -1, List.of(), pairTypes));
        }
        if (thirteenOrphans && orphanPair != -1) {
            results.add(new HandDecomposition(HandDecomposition.Form.THIRTEEN_ORPHANS, orphanPair, List.of()));
        }
    }
}
