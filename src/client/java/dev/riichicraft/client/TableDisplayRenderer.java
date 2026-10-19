package dev.riichicraft.client;

import com.mojang.math.Axis;
import dev.riichicraft.Riichicraft;
import dev.riichicraft.block.LargeMahjongTableBlock;
import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.item.ModItems;
import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.network.TableScenePayload;
import dev.riichicraft.table.TableAnimationPlayer;
import dev.riichicraft.table.TableTileLayout;
import dev.riichicraft.table.TileMotion;
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
    private record Draw(TableTileLayout.Tile tile, dev.riichicraft.table.TileMotion.Pose pose, double x, double y, double z, int light, int outline, ItemStackRenderState model) { }
    private static final RenderStateDataKey<List<Draw>> DRAWS = RenderStateDataKey.create(() -> "riichicraft tabletop");
    private TableDisplayRenderer() { }
    static void initialize() {
        LevelExtractionEvents.END_EXTRACTION.register(context -> extract(context, TableDisplayClient.snapshots()));
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            var pose = context.poseStack();
            for (Draw draw : context.levelState().getDataOrDefault(DRAWS, List.of())) {
                var tile = draw.tile(); pose.pushPose(); pose.translate(draw.x(), draw.y(), draw.z());
                var motion=draw.pose();pose.mulPose(Axis.YP.rotationDegrees(motion.yaw()));
                pose.mulPose(Axis.XP.rotationDegrees(motion.pitch()));
                pose.scale(motion.scale(),motion.scale(),motion.scale());
                draw.model().submit(pose, context.submitNodeCollector(), draw.light(), OverlayTexture.NO_OVERLAY, draw.outline());
                pose.popPose();
            }
        });
    }

    static void extract(LevelExtractionContext context, List<dev.riichicraft.network.TableDisplayPayload> tables) {
        extract(context,tables,TableDisplayClient::frames);
    }
    static void extract(LevelExtractionContext context,List<dev.riichicraft.network.TableDisplayPayload> tables,
            java.util.function.BiFunction<dev.riichicraft.network.TableDisplayPayload,Long,List<dev.riichicraft.table.TableAnimationPlayer.Frame>> frames) {
            var client = Minecraft.getInstance(); var level = context.level();
            List<Draw> draws = new ArrayList<>(); Map<Integer, ItemStackRenderState> models = new HashMap<>();
            var camera = context.levelState().cameraRenderState.pos;
            TableCamera.beginHandFrame();
            for (var table : tables) {
                var pos = table.pos();
                if (!table.dimension().equals(level.dimension()) || !level.hasChunkAt(pos)
                        || pos.distToCenterSqr(camera) > 48 * 48) continue;
                var root = level.getBlockState(pos);
                if (!root.is(ModBlocks.LARGE_MAHJONG_TABLE) || root.getValue(LargeMahjongTableBlock.PART) != 4) continue;
                int light = (level.getBrightness(LightLayer.SKY, pos.above()) << 20)
                        | (level.getBrightness(LightLayer.BLOCK, pos.above()) << 4);
                var scene = TableInteractionClient.scene();
                var visible = TableDisplayClient.visibleFrames(table, frames.apply(table, System.nanoTime()), scene, TableCamera.overhead());
                if (matches(table, scene) && client.gui.screen() instanceof TableScreen screen)
                    visible = selectedFrame(visible, scene.ownSeat(), screen.selectedTileId());
                if (matches(table, scene)) TableCamera.recordHandFrame(scene, visible);
                for (var frame : visible) {
                    var tile=frame.tile();var motion=frame.pose();
                    var model = models.computeIfAbsent(tile.face(), face -> {
                        ItemStack stack = face < 0 ? new ItemStack(ModItems.MAHJONG_TILE) : ModItems.tileStack(face);
                        if (face < 0) stack.set(DataComponents.ITEM_MODEL, Riichicraft.id("mahjong_tiles/hidden"));
                        var state = new ItemStackRenderState();
                        client.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.NONE, level, null, 0);
                        return state;
                    });
                    int outline = matches(table, scene) && tile.group() == TableTileLayout.Group.HAND
                            && tile.seat() == scene.ownSeat() && client.gui.screen() instanceof TableScreen screen
                            ? screen.tileOutlineColor(tile.face()) : 0;
                    draws.add(new Draw(tile,motion, pos.getX() + 0.5 + motion.x() / 16 - camera.x,
                            pos.getY() + 1 + motion.y() / 16 - camera.y, pos.getZ() + 0.5 + motion.z() / 16 - camera.z, light, outline, model));
                }
            }
            context.levelState().setData(DRAWS, List.copyOf(draws));
    }

    /** Recipient hands replace anonymous hand slots; public rivers, walls and animation stay shared. */
    static List<TableAnimationPlayer.Frame> visibleFrames(TableDisplayPayload table, List<TableAnimationPlayer.Frame> frames,
            TableScenePayload scene, boolean overhead) {
        if (!matches(table, scene)) return frames;
        List<TableAnimationPlayer.Frame> result = new ArrayList<>();
        Map<String, TableAnimationPlayer.Frame> anonymous = new HashMap<>();
        for (var frame : frames) {
            var tile = frame.tile();
            if (tile.group() != TableTileLayout.Group.HAND || scene.hands().get(tile.seat()).isEmpty()) result.add(frame);
            else anonymous.put(tile.key(), frame);
        }
        for (int seat = 0; seat < scene.hands().size(); seat++) {
            var hand = new ArrayList<>(scene.hands().get(seat));
            if (hand.isEmpty()) continue;
            hand.sort(java.util.Comparator.naturalOrder());
            int drawn = scene.drawnTiles().get(seat);
            if (drawn >= 0 && hand.remove(Integer.valueOf(drawn))) hand.add(drawn);
            boolean flat = scene.finished() || overhead && (scene.ownSeat() < 0 || seat == scene.ownSeat());
            for (int index = 0; index < hand.size(); index++) {
                String key = "h:" + seat + ":" + index;
                var source = anonymous.get(key);
                // Private hand packets can arrive before the public draw animation. Never flash
                // the new draw at its destination while that anonymous slot is still absent.
                if (!scene.finished() && hand.get(index) == drawn && (source == null
                        || scene.publicStateHash()!=null && source.stateHash()!=scene.publicStateHash())) continue;
                var tile = handTile(scene, seat, index, hand.get(index), overhead);
                var pose = new TileMotion.Pose(tile.x(), tile.y(), tile.z(), tile.yaw(), flat ? -90 : 0, tile.scale());
                if (source != null) {
                    var old = source.tile(); var motion = source.pose();
                    // A flying draw grows from wall scale .7 to hand scale .85. Apply local endpoint
                    // spacing/posture by that same eased fraction, keeping its exact wall origin.
                    float alignment = Math.clamp((motion.scale() - .7F) / (old.scale() - .7F), 0, 1);
                    pose = new TileMotion.Pose(motion.x() + (tile.x() - old.x()) * alignment,
                            motion.y() + (tile.y() - old.y()) * alignment,
                            motion.z() + (tile.z() - old.z()) * alignment,
                            motion.yaw(), flat ? -90 : motion.pitch(), motion.scale());
                }
                result.add(new TableAnimationPlayer.Frame(tile, pose,source==null?0:source.stateHash(),
                        source==null?null:source.transition()));
            }
        }
        return List.copyOf(result);
    }

    static TableTileLayout.Tile handTile(TableScenePayload scene, int seat, int index, int physicalTile, boolean overhead) {
        int side = scene.sides().get(seat);
        boolean flat = scene.finished() || overhead && (scene.ownSeat() < 0 || seat == scene.ownSeat());
        float x = -17.65F + index * 1.82F + (physicalTile == scene.drawnTiles().get(seat) ? .75F : 0);
        float z = 20.5F;
        float rx = switch (side) { case 1 -> z; case 2 -> -x; case 3 -> -z; default -> x; };
        float rz = switch (side) { case 1 -> -x; case 2 -> -z; case 3 -> x; default -> z; };
        float scale = .85F, y = .02F + (flat ? 1.25F : 2.75F) * scale / 2;
        return new TableTileLayout.Tile(physicalTile, rx, y, rz, side * 90, flat, false,
                scale, TableTileLayout.Group.HAND, seat, "h:" + seat + ":" + index);
    }

    static boolean matches(TableDisplayPayload table, TableScenePayload scene) {
        return scene != null && scene.gameId() != null && table.tableId().equals(scene.tableId())
                && table.dimension().equals(scene.dimension()) && table.pos().equals(scene.pos())
                && table.snapshot().gameId().equals(scene.gameId())
                && table.snapshot().seats().stream().map(dev.riichicraft.network.TableDisplaySnapshot.Seat::side).toList().equals(scene.sides());
    }

    private static List<TableAnimationPlayer.Frame> selectedFrame(List<TableAnimationPlayer.Frame> frames, int own, int selected) {
        if (own < 0 || selected < 0) return frames;
        return frames.stream().map(frame -> {
            var tile = frame.tile();
            if (tile.group() != TableTileLayout.Group.HAND || tile.seat() != own || tile.face() != selected) return frame;
            var pose = frame.pose();
            return new TableAnimationPlayer.Frame(tile, new TileMotion.Pose(pose.x(), pose.y() + .35F, pose.z(),
                    pose.yaw(), pose.pitch(), pose.scale() * 1.05F),frame.stateHash(),frame.transition());
        }).toList();
    }
}
