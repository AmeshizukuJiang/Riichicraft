package dev.riichicraft.server;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbyClosedPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Temporary, pre-game lobbies. Every instance is confined to its server's game thread. */
public final class LobbyService {
    private static final Map<MinecraftServer, LobbyService> SERVICES = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final LobbyRegistry registry = new LobbyRegistry();
    private final Map<TableKey, UUID> tables = new HashMap<>();
    private final Map<UUID, TableKey> locations = new HashMap<>();
    private final Map<UUID, View> views = new HashMap<>();

    private record TableKey(ResourceKey<Level> dimension, BlockPos pos) { }
    private record View(UUID id, UUID tableId) { }

    private LobbyService(MinecraftServer server) {
        this.server = server;
    }

    public static LobbyService get(MinecraftServer server) {
        return SERVICES.computeIfAbsent(server, LobbyService::new);
    }

    public static void initialize() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> get(server).disconnect(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LobbyService service = SERVICES.get(server);
            if (service != null) service.tick();
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            LobbyService service = SERVICES.get(level.getServer());
            if (service != null) service.unloadChunk(level, chunk.getPos().x(), chunk.getPos().z());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVICES.remove(server));
    }

    public UUID open(ServerPlayer player, BlockPos pos) {
        TableKey key = new TableKey(player.level().dimension(), pos.immutable());
        if (!accessible(player, key)) return null;
        UUID tableId = tables.computeIfAbsent(key, ignored -> {
            UUID id = UUID.randomUUID();
            locations.put(id, key);
            registry.createTable(id);
            return id;
        });
        View old = views.remove(player.getUUID());
        if (old != null) sendClosed(player, old, "");
        View view = new View(UUID.randomUUID(), tableId);
        views.put(player.getUUID(), view);
        sendSnapshot(player, view, registry.snapshot(tableId).orElseThrow(), "");
        return view.id();
    }

    public void handleAction(ServerPlayer player, LobbyActionPayload payload) {
        View view = views.get(player.getUUID());
        if (view == null || !view.id().equals(payload.viewId())) return;
        if (payload.action() == LobbyActionPayload.CLOSE) {
            views.remove(player.getUUID());
            return;
        }
        TableKey location = locations.get(view.tableId());
        if (location == null || !accessible(player, location)) {
            views.remove(player.getUUID());
            sendClosed(player, view, "message.riichicraft.lobby.unavailable");
            return;
        }
        LobbyRegistry.Result result = switch (payload.action()) {
            case LobbyActionPayload.JOIN -> registry.join(view.tableId(), player.getUUID(),
                    player.getGameProfile().name(), payload.revision());
            case LobbyActionPayload.SET_READY -> registry.setReady(view.tableId(), player.getUUID(),
                    payload.ready(), payload.revision());
            case LobbyActionPayload.LEAVE -> registry.leave(view.tableId(), player.getUUID(), payload.revision());
            default -> null;
        };
        if (result == null) {
            sendSnapshot(player, view, registry.snapshot(view.tableId()).orElseThrow(),
                    "message.riichicraft.lobby.invalid_action");
        } else if (result.snapshot() != null) {
            String feedback = feedback(result.status());
            if (result.changed()) broadcast(result.snapshot(), player.getUUID(), feedback);
            else sendSnapshot(player, view, result.snapshot(), feedback);
        }
    }

    public void disconnect(ServerPlayer player) {
        views.remove(player.getUUID());
        registry.disconnect(player.getUUID()).ifPresent(snapshot -> broadcast(snapshot, null, ""));
    }

    public void removeTable(ServerLevel level, BlockPos pos) {
        TableKey key = new TableKey(level.dimension(), pos.immutable());
        UUID id = tables.remove(key);
        if (id == null) return;
        locations.remove(id);
        registry.removeTable(id);
        for (Map.Entry<UUID, View> entry : List.copyOf(views.entrySet())) {
            if (entry.getValue().tableId().equals(id)) {
                views.remove(entry.getKey());
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) sendClosed(player, entry.getValue(), "message.riichicraft.lobby.unavailable");
            }
        }
    }

    private void unloadChunk(ServerLevel level, int chunkX, int chunkZ) {
        for (TableKey key : List.copyOf(tables.keySet())) {
            if (key.dimension().equals(level.dimension()) && key.pos().getX() >> 4 == chunkX
                    && key.pos().getZ() >> 4 == chunkZ) removeTable(level, key.pos());
        }
    }

    private void tick() {
        for (TableKey key : List.copyOf(tables.keySet())) {
            ServerLevel level = server.getLevel(key.dimension());
            if (level != null && (!level.isLoaded(key.pos())
                    || !level.getBlockState(key.pos()).is(ModBlocks.MAHJONG_TABLE))) {
                removeTable(level, key.pos());
            }
        }
        for (Map.Entry<UUID, View> entry : List.copyOf(views.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue; // Unconnected native GameTest players are not in the player list.
            TableKey key = locations.get(entry.getValue().tableId());
            if (key == null || !accessible(player, key)) {
                views.remove(entry.getKey());
                sendClosed(player, entry.getValue(), "message.riichicraft.lobby.unavailable");
            }
        }
    }

    private boolean accessible(ServerPlayer player, TableKey key) {
        return player.isAlive() && !player.isSpectator() && player.level().dimension().equals(key.dimension())
                && player.level().isLoaded(key.pos())
                && player.level().getBlockState(key.pos()).is(ModBlocks.MAHJONG_TABLE)
                && player.isWithinBlockInteractionRange(key.pos(), 1.0);
    }

    private void broadcast(LobbyRegistry.Snapshot snapshot, UUID actor, String feedback) {
        for (Map.Entry<UUID, View> entry : views.entrySet()) {
            if (!entry.getValue().tableId().equals(snapshot.tableId())) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) sendSnapshot(player, entry.getValue(), snapshot,
                    entry.getKey().equals(actor) ? feedback : "");
        }
    }

    private void sendSnapshot(ServerPlayer player, View view, LobbyRegistry.Snapshot snapshot, String feedback) {
        if (player.connection == null || !ServerPlayNetworking.canSend(player, LobbySnapshotPayload.TYPE)) return;
        int ownSeat = -1;
        for (LobbyRegistry.Seat seat : snapshot.seats()) {
            if (player.getUUID().equals(seat.playerId())) ownSeat = seat.index();
        }
        var seats = snapshot.seats().stream().map(seat ->
                new LobbySnapshotPayload.Seat(seat.playerId(), seat.name(), seat.ready())).toList();
        ServerPlayNetworking.send(player, new LobbySnapshotPayload(view.id(), view.tableId(),
                locations.get(view.tableId()).pos(), snapshot.revision(), seats, ownSeat, snapshot.allReady(), feedback));
    }

    private void sendClosed(ServerPlayer player, View view, String reason) {
        if (player.connection != null && ServerPlayNetworking.canSend(player, LobbyClosedPayload.TYPE)) {
            ServerPlayNetworking.send(player, new LobbyClosedPayload(view.id(), reason));
        }
    }

    private static String feedback(LobbyRegistry.Status status) {
        return switch (status) {
            case UPDATED, UNCHANGED -> "";
            case STALE_REVISION -> "message.riichicraft.lobby.stale";
            case TABLE_FULL -> "message.riichicraft.lobby.full";
            case ALREADY_SEATED_ELSEWHERE -> "message.riichicraft.lobby.other_table";
            case NOT_SEATED -> "message.riichicraft.lobby.not_seated";
            case TABLE_NOT_FOUND -> "message.riichicraft.lobby.unavailable";
        };
    }

    public LobbyRegistry registry() {
        return registry;
    }
}
