package dev.riichicraft.client;

import dev.riichicraft.item.ModItems;
import dev.riichicraft.item.TileAppearance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Test-only contact sheet rendered by the real Minecraft item and hand renderers. */
public final class TileGalleryScreen extends Screen {
    private final boolean items;

    public TileGalleryScreen(boolean items) {
        super(Component.literal(items ? "37 native tile models" : "37 shared hand faces"));
        this.items = items;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF183B2E);
        graphics.centeredText(font, title, width / 2, 8, 0xFFF5F0DE);
        var samples = TileAppearance.samples();
        for (int index = 0; index < samples.size(); index++) {
            int id = samples.get(index);
            int x = (width - 280) / 2 + index % 10 * 28;
            int y = 27 + index / 10 * 47;
            if (items) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(x, y);
                graphics.pose().scale(1.7F, 1.7F);
                graphics.item(ModItems.tileStack(id), 0, 0);
                graphics.pose().popMatrix();
            } else {
                TileFaces.drawHand(graphics, font, id, x + 3, y, 22, 32, false, false);
            }
            graphics.centeredText(font, TileAppearance.key(id), x + 14, y + 34, 0xFFF5F0DE);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
