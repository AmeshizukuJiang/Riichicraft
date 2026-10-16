package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.List;
import java.util.Objects;

/**
 * One structural hand, either before or after its possible winning tile.
 * Concealed tiles exclude declared groups; a possible winning tile belongs in concealedTiles.
 * This does not describe discard history, winning method, yaku, or available wall tiles.
 */
public record HandInput(List<Integer> concealedTiles, List<Meld> melds) {
    public HandInput {
        concealedTiles = List.copyOf(Objects.requireNonNull(concealedTiles, "concealedTiles"));
        melds = List.copyOf(Objects.requireNonNull(melds, "melds"));
        if (melds.size() > 4) {
            throw new IllegalArgumentException("A hand cannot contain more than four declared groups");
        }

        boolean[] seen = new boolean[TileCatalog.TILE_COUNT];
        int[] counts = new int[TileCatalog.TYPE_COUNT];
        for (int tile : concealedTiles) {
            addTile(tile, seen, counts);
        }
        for (Meld meld : melds) {
            for (int tile : meld.tiles()) {
                addTile(tile, seen, counts);
            }
        }

        int waitingConcealedSize = 13 - 3 * melds.size();
        if (concealedTiles.size() != waitingConcealedSize
                && concealedTiles.size() != waitingConcealedSize + 1) {
            throw new IllegalArgumentException("Expected " + waitingConcealedSize + " or "
                    + (waitingConcealedSize + 1) + " concealed tiles for " + melds.size() + " declared groups");
        }
    }

    /** Menzen depends on open declarations; concealed kans remain closed. */
    public boolean isClosed() {
        return melds.stream().noneMatch(Meld::isOpen);
    }

    /** True for 14 tiles plus the extra physical tile of each declared kan. */
    public boolean isComplete() {
        return concealedTiles.size() == 14 - 3 * melds.size();
    }

    /** True for the structural 13-tile state; this does not imply that it is in tenpai. */
    public boolean isWaiting() {
        return !isComplete();
    }

    /** Counts all held tiles, including every declared chi, pon, and kan; returns a fresh array. */
    public int[] tileCounts() {
        int[] counts = new int[TileCatalog.TYPE_COUNT];
        for (int tile : concealedTiles) {
            counts[TileCatalog.typeOf(tile)]++;
        }
        for (Meld meld : melds) {
            for (int tile : meld.tiles()) {
                counts[TileCatalog.typeOf(tile)]++;
            }
        }
        return counts;
    }

    private static void addTile(int tile, boolean[] seen, int[] counts) {
        int type = TileCatalog.typeOf(tile);
        if (seen[tile]) {
            throw new IllegalArgumentException("A physical tile cannot occur twice in a hand: " + tile);
        }
        seen[tile] = true;
        if (++counts[type] > TileCatalog.COPIES_PER_TYPE) {
            throw new IllegalArgumentException("A hand cannot hold more than four tiles of type " + type);
        }
    }
}
