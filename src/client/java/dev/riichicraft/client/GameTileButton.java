package dev.riichicraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;

/** A real keyboard/narration-accessible button with a code-drawn tile face. */
public final class GameTileButton extends Button {
    private final int tileId;
    private final int baselineY;
    private boolean selected;

    GameTileButton(int tileId, int x, int y, int width, OnPress callback) {
        super(x, y, width, 32, LobbyScreen.tileButtonLabel(tileId), callback, DEFAULT_NARRATION);
        this.tileId = tileId;
        baselineY = y;
        setTooltip(Tooltip.create(TileFaces.fullName(tileId)));
    }

    /** Physical identity for integration tests; never shown as an implementation ID on the face. */
    public int tileId() {
        return tileId;
    }

    void setSelected(boolean value) {
        selected = value;
        setY(baselineY - (value ? 3 : 0));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        TileFaces.drawHand(graphics, Minecraft.getInstance().font, tileId,
                getX(), getY(), getWidth(), getHeight(), selected, isHoveredOrFocused());
        if (!active) {
            graphics.fill(getX(),getY(),getX()+getWidth(),getY()+getHeight(),0x44334236);
        }
    }
}
