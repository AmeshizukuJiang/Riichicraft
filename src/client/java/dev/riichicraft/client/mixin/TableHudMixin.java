package dev.riichicraft.client.mixin;

import dev.riichicraft.client.TableCamera;
import dev.riichicraft.client.TableScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep hotbar, mount prompts and crosshair off the board; actual chat remains available. */
@Mixin(Hud.class)
abstract class TableHudMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void riichicraft$tableHud(GuiGraphicsExtractor graphics, DeltaTracker tracker, CallbackInfo callback) {
        var screen = Minecraft.getInstance().gui.screen();
        if (screen instanceof TableScreen || TableCamera.overhead() && !(screen instanceof ChatScreen)) callback.cancel();
    }
}
