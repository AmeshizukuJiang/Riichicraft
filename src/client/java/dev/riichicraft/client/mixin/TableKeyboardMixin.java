package dev.riichicraft.client.mixin;

import dev.riichicraft.client.TableInteractionClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The no-screen path never reaches TableScreen's Esc handler. */
@Mixin(KeyboardHandler.class)
abstract class TableKeyboardMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void riichicraft$leaveHiddenBoard(long window, int action, KeyEvent event, CallbackInfo callback) {
        if (window == minecraft.getWindow().handle() && action == GLFW.GLFW_PRESS
                && TableInteractionClient.exitHiddenOverhead(minecraft, event)) callback.cancel();
    }
}
