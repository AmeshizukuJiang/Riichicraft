package dev.riichicraft.server;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.rules.DiscardResponseWindow;
import dev.riichicraft.rules.FourPlayerRound;
import dev.riichicraft.rules.KanActions;
import dev.riichicraft.rules.Meld;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbyClosedPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Server-thread table ownership, authenticated intentions and temporary round lifecycle. */
public final class LobbyService {
    private static final Map<MinecraftServer, LobbyService> SERVICES = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final LobbyRegistry registry = new LobbyRegistry();
    private final Map<TableKey, UUID> tables = new HashMap<>();
    private final Map<UUID, TableKey> locations = new HashMap<>();
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Prototype> games = new HashMap<>();
    private final Set<UUID> disconnected = new HashSet<>();
    private final Map<UUID, String> pendingNotices = new HashMap<>();
    private static final SecureRandom SEEDS = new SecureRandom();

    private static final class Prototype {
        final FourPlayerRound game;
        final UUID gameId;
        final List<UUID> players;
        long revision;
        boolean paused;
        boolean finished;
        String statusKey = "";
        final Set<UUID> confirmations = new HashSet<>();
        Set<UUID> unavailable = Set.of();

        Prototype(UUID gameId,List<UUID> players,long seed) {
            this.gameId=gameId;
            this.players=List.copyOf(players);
            game=FourPlayerRound.shuffled(gameId,players,0,27,0,List.of(25_000,25_000,25_000,25_000),0,seed);
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
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> get(server).connected(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LobbyService service = SERVICES.get(server);
            if (service != null) service.tick();
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            LobbyService service = SERVICES.get(level.getServer());
            if (service != null) service.unloadChunk(level, chunk.getPos().x(), chunk.getPos().z());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LobbyService service = SERVICES.get(server);
            if (service != null) service.stop();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVICES.remove(server));
    }

    public UUID open(ServerPlayer player, BlockPos pos) {
        TableKey key = new TableKey(player.level().dimension(), pos.immutable());
        if (!accessible(player, key)) return null;
        disconnected.remove(player.getUUID());
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
        Prototype prototype = games.get(tableId);
        if (prototype != null) refreshPresence(tableId, prototype);
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
        games.put(view.tableId(), new Prototype(UUID.randomUUID(), players, SEEDS.nextLong()));
        broadcast(lobby, null, "");
    }

