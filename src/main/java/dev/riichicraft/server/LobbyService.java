package dev.riichicraft.server;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.game.DrawDiscardGame;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbyClosedPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;
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
    private final Map<UUID, Prototype> games = new HashMap<>();
    private static final SecureRandom SEEDS = new SecureRandom();

    private static final class Prototype {
        final DrawDiscardGame game;
        long revision;
        boolean paused;
        String statusKey = "";
        final java.util.Set<UUID> confirmations = new java.util.HashSet<>();

        Prototype(DrawDiscardGame game) {
            this.game = game;
        }
    }

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
        if (games.containsKey(view.tableId())) {
            sendSnapshot(player, view, registry.snapshot(view.tableId()).orElseThrow(),
                    "message.riichicraft.game.in_progress");
            return;
        }
        if (payload.action() == LobbyActionPayload.START) {
            startPrototype(player, view, payload.revision());
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

    private void startPrototype(ServerPlayer player, View view, long expectedRevision) {
        LobbyRegistry.Snapshot lobby = registry.snapshot(view.tableId()).orElseThrow();
        String feedback = "";
        if (lobby.revision() != expectedRevision) feedback = "message.riichicraft.lobby.stale";
        else if (!player.getUUID().equals(lobby.seats().getFirst().playerId())) {
            feedback = "message.riichicraft.game.east_starts";
        } else if (!lobby.allReady()) feedback = "message.riichicraft.game.not_ready";
        else if (lobby.seats().stream().anyMatch(seat -> !present(seat.playerId(), view.tableId()))) {
            feedback = "message.riichicraft.game.players_absent";
        }
        if (!feedback.isEmpty()) {
            sendSnapshot(player, view, lobby, feedback);
            return;
        }
        List<UUID> players = lobby.seats().stream().map(LobbyRegistry.Seat::playerId).toList();
        games.put(view.tableId(), new Prototype(new DrawDiscardGame(UUID.randomUUID(), players, SEEDS.nextLong())));
        broadcast(lobby, null, "");
    }

    public void handleGameAction(ServerPlayer player, GameActionPayload payload) {
        View view = views.get(player.getUUID());
        if (view == null || !view.id().equals(payload.viewId())) return;
        Prototype prototype = games.get(view.tableId());
        if (prototype == null || !prototype.game.gameId().equals(payload.gameId())) return;
        LobbyRegistry.Snapshot lobby = registry.snapshot(view.tableId()).orElseThrow();
        String feedback = "";
        if (payload.revision() != prototype.revision) feedback = "message.riichicraft.lobby.stale";
        else if (!prototype.game.players().contains(player.getUUID())) {
            feedback = "message.riichicraft.game.spectator";
        } else if (!accessible(player, locations.get(view.tableId()))) {
            feedback = "message.riichicraft.lobby.unavailable";
        } else if (payload.action() == GameActionPayload.DISCARD) {
            if (prototype.paused) feedback = "message.riichicraft.game.paused";
            else {
                DrawDiscardGame.Result result = prototype.game.discard(player.getUUID(), payload.tileId(),
                        prototype.game.revision());
                if (result.changed()) {
                    prototype.revision++;
                    if (prototype.game.isFinished()) {
                        games.remove(view.tableId());
                        lobby = registry.resetReadiness(view.tableId());
                        broadcast(lobby, null, "message.riichicraft.game.exhausted");
                    } else broadcast(lobby, null, "");
                    return;
                }
                feedback = switch (result.status()) {
                    case NOT_YOUR_TURN -> "message.riichicraft.game.not_your_turn";
                    case TILE_NOT_IN_HAND -> "message.riichicraft.game.invalid_tile";
                    default -> "message.riichicraft.lobby.stale";
                };
            }
        } else feedback = "message.riichicraft.lobby.invalid_action";
        sendSnapshot(player, view, lobby, feedback);
    }

    private boolean present(UUID playerId, UUID tableId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        TableKey location = locations.get(tableId);
        return player != null && location != null && accessible(player, location);
    }

    /** A recipient-specific view for server integrations; never returns another player's concealed tiles. */
    public GameSnapshot gameView(UUID tableId, UUID recipient) {
        Prototype prototype = games.get(tableId);
        if (prototype == null) return null;
        DrawDiscardGame.View gameView = prototype.game.view(recipient);
        List<GameSnapshot.Seat> seats = gameView.seats().stream().map(seat -> new GameSnapshot.Seat(
                seat.handCount(), seat.discards(), present(seat.playerId(), tableId),
                prototype.confirmations.contains(seat.playerId()))).toList();
        return new GameSnapshot(gameView.gameId(), prototype.revision, gameView.currentSeat(),
                gameView.liveWallCount(), seats, gameView.closedHand(),
                gameView.drawnTile() == null ? -1 : gameView.drawnTile(), prototype.paused, prototype.statusKey);
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
        games.remove(id);
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
                    actor == null || entry.getKey().equals(actor) ? feedback : "");
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
                locations.get(view.tableId()).pos(), snapshot.revision(), seats, ownSeat, snapshot.allReady(), feedback,
                gameView(view.tableId(), player.getUUID())));
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
