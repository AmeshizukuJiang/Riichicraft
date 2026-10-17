package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.util.List;
import java.util.stream.IntStream;

/** Three-player membership; physical IDs retain the shared 0..135 identities, with gaps. */
public final class ThreePlayerTiles {
    private static final List<Integer> TYPES = IntStream.range(0, TileCatalog.TYPE_COUNT)
            .filter(ThreePlayerTiles::containsType).boxed().toList();
    private static final List<Integer> TILES = TileCatalog.allTiles().stream()
            .filter(ThreePlayerTiles::contains).toList();

    private ThreePlayerTiles() { }

    /** Total predicate, including out-of-range input: removed 2m..8m are never legal. */
    public static boolean contains(int physicalId) {
        return physicalId >= 0 && physicalId < TileCatalog.TILE_COUNT
                && containsType(physicalId / TileCatalog.COPIES_PER_TYPE);
    }

    public static boolean containsType(int type) {
        return type == 0 || type >= 8 && type < TileCatalog.TYPE_COUNT;
    }

    public static List<Integer> allTiles() { return TILES; }

    public static List<Integer> allTypes() { return TYPES; }

    /** Validate the preset before interpreting a shared physical ID. */
    public static int typeOf(int physicalId) {
        if (!contains(physicalId)) throw new IllegalArgumentException("Tile does not belong to three-player play: " + physicalId);
        return TileCatalog.typeOf(physicalId);
    }

    public static boolean isRed(int physicalId) {
        typeOf(physicalId);
        return TileCatalog.isRed(physicalId);
    }

    /** 1m and 9m point to each other; honor cycles still include north. */
    public static int doraType(int indicatorPhysicalId) {
        int type = typeOf(indicatorPhysicalId);
        if (type == 0) return 8;
        if (type == 8) return 0;
        return HandScorer.doraType(indicatorPhysicalId);
    }
}