    public void handleGameAction(ServerPlayer player, GameActionPayload payload) {
        View view = views.get(player.getUUID());
        if (view == null || !view.id().equals(payload.viewId())) return;
        Prototype prototype = games.get(view.tableId());
        if (prototype == null || !prototype.gameId.equals(payload.gameId())) return;
        // Recheck in the same server action, closing the gap before the next END_SERVER_TICK.
        refreshPresence(view.tableId(), prototype);
        LobbyRegistry.Snapshot lobby = registry.snapshot(view.tableId()).orElseThrow();
        String feedback = "";
        // Other replies advance the visible revision but never invalidate this same opportunity.
        boolean response=GameActionPayload.isResponse(payload.action());
        if ((!response && payload.revision()!=prototype.revision) || payload.revision()>prototype.revision) feedback = "message.riichicraft.lobby.stale";
        else if (!prototype.players.contains(player.getUUID())) {
            feedback = "message.riichicraft.game.spectator";
        } else if (!accessible(player, locations.get(view.tableId()))) {
            feedback = "message.riichicraft.lobby.unavailable";
        } else if (payload.action() == GameActionPayload.RETURN_LOBBY) {
            if (!prototype.finished) feedback="message.riichicraft.lobby.invalid_action";
            else {
                games.remove(view.tableId()); clearDisconnected(prototype);
                broadcast(registry.resetReadiness(view.tableId()),null,"");
                return;
            }
        } else if (payload.action() == GameActionPayload.ABORT && !prototype.finished) {
            games.remove(view.tableId());
            for (UUID participant : prototype.players) {
                if (disconnected.contains(participant) || server.getPlayerList().getPlayer(participant) == null) {
                    pendingNotices.put(participant, "message.riichicraft.game.aborted");
                }
            }
            clearDisconnected(prototype);
            broadcast(registry.resetReadiness(view.tableId()), null, "message.riichicraft.game.aborted");
            return;
        } else if (prototype.finished) feedback="message.riichicraft.lobby.invalid_action";
        else if (payload.action() == GameActionPayload.RESUME) {
            if (!prototype.paused) feedback = "message.riichicraft.game.resumed";
            else if (!prototype.unavailable.isEmpty()) feedback = "message.riichicraft.game.players_absent";
            else if (prototype.confirmations.add(player.getUUID())) {
                prototype.revision++;
                if (prototype.confirmations.size() == 4) {
                    prototype.paused = false;
                    prototype.statusKey = "";
                    broadcast(lobby, null, "message.riichicraft.game.resumed");
                } else broadcast(lobby, null, "");
                return;
            }
        } else {
            if (prototype.paused) feedback = "message.riichicraft.game.paused";
            else {
                var command=new FourPlayerRound.Command(prototype.gameId,prototype.game.view(player.getUUID()).revision());
                KanActions.Choice kanChoice=null;
                if (payload.action()==GameActionPayload.CLOSED_KAN || payload.action()==GameActionPayload.ADDED_KAN) {
                    try {
                        kanChoice=new KanActions.Choice(payload.action()==GameActionPayload.CLOSED_KAN?KanActions.Kind.CLOSED:KanActions.Kind.ADDED,
                                payload.meldIndex(),payload.handTiles());
                    } catch (IllegalArgumentException invalidSelection) {
                        sendSnapshot(player,view,lobby,"message.riichicraft.lobby.invalid_action");
                        return;
                    }
                }
                FourPlayerRound.Status result=switch(payload.action()) {
                    case GameActionPayload.DISCARD,GameActionPayload.RIICHI -> prototype.game.discard(player.getUUID(),command,payload.tileId(),payload.action()==GameActionPayload.RIICHI);
                    case GameActionPayload.TSUMO -> prototype.game.tsumo(player.getUUID(),command);
                    case GameActionPayload.NINE_TERMINALS -> prototype.game.nineTerminals(player.getUUID(),command);
                    case GameActionPayload.CLOSED_KAN,GameActionPayload.ADDED_KAN -> prototype.game.declareKan(player.getUUID(),command,kanChoice);
                    case GameActionPayload.PASS,GameActionPayload.RON,GameActionPayload.CHI,GameActionPayload.PON,GameActionPayload.OPEN_KAN -> prototype.game.respond(player.getUUID(),
                            new DiscardResponseWindow.Id(prototype.gameId,payload.windowSerial()),responseChoice(payload));
                    default -> FourPlayerRound.Status.NOT_OFFERED;
                };
                if (result==FourPlayerRound.Status.ACCEPTED) {
                    prototype.revision++;
                    prototype.finished=prototype.game.view(player.getUUID()).phase()==FourPlayerRound.Phase.FINISHED;
                    broadcast(lobby, null, "");
                    return;
                }
                feedback = switch (result) {
                    case NOT_YOUR_TURN -> "message.riichicraft.game.not_your_turn";
                    case NOT_OFFERED,WRONG_PHASE -> "message.riichicraft.lobby.invalid_action";
                    default -> "message.riichicraft.lobby.stale";
                };
            }
        }
        sendSnapshot(player, view, lobby, feedback);
    }

    private static DiscardResponseWindow.Choice responseChoice(GameActionPayload payload) {
        return switch(payload.action()) {
            case GameActionPayload.PASS -> DiscardResponseWindow.Simple.PASS;
            case GameActionPayload.RON -> DiscardResponseWindow.Simple.RON;
            default -> new DiscardResponseWindow.Call(switch(payload.action()) {
                case GameActionPayload.CHI -> Meld.Kind.CHI;
                case GameActionPayload.PON -> Meld.Kind.PON;
                case GameActionPayload.OPEN_KAN -> Meld.Kind.OPEN_KAN;
                default -> throw new IllegalArgumentException("Not a response choice");
            },payload.handTiles());
        };
    }

    private boolean present(UUID playerId, UUID tableId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        TableKey location = locations.get(tableId);
        return !disconnected.contains(playerId) && player != null && location != null && accessible(player, location);
    }

    private void connected(ServerPlayer player) {
        disconnected.remove(player.getUUID());
        String notice = pendingNotices.remove(player.getUUID());
        if (notice != null) player.sendOverlayMessage(Component.translatable(notice));
    }

