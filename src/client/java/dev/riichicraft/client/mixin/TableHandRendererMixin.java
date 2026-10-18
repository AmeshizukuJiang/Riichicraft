package dev.riichicraft.client.mixin;

import dev.riichicraft.client.TableCamera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The overhead board needs its full viewport, without a held block or bare arm in front. */
@Mixin(GameRenderer.class)
abstract class TableHandRendererMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void riichicraft$hideOverheadHand(CameraRenderState camera, float partialTick,
            Matrix4fc projection, CallbackInfo callback) {
        if (TableCamera.overhead()) callback.cancel();
    }
}
