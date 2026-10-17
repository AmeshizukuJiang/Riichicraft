package dev.riichicraft.client;

import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.table.TableDisplayCache;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientLevel;

public final class TableDisplayClient {
    private static final TableDisplayCache CACHE = new TableDisplayCache();
    private static ClientLevel level;
    private TableDisplayClient() { }
    static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(TableDisplayPayload.TYPE, (payload, context) -> {
            synchronizeLevel(context.client().level);
            if (level != null) CACHE.accept(payload, level.dimension());
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { CACHE.reset(); level = client.level; });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { CACHE.reset(); level = null; });
        ClientTickEvents.END_CLIENT_TICK.register(client -> synchronizeLevel(client.level));
        TableDisplayRenderer.initialize();
    }
    private static void synchronizeLevel(ClientLevel current) {
        if (current != level) { CACHE.clearTables(); level = current; }
    }
    /** Immutable public state for rendering and actual client integration checks. */
    public static List<TableDisplayPayload> snapshots() { return CACHE.snapshots(); }
}
