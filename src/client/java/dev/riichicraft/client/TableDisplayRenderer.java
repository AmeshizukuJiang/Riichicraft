package dev.riichicraft.client;

import com.mojang.math.Axis;
import dev.riichicraft.Riichicraft;
import dev.riichicraft.block.LargeMahjongTableBlock;
import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.item.ModItems;
import dev.riichicraft.table.TableTileLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;

/** Extract world/model data first, then submit immutable frame data without reading the live level. */
final class TableDisplayRenderer {
    private record Draw(TableTileLayout.Tile tile, double x, double y, double z, int light, ItemStackRenderState model) { }
    private static final RenderStateDataKey<List<Draw>> DRAWS = RenderStateDataKey.create(() -> "riichicraft tabletop");
    private TableDisplayRenderer() { }
    static void initialize() {
        LevelExtractionEvents.END_EXTRACTION.register(context -> extract(context, TableDisplayClient.snapshots()));
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            var pose = context.poseStack();
            for (Draw draw : context.levelState().getDataOrDefault(DRAWS, List.of())) {
                var tile = draw.tile(); pose.pushPose(); pose.translate(draw.x(), draw.y(), draw.z());
                pose.mulPose(Axis.YP.rotationDegrees(tile.yaw()));
                if (tile.flat()) pose.mulPose(Axis.XP.rotationDegrees(tile.down() ? 90 : -90));
                pose.scale(tile.scale(), tile.scale(), tile.scale());
                draw.model().submit(pose, context.submitNodeCollector(), draw.light(), OverlayTexture.NO_OVERLAY, 0);
                pose.popPose();
            }
        });
    }

    static void extract(LevelExtractionContext context, List<dev.riichicraft.network.TableDisplayPayload> tables) {
            var client = Minecraft.getInstance(); var level = context.level();
            List<Draw> draws = new ArrayList<>(); Map<Integer, ItemStackRenderState> models = new HashMap<>();
            var camera = context.levelState().cameraRenderState.pos;
            for (var table : tables) {
                var pos = table.pos();
                if (!table.dimension().equals(level.dimension()) || !level.hasChunkAt(pos)
                        || pos.distToCenterSqr(camera) > 48 * 48) continue;
                var root = level.getBlockState(pos);
                if (!root.is(ModBlocks.LARGE_MAHJONG_TABLE) || root.getValue(LargeMahjongTableBlock.PART) != 4) continue;
                int light = (level.getBrightness(LightLayer.SKY, pos.above()) << 20)
                        | (level.getBrightness(LightLayer.BLOCK, pos.above()) << 4);
                for (var tile : TableTileLayout.tiles(table.snapshot())) {
                    var model = models.computeIfAbsent(tile.face(), face -> {
                        ItemStack stack = face < 0 ? new ItemStack(ModItems.MAHJONG_TILE) : ModItems.tileStack(face);
                        if (face < 0) stack.set(DataComponents.ITEM_MODEL, Riichicraft.id("mahjong_tiles/hidden"));
                        var state = new ItemStackRenderState();
                        client.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.NONE, level, null, 0);
                        return state;
                    });
                    draws.add(new Draw(tile, pos.getX() + 0.5 + tile.x() / 16 - camera.x,
                            pos.getY() + 1 + tile.y() / 16 - camera.y, pos.getZ() + 0.5 + tile.z() / 16 - camera.z, light, model));
                }
            }
            context.levelState().setData(DRAWS, List.copyOf(draws));
    }
}
