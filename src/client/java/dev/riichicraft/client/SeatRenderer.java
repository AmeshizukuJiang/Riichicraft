package dev.riichicraft.client;

import dev.riichicraft.entity.MahjongSeatEntity;
import dev.riichicraft.entity.ModEntities;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** The chair block supplies the mesh; this entity only positions its rider. */
public final class SeatRenderer extends EntityRenderer<MahjongSeatEntity, EntityRenderState> {
    private SeatRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
        shadowStrength = 0;
    }
    public static void initialize() { EntityRendererRegistry.register(ModEntities.MAHJONG_SEAT, SeatRenderer::new); }
    @Override public EntityRenderState createRenderState() { return new EntityRenderState(); }
}
