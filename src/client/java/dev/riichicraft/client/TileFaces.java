package dev.riichicraft.client;

import dev.riichicraft.game.TileCatalog;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Original code-drawn tile faces. Physical tile identity stays separate from the visible type. */
final class TileFaces {
    private static final String[] SUITS = {"man", "pin", "sou"};
    private static final String[] HONORS = {"east", "south", "west", "north", "white", "green", "red"};

    private TileFaces() {
    }

    static Component fullName(int tileId) {
        int type = TileCatalog.typeOf(tileId);
        Component name = type < 27 ? Component.translatable("tile.riichicraft." + SUITS[type / 9], type % 9 + 1)
                : Component.translatable("tile.riichicraft." + HONORS[type - 27]);
        return TileCatalog.isRed(tileId) ? Component.translatable("tile.riichicraft.red_five", name) : name;
    }

    static Component shortName(int tileId) {
        int type = TileCatalog.typeOf(tileId);
        if (type >= 27) {
            return Component.translatable("tile.riichicraft." + HONORS[type - 27] + ".short");
        }
        int rank = TileCatalog.isRed(tileId) ? 0 : type % 9 + 1;
        return Component.literal(Integer.toString(rank)).append(suit(type));
    }

    private static Component suit(int type) {
        return Component.translatable("tile.riichicraft." + SUITS[type / 9] + ".short");
    }

    static void drawHand(GuiGraphicsExtractor graphics, Font font, int tileId,
            int x, int y, int width, int height, boolean selected, boolean highlighted) {
        int type = TileCatalog.typeOf(tileId);
        boolean red = TileCatalog.isRed(tileId);
        int border = selected ? 0xFFFFD76E : highlighted ? 0xFFC9E5CB : 0xFF738F77;
        graphics.fill(x, y, x + width, y + height, border);
        int inset = selected ? 2 : 1;
        graphics.fill(x + inset, y + inset, x + width - inset, y + height - 2, 0xFFF3EBD6);
        graphics.fill(x + inset, y + height - 2, x + width - inset, y + height - 1, 0xFF3B6E55);
        int color = red ? 0xFFAF2C29 : 0xFF25352D;
        if (type < 27) {
            graphics.centeredText(font, Integer.toString(type % 9 + 1), x + width / 2, y + 2, color);
            graphics.centeredText(font, suit(type), x + width / 2, y + 12, color);
            if (red) {
                graphics.centeredText(font, Component.translatable("screen.riichicraft.game.red_mark"),
                        x + width / 2, y + 21, color);
            }
        } else {
            graphics.centeredText(font, shortName(tileId), x + width / 2, y + 11, color);
        }
    }

    static void drawDiscard(GuiGraphicsExtractor graphics, Font font, int tileId,
            int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFFB6B9A1);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFF3EBD6);
        graphics.centeredText(font, shortName(tileId), x + width / 2, y + 1,
                TileCatalog.isRed(tileId) ? 0xFFAF2C29 : 0xFF25352D);
    }
}
