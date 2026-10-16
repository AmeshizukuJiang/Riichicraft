package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** A declared group, expressed as distinct physical tile IDs rather than tile types. */
public record Meld(Kind kind, List<Integer> tiles) {
    public enum Kind {
        CHI,
        PON,
        OPEN_KAN,
        CLOSED_KAN
    }

    public Meld {
        Objects.requireNonNull(kind, "kind");
        tiles = List.copyOf(Objects.requireNonNull(tiles, "tiles"));
        int expectedSize = kind == Kind.CHI || kind == Kind.PON ? 3 : 4;
        if (tiles.size() != expectedSize) {
            throw new IllegalArgumentException("A " + kind + " must contain " + expectedSize + " tiles");
        }

        boolean[] seen = new boolean[TileCatalog.TILE_COUNT];
        int[] types = new int[expectedSize];
        for (int index = 0; index < tiles.size(); index++) {
            int tile = tiles.get(index);
            types[index] = TileCatalog.typeOf(tile);
            if (seen[tile]) {
                throw new IllegalArgumentException("A physical tile cannot occur twice in a meld: " + tile);
            }
            seen[tile] = true;
        }
        Arrays.sort(types);
        if (kind == Kind.CHI) {
            if (types[0] >= 27 || types[0] % 9 > 6
                    || types[1] != types[0] + 1 || types[2] != types[0] + 2) {
                throw new IllegalArgumentException("A chi must be three consecutive tiles in one suit");
            }
        } else {
            for (int type : types) {
                if (type != types[0]) {
                    throw new IllegalArgumentException("A pon or kan must contain only one tile type");
                }
            }
        }
    }

    /** A concealed kan does not break menzen (a closed hand). */
    public boolean isOpen() {
        return kind != Kind.CLOSED_KAN;
    }
}
