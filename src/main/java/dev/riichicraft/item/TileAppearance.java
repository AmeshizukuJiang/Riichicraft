package dev.riichicraft.item;

import dev.riichicraft.Riichicraft;
import dev.riichicraft.game.TileCatalog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Visible face only. Inventory items never carry an authoritative game tile identity. */
public final class TileAppearance {
    private static final String[] SUITS = {"man", "pin", "sou"};
    private static final String[] HONORS = {"east", "south", "west", "north", "white", "green", "red"};
    private static final List<Integer> SAMPLES = createSamples();

    private TileAppearance() {
    }

    public static String key(int tileId) {
        int type = TileCatalog.typeOf(tileId);
        if (type >= 27) return (type - 26) + "z";
        return (TileCatalog.isRed(tileId) ? "0" : Integer.toString(type % 9 + 1)) + "mps".charAt(type / 9);
    }

    public static Identifier itemModel(int tileId) {
        return Riichicraft.id("mahjong_tiles/" + key(tileId));
    }

    public static Identifier texture(int tileId) {
        return Riichicraft.id("textures/item/mahjong_tiles/" + key(tileId) + ".png");
    }

    public static Component name(int tileId) {
        int type = TileCatalog.typeOf(tileId);
        // Translation argument numbers decode as strings in the native component codec.
        // Start with strings so reloaded items still stack with newly created ones.
        Component name = type < 27 ? Component.translatable("tile.riichicraft." + SUITS[type / 9], Integer.toString(type % 9 + 1))
                : Component.translatable("tile.riichicraft." + HONORS[type - 27]);
        return TileCatalog.isRed(tileId) ? Component.translatable("tile.riichicraft.red_five", name) : name;
    }

    /** Ordinary 1..9 plus red five per suit, then E/S/W/N/white/green/red. */
    public static List<Integer> samples() {
        return SAMPLES;
    }

    private static List<Integer> createSamples() {
        List<Integer> result = new ArrayList<>();
        for (int type = 0; type < TileCatalog.TYPE_COUNT; type++) {
            int first = type * TileCatalog.COPIES_PER_TYPE;
            result.add(TileCatalog.isRed(first) ? first + 1 : first);
            if (TileCatalog.isRed(first)) result.add(first);
        }
        return List.copyOf(result);
    }
}