    private void refreshPresence(UUID tableId, Prototype prototype) {
        // A paid result is immutable and remains reopenable even when its players disconnect.
        if (prototype.finished) return;
        Set<UUID> missing = new HashSet<>();
        for (UUID playerId : prototype.players) {
            if (!present(playerId, tableId)) missing.add(playerId);
        }
        boolean changed = !missing.equals(prototype.unavailable);
        boolean newlyMissing = missing.stream().anyMatch(id -> !prototype.unavailable.contains(id));
        if (!missing.isEmpty() && (!prototype.paused || newlyMissing)) {
            prototype.paused = true;
            prototype.confirmations.clear();
            changed = true;
        }
        prototype.unavailable = Set.copyOf(missing);
        String status = !prototype.paused ? "" : missing.isEmpty()
                ? "message.riichicraft.game.awaiting_confirmations" : "message.riichicraft.game.player_unavailable";
        if (!status.equals(prototype.statusKey)) changed = true;
        prototype.statusKey = status;
        if (changed) {
            prototype.revision++;
            broadcast(registry.snapshot(tableId).orElseThrow(), null, "");
        }
    }

    private void clearDisconnected(Prototype prototype) {
        disconnected.removeAll(prototype.players);
    }

    /** A recipient-specific view for server integrations; never returns another player's concealed tiles. */
    public GameSnapshot gameView(UUID tableId, UUID recipient) {
        Prototype prototype = games.get(tableId);
        if (prototype == null) return null;
        FourPlayerRound.View gameView = prototype.game.view(recipient);
        List<GameSnapshot.Seat> seats = gameView.seats().stream().map(seat -> new GameSnapshot.Seat(
                seat.concealedCount(), seat.river().stream().map(FourPlayerRound.RiverTile::tileId).toList(), present(seat.playerId(), tableId),
                prototype.confirmations.contains(seat.playerId()))).toList();
        List<GameSnapshot.SeatRules> seatRules=new ArrayList<>(4);
        for(var seat:gameView.seats()) {
            List<GameSnapshot.MeldView> melds=new ArrayList<>();
            for(int index=0;index<seat.melds().size();index++) {
                var meld=seat.melds().get(index); var origin=seat.origins().get(index);
                melds.add(new GameSnapshot.MeldView(meld.kind().ordinal(),meld.tiles(),origin.providerSeat(),origin.claimedTileId()));
            }
            seatRules.add(new GameSnapshot.SeatRules(melds,seat.river().stream().map(r->
                    new GameSnapshot.RiverMark(r.declaration(),r.tsumogiri(),r.claimedBy())).toList(),seat.points(),seat.riichi().ordinal()));
        }
        var options=gameView.turnOptions();
        List<GameSnapshot.ActionOption> kanOptions=options.kans().stream().map(k->new GameSnapshot.ActionOption(
                k.kind()==KanActions.Kind.CLOSED?GameActionPayload.CLOSED_KAN:GameActionPayload.ADDED_KAN,k.meldIndex(),k.handTiles()))
                .sorted(optionOrder()).toList();
        List<GameSnapshot.ActionOption> responses=gameView.responses().stream().map(LobbyService::responseOption).sorted(optionOrder()).toList();
        var opportunity=gameView.opportunity().map(o->new GameSnapshot.Opportunity(o.id().serial(),o.actorSeat(),o.tileId(),o.kan())).orElse(null);
        var rules=new GameSnapshot.RuleState(gameView.phase().ordinal(),gameView.dealerSeat(),gameView.roundWind(),gameView.honba(),gameView.deposits(),
                gameView.doraIndicators(),seatRules,options.discards().stream().sorted().toList(),options.riichiDiscards().stream().sorted().toList(),
                kanOptions,options.tsumo(),options.nineTerminals(),opportunity,responses,gameView.end().map(LobbyService::resultView).orElse(null));
        return new GameSnapshot(gameView.roundId(),prototype.revision,gameView.actorSeat(),gameView.liveTiles(),seats,
                gameView.ownHand().map(hand->hand.concealedTiles()).orElse(List.of()),gameView.ownDraw().orElse(-1),prototype.paused,prototype.statusKey,rules);
    }

    private static Comparator<GameSnapshot.ActionOption> optionOrder() {
        return Comparator.comparingInt(GameSnapshot.ActionOption::action).thenComparingInt(GameSnapshot.ActionOption::meldIndex)
                .thenComparing(o->o.handTiles().toString());
    }

