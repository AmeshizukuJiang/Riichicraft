package dev.riichicraft.client;

import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.network.TableDisplaySnapshot;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.core.BlockPos;

/** Test-only layout contact sheet; it does not alter server games or the real packet cache. */
public final class TabletopRenderFixture {
    private static boolean registered;
    private static TableDisplaySnapshot scene;
    private static BlockPos pos;
    public static void show(BlockPos center, TableDisplaySnapshot snapshot) {
        if (!registered) {
            registered = true;
            LevelExtractionEvents.END_EXTRACTION.register(context -> {
                if (scene != null) TableDisplayRenderer.extract(context, List.of(new TableDisplayPayload(
                        context.level().dimension(), new UUID(0, 42), pos, 0, scene)));
            });
        }
        pos = center; scene = snapshot;
    }
    public static void hide() { scene = null; }
}
