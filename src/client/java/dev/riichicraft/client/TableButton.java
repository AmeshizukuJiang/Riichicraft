package dev.riichicraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Shared table controls; full labels remain available to tooltips and narration. */
class TableButton extends Button {
    TableButton(Component label, int x, int y, int width, int height, OnPress press) {
        super(x, y, width, height, label, press, DEFAULT_NARRATION);
        setTooltip(Tooltip.create(label));
    }

    static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean accent) {
        graphics.fill(x, y, x + width, y + height, 0xDF142B25);
        graphics.outline(x, y, width, height, accent ? 0xFFE1C783 : 0xFF547568);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), width = getWidth(), height = getHeight();
        panel(graphics, x, y, width, height, active && isHoveredOrFocused());
        if (active && isHoveredOrFocused()) graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xA0315344);
        var font = Minecraft.getInstance().font;
        String label = font.plainSubstrByWidth(getMessage().getString(), Math.max(1, width - 8));
        graphics.centeredText(font, label, x + width / 2, y + (height - 8) / 2, active ? 0xFFF3E9CF : 0xFF84948B);
    }
}
