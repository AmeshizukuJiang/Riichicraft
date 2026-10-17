package dev.riichicraft.client;

import dev.riichicraft.game.TileCatalog;
import dev.riichicraft.item.TileAppearance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Licensed graphical hand faces; compact rivers retain their existing text labels. */
final class TileFaces {
    private static final String[] SUITS = {"man", "pin", "sou"};
    private static final String[] HONORS = {"east", "south", "west", "north", "white", "green", "red"};

    private TileFaces() {
    }

    static Component fullName(int tileId) {
        return TileAppearance.name(tileId);
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
        int border = selected ? 0xFFFFD76E : highlighted ? 0xFFC9E5CB : 0xFF738F77;
        graphics.fill(x, y, x + width, y + height, border);
        int inset = selected ? 2 : 1;
        graphics.fill(x + inset, y + inset, x + width - inset, y + height - 2, 0xFFF5F0DE);
        graphics.fill(x + inset, y + height - 2, x + width - inset, y + height - 1, 0xFF205440);
        int faceWidth = Math.min(width - 2 * inset, (height - 2 * inset - 2) * 8 / 11);
        int faceHeight = faceWidth * 11 / 8;
        int faceX = x + (width - faceWidth) / 2;
        int faceY = y + (height - 2 - faceHeight) / 2;
        graphics.blit(TileAppearance.texture(tileId), faceX, faceY, faceX + faceWidth, faceY + faceHeight,
                0.0F, 1.0F, 0.0F, 88.0F / 128.0F);
    }

    static void drawDiscard(GuiGraphicsExtractor graphics, Font font, int tileId,
            int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFFB6B9A1);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFF3EBD6);
        graphics.centeredText(font, shortName(tileId), x + width / 2, y + 1,
                TileCatalog.isRed(tileId) ? 0xFFAF2C29 : 0xFF25352D);
    }
}