    private static GameSnapshot.ActionOption responseOption(DiscardResponseWindow.Choice choice) {
        if(choice==DiscardResponseWindow.Simple.PASS) return new GameSnapshot.ActionOption(GameActionPayload.PASS,-1,List.of());
        if(choice==DiscardResponseWindow.Simple.RON) return new GameSnapshot.ActionOption(GameActionPayload.RON,-1,List.of());
        var call=(DiscardResponseWindow.Call)choice;
        int action=switch(call.kind()) { case CHI->GameActionPayload.CHI; case PON->GameActionPayload.PON;
            case OPEN_KAN->GameActionPayload.OPEN_KAN; case CLOSED_KAN->throw new IllegalArgumentException("Closed kan is not a discard response"); };
        return new GameSnapshot.ActionOption(action,-1,call.handTiles());
    }

    private static GameSnapshot.Result resultView(FourPlayerRound.End end) {
        List<GameSnapshot.WinValue> values=end.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry->{
            var v=entry.getValue();
            return new GameSnapshot.WinValue(entry.getKey(),v.winningTileId(),v.yaku().stream().map(y->
                    new GameSnapshot.Award(y.yaku().name(),y.han(),y.yakumanUnits())).toList(),v.han(),v.fu(),v.yakumanUnits(),
                    v.dora(),v.aka(),v.ura(),v.points().payment().totalPoints());
        }).toList();
        return new GameSnapshot.Result(end.kind().ordinal(),end.winners(),end.tenpai().stream().sorted().toList(),values,
                end.transfers().stream().map(p->new GameSnapshot.Payment(p.fromSeat(),p.toSeat(),p.points(),p.kind().ordinal())).toList(),end.dealerContinues());
    }

    public void disconnect(ServerPlayer player) {
        views.remove(player.getUUID());
        UUID tableId = registry.tableForPlayer(player.getUUID()).orElse(null);
        Prototype prototype = games.get(tableId);
        if (prototype != null) {
            disconnected.add(player.getUUID());
            refreshPresence(tableId, prototype);
            return;
        }
        registry.disconnect(player.getUUID()).ifPresent(snapshot -> broadcast(snapshot, null, ""));
    }

    public void removeTable(ServerLevel level, BlockPos pos) {
        TableKey key = new TableKey(level.dimension(), pos.immutable());
        UUID id = tables.remove(key);
        if (id == null) return;
        locations.remove(id);
        Prototype prototype = games.remove(id);
        String reason = prototype == null ? "message.riichicraft.lobby.unavailable"
                : "message.riichicraft.game.terminated";
        if (prototype != null) {
            for (UUID playerId : prototype.players) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                View ownView = views.get(playerId);
                if (player == null || disconnected.contains(playerId)) pendingNotices.put(playerId, reason);
                else if (ownView == null || !ownView.tableId().equals(id)) {
                    player.sendOverlayMessage(Component.translatable(reason));
                }
            }
            clearDisconnected(prototype);
        }
        registry.removeTable(id);
        for (Map.Entry<UUID, View> entry : List.copyOf(views.entrySet())) {
            if (entry.getValue().tableId().equals(id)) {
                views.remove(entry.getKey());
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) sendClosed(player, entry.getValue(), reason);
            }
        }
    }

    private void unloadChunk(ServerLevel level, int chunkX, int chunkZ) {
        for (TableKey key : List.copyOf(tables.keySet())) {
            if (key.dimension().equals(level.dimension()) && key.pos().getX() >> 4 == chunkX
                    && key.pos().getZ() >> 4 == chunkZ) removeTable(level, key.pos());
        }
    }

    public void tick() {
        for (TableKey key : List.copyOf(tables.keySet())) {
            ServerLevel level = server.getLevel(key.dimension());
            if (level != null && (!level.isLoaded(key.pos())
                    || !level.getBlockState(key.pos()).is(ModBlocks.MAHJONG_TABLE))) {
                removeTable(level, key.pos());
            }
        }
        for (Map.Entry<UUID, Prototype> entry : List.copyOf(games.entrySet())) {
            refreshPresence(entry.getKey(), entry.getValue());
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

    /** Temporary prototypes intentionally have no saved state in this version. */
    public void stop() {
        for (TableKey key : List.copyOf(tables.keySet())) {
            ServerLevel level = server.getLevel(key.dimension());
            if (level != null) removeTable(level, key.pos());
        }
        games.clear();
        views.clear();
        tables.clear();
        locations.clear();
        disconnected.clear();
        pendingNotices.clear();
        registry.clear();
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
