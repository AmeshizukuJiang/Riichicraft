package dev.riichicraft.client.mixin;

import dev.riichicraft.client.TableCamera;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera 26.2 computes culling after alignment, so override here rather than after update. */
@Mixin(Camera.class)
abstract class TableCameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void riichicraft$alignTable(float partialTick, CallbackInfo callback) {
        var view = TableCamera.view();
        if (view != null) {
            setRotation(view.yaw(), view.pitch());
            setPosition(view.x(), view.y(), view.z());
        }
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void riichicraft$tableFov(float partialTick, CallbackInfoReturnable<Float> callback) {
        callback.setReturnValue(TableCamera.fov(callback.getReturnValue()));
    }
}
