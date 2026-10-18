package dev.riichicraft.client;

import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.table.TableDisplayCache;
import dev.riichicraft.table.TableAnimationPlayer;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientLevel;

public final class TableDisplayClient {
    private static final TableDisplayCache CACHE = new TableDisplayCache();
    private static final TableAnimationPlayer ANIMATIONS=new TableAnimationPlayer();
    private static ClientLevel level;
    private TableDisplayClient() { }
    static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(TableDisplayPayload.TYPE, (payload, context) -> {
            synchronizeLevel(context.client().level);
            if (level != null && CACHE.accept(payload, level.dimension())) {
                ANIMATIONS.accept(payload,System.nanoTime());
                ANIMATIONS.retain(CACHE.snapshots().stream().map(TableDisplayPayload::tableId).collect(java.util.stream.Collectors.toSet()));
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { CACHE.reset(); ANIMATIONS.clear();level = client.level; });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { CACHE.reset(); ANIMATIONS.clear();level = null; });
        ClientTickEvents.END_CLIENT_TICK.register(client -> synchronizeLevel(client.level));
        TableDisplayRenderer.initialize();
    }
    private static void synchronizeLevel(ClientLevel current) {
        if (current != level) { CACHE.clearTables(); ANIMATIONS.clear();level = current; }
    }
    /** Immutable public state for rendering and actual client integration checks. */
    public static List<TableDisplayPayload> snapshots() { return CACHE.snapshots(); }
    public static List<TableAnimationPlayer.Frame> frames(TableDisplayPayload table,long now){
        var frames=ANIMATIONS.frames(table.tableId(),now);
        return frames.isEmpty()?TableAnimationPlayer.still(table.snapshot()):frames;
    }
    public static boolean animating(java.util.UUID id){return ANIMATIONS.animating(id,System.nanoTime());}
}
