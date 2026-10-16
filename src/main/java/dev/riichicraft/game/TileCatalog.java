package dev.riichicraft.game;

import java.util.List;
import java.util.stream.IntStream;

/** The 136 physical tiles; each group of four IDs represents one of the 34 tile types. */
public final class TileCatalog {
    public static final int TILE_COUNT = 136;
    public static final int TYPE_COUNT = 34;
    public static final int COPIES_PER_TYPE = 4;
    public static final int RED_MAN_FIVE = 16;
    public static final int RED_PIN_FIVE = 52;
    public static final int RED_SOU_FIVE = 88;
    private static final List<Integer> ALL_TILES = IntStream.range(0, TILE_COUNT).boxed().toList();

    private TileCatalog() {
    }

    /** Types 0..8 man, 9..17 pin, 18..26 sou, then east/south/west/north/white/green/red. */
    public static int typeOf(int tileId) {
        requireValid(tileId);
        return tileId / COPIES_PER_TYPE;
    }

    public static boolean isRed(int tileId) {
        requireValid(tileId);
        return tileId == RED_MAN_FIVE || tileId == RED_PIN_FIVE || tileId == RED_SOU_FIVE;
    }

    public static List<Integer> allTiles() {
        return ALL_TILES;
    }

    private static void requireValid(int tileId) {
        if (tileId < 0 || tileId >= TILE_COUNT) {
            throw new IllegalArgumentException("Invalid physical tile ID: " + tileId);
        }
    }
}
