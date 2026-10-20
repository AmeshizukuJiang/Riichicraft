package dev.riichicraft.server;

import dev.riichicraft.Riichicraft;
import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.block.TableStructure;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.persistence.TableArchiveStore;
import dev.riichicraft.rules.DiscardResponseWindow;
import dev.riichicraft.rules.FourPlayerRound;
import dev.riichicraft.rules.ThreePlayerRound;
import dev.riichicraft.rules.ThreePlayerMatch;
import dev.riichicraft.rules.ThreePlayerMatchRules;
import dev.riichicraft.rules.ThreePlayerArchiveCodec;
import dev.riichicraft.rules.FourPlayerMatch;
import dev.riichicraft.rules.GameArchiveCodec;
import dev.riichicraft.rules.MatchRules;
import dev.riichicraft.rules.KanActions;
import dev.riichicraft.rules.Meld;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbyClosedPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import dev.riichicraft.network.TableDisplaySnapshot;
import dev.riichicraft.network.TableScenePayload;
import dev.riichicraft.table.TableHandVisibility;
import dev.riichicraft.entity.MahjongSeatEntity;
import dev.riichicraft.entity.ModEntities;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.core.Direction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.security.SecureRandom;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Server-thread table ownership, authenticated intentions and durable world lifecycle. */
public final class LobbyService {
    private static final Map<MinecraftServer, LobbyService> SERVICES = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final LobbyRegistry registry = new LobbyRegistry();
    private final Map<TableKey, UUID> tables = new HashMap<>();
    private final Map<UUID, TableKey> locations = new HashMap<>();
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, MahjongSeatEntity> mountedSeats = new HashMap<>();
    private final Set<UUID> seatedTables = new HashSet<>();
    private final Map<UUID, Prototype> games = new HashMap<>();
    private final Set<UUID> disconnected = new HashSet<>();
    private final Map<UUID, String> pendingNotices = new HashMap<>();
    private final Path archivePath;
    private final ArchiveWriter archiveWriter;
    /** Invalid core records remain byte-for-byte present and continue reserving their player UUIDs. */
    private final Map<UUID, TableArchiveStore.Table> blockedTables = new HashMap<>();
    private final Set<UUID> unloadedTables = new HashSet<>();
    private final Map<UUID,Integer> structureCounts = new HashMap<>();
    private final TableDisplaySync tableDisplaySync = new TableDisplaySync();
    private final Map<UUID,List<Integer>> displaySides = new HashMap<>();
    private int displayTicks;
    private boolean archiveBlocked;
    private boolean archiveDirty;
    private boolean saveFailed;
    private boolean stopping;
    private boolean detached;
    private static final SecureRandom SEEDS = new SecureRandom();
    // Injection stays package scoped; the separate GameTest mod supplies deterministic timing.
    static java.util.function.IntSupplier responseDelayDefaults = ()->ResponseDelay.sample(SEEDS.nextDouble(),SEEDS.nextDouble());
    java.util.function.IntSupplier responseDelaySource = responseDelayDefaults;

    private static final class Prototype {
        final FourPlayerRound game;
        final FourPlayerMatch match;
        final ThreePlayerRound threeGame;
        final ThreePlayerMatch threeMatch;
        UUID gameId;
        final List<UUID> players;
        long revision;
        boolean paused;
        boolean finished;
        String statusKey = "";
        final Set<UUID> confirmations = new HashSet<>();
        Set<UUID> unavailable = Set.of();
        int responseDelayTicks;
        List<GameSnapshot> waitingHands;
        GameSnapshot waitingPublic;

        void clearResponseDelay() { responseDelayTicks=0;waitingHands=null;waitingPublic=null; }

        Prototype(UUID id,List<UUID> players,long seed,int mode) {
            this.players=List.copyOf(players);
            if(mode>=3) {
                game=null; match=null;
                if(mode%3==0) { threeGame=ThreePlayerRound.shuffled(id,players,0,27,0,List.of(35000,35000,35000),0,seed); threeMatch=null; }
                else { threeGame=null; threeMatch=new ThreePlayerMatch(id,players,mode%3==1?ThreePlayerMatchRules.Format.EAST:ThreePlayerMatchRules.Format.SOUTH,0,LobbyService::newThreeRound); }
            } else if(mode==LobbyRegistry.SINGLE) {
                threeGame=null; threeMatch=null;
                game=FourPlayerRound.shuffled(id,players,0,27,0,List.of(25_000,25_000,25_000,25_000),0,seed);
                match=null;
            } else {
                threeGame=null; threeMatch=null;
                game=null;
                match=new FourPlayerMatch(id,players,mode==LobbyRegistry.EAST?MatchRules.Format.EAST:MatchRules.Format.SOUTH,0,
                        LobbyService::newRound);
            }
            syncStatus();
        }

        Prototype(TableArchiveStore.Table table) {
            players=table.seats().stream().map(TableArchiveStore.Seat::playerId).toList();
            if(table.mode()>=3) {
                game=null; match=null;
                if(table.mode()%3==0) { threeGame=ThreePlayerArchiveCodec.decodeRound(table.gameData()); threeMatch=null; }
                else { threeGame=null; threeMatch=ThreePlayerArchiveCodec.decodeMatch(table.gameData(),LobbyService::newThreeRound);
                    if(threeMatch.view(null).format()!=(table.mode()%3==1?ThreePlayerMatchRules.Format.EAST:ThreePlayerMatchRules.Format.SOUTH)) throw new IllegalArgumentException("Saved sanma format mismatch"); }
            } else if(table.mode()==LobbyRegistry.SINGLE) {
                threeGame=null; threeMatch=null;
                game=GameArchiveCodec.decodeRound(table.gameData()); match=null;
            } else {
                threeGame=null; threeMatch=null; game=null; match=GameArchiveCodec.decodeMatch(table.gameData(),LobbyService::newRound);
                MatchRules.Format expected=table.mode()==LobbyRegistry.EAST?MatchRules.Format.EAST:MatchRules.Format.SOUTH;
                if(match.view(null).format()!=expected) throw new IllegalArgumentException("Saved format mismatch");
            }
            if(!(three()?threeView(null).seats().stream().map(ThreePlayerRound.PublicSeat::playerId).toList():roundView(null).seats().stream().map(FourPlayerRound.PublicSeat::playerId).toList()).equals(players)) {
                throw new IllegalArgumentException("Saved participant mismatch");
            }
            revision=Math.incrementExact(table.gameRevision());
            syncStatus();
            if(intermission()) clearNext();
            else if(!finished) paused=true;
            unavailable=Set.copyOf(players);
            if(!finished) statusKey="message.riichicraft.game.player_unavailable";
        }

        FourPlayerRound.View roundView(UUID recipient) { return match==null?game.view(recipient):match.view(recipient).round(); }
        boolean three() { return threeGame!=null || threeMatch!=null; }
        ThreePlayerRound.View threeView(UUID recipient) { return threeMatch==null?threeGame.view(recipient):threeMatch.view(recipient).round(); }
        int addedKanTile(int seat, int index) {
            if (three()) return threeMatch == null ? threeGame.addedKanTile(seat,index) : threeMatch.addedKanTile(seat,index);
            return match == null ? game.addedKanTile(seat,index) : match.addedKanTile(seat,index);
        }
        boolean intermission() { return three()?threeMatch!=null && threeMatch.view(null).phase()==ThreePlayerMatch.Phase.ROUND_RESULT:match!=null && match.view(null).phase()==FourPlayerMatch.Phase.ROUND_RESULT; }
        boolean clearNext() { return three()?threeMatch.clearNextConfirmations():match.clearNextConfirmations(); }
        byte[] archive() { return three()?(threeMatch==null?ThreePlayerArchiveCodec.encodeRound(threeGame):ThreePlayerArchiveCodec.encodeMatch(threeMatch)):(match==null?GameArchiveCodec.encodeRound(game):GameArchiveCodec.encodeMatch(match)); }
        FourPlayerRound.Status confirmNext(UUID player,long serial) {
            if(three()) { var v=threeMatch.view(player); return FourPlayerRound.Status.valueOf(threeMatch.confirmNext(player,new ThreePlayerMatch.ContinueId(v.matchId(),gameId,serial)).name()); }
            var v=match.view(player); return match.confirmNext(player,new FourPlayerMatch.ContinueId(v.matchId(),gameId,serial));
        }

        void syncStatus() {
            if(three()) {
                gameId=threeView(null).roundId(); finished=threeMatch==null?threeView(null).phase()==ThreePlayerRound.Phase.FINISHED:threeMatch.view(null).phase()==ThreePlayerMatch.Phase.MATCH_RESULT;
                if(finished || intermission()) { paused=false; confirmations.clear(); statusKey=""; } return;
            }
            gameId=roundView(null).roundId();
            finished=match==null?roundView(null).phase()==FourPlayerRound.Phase.FINISHED
                    :match.view(null).phase()==FourPlayerMatch.Phase.MATCH_RESULT;
            if(finished || intermission()) { paused=false; confirmations.clear(); statusKey=""; }
        }

        FourPlayerRound.Status act(UUID player,GameActionPayload payload,KanActions.Choice choice) {
            if(three()) return FourPlayerRound.Status.valueOf(actThree(player,payload,choice).name());
            if(match!=null) {
                var command=match.view(player).command();
                return switch(payload.action()) {
                    case GameActionPayload.DISCARD,GameActionPayload.RIICHI -> match.discard(player,command,payload.tileId(),payload.action()==GameActionPayload.RIICHI);
                    case GameActionPayload.TSUMO -> match.tsumo(player,command);
                    case GameActionPayload.NINE_TERMINALS -> match.nineTerminals(player,command);
                    case GameActionPayload.CLOSED_KAN,GameActionPayload.ADDED_KAN -> match.declareKan(player,command,choice);
                    case GameActionPayload.PASS,GameActionPayload.RON,GameActionPayload.CHI,GameActionPayload.PON,GameActionPayload.OPEN_KAN -> match.respond(player,
                            new DiscardResponseWindow.Id(gameId,payload.windowSerial()),responseChoice(payload));
                    default -> FourPlayerRound.Status.NOT_OFFERED;
                };
            }
            var command=game.view(player).command();
            return switch(payload.action()) {
                case GameActionPayload.DISCARD,GameActionPayload.RIICHI -> game.discard(player,command,payload.tileId(),payload.action()==GameActionPayload.RIICHI);
                case GameActionPayload.TSUMO -> game.tsumo(player,command);
                case GameActionPayload.NINE_TERMINALS -> game.nineTerminals(player,command);
                case GameActionPayload.CLOSED_KAN,GameActionPayload.ADDED_KAN -> game.declareKan(player,command,choice);
                case GameActionPayload.PASS,GameActionPayload.RON,GameActionPayload.CHI,GameActionPayload.PON,GameActionPayload.OPEN_KAN -> game.respond(player,
                        new DiscardResponseWindow.Id(gameId,payload.windowSerial()),responseChoice(payload));
                default -> FourPlayerRound.Status.NOT_OFFERED;
            };
        }
        private ThreePlayerRound.Status actThree(UUID player,GameActionPayload payload,KanActions.Choice choice) {
            if(threeMatch!=null) {
                var command=threeMatch.view(player).command();
                return switch(payload.action()) {
                    case GameActionPayload.DISCARD,GameActionPayload.RIICHI -> threeMatch.discard(player,command,payload.tileId(),payload.action()==GameActionPayload.RIICHI);
                    case GameActionPayload.KITA -> threeMatch.kita(player,command,payload.tileId());
                    case GameActionPayload.TSUMO -> threeMatch.tsumo(player,command);
                    case GameActionPayload.NINE_TERMINALS -> threeMatch.nineTerminals(player,command);
                    case GameActionPayload.CLOSED_KAN,GameActionPayload.ADDED_KAN -> threeMatch.declareKan(player,command,choice);
                    case GameActionPayload.PASS,GameActionPayload.RON,GameActionPayload.CHI,GameActionPayload.PON,GameActionPayload.OPEN_KAN -> threeMatch.respond(player,
                            new DiscardResponseWindow.Id(gameId,payload.windowSerial()),responseChoice(payload));
                    default -> ThreePlayerRound.Status.NOT_OFFERED;
                };
            }
            var command=threeGame.view(player).command();
            return switch(payload.action()) {
                case GameActionPayload.DISCARD,GameActionPayload.RIICHI -> threeGame.discard(player,command,payload.tileId(),payload.action()==GameActionPayload.RIICHI);
                case GameActionPayload.KITA -> threeGame.kita(player,command,payload.tileId());
                case GameActionPayload.TSUMO -> threeGame.tsumo(player,command);
                case GameActionPayload.NINE_TERMINALS -> threeGame.nineTerminals(player,command);
                case GameActionPayload.CLOSED_KAN,GameActionPayload.ADDED_KAN -> threeGame.declareKan(player,command,choice);
                case GameActionPayload.PASS,GameActionPayload.RON,GameActionPayload.CHI,GameActionPayload.PON,GameActionPayload.OPEN_KAN -> threeGame.respond(player,
                        new DiscardResponseWindow.Id(gameId,payload.windowSerial()),responseChoice(payload));
                default -> ThreePlayerRound.Status.NOT_OFFERED;
            };
        }
    }

    private record TableKey(ResourceKey<Level> dimension, BlockPos pos) { }
    private record View(UUID id, UUID tableId, boolean immersive) { }
    @FunctionalInterface interface ArchiveWriter {
        void write(Path path,List<TableArchiveStore.Table> tables) throws IOException;
    }

    private LobbyService(MinecraftServer server) {
        this(server,server.getWorldPath(LevelResource.ROOT).resolve("data/riichicraft/tables.dat"));
    }

    /** Package access supports isolated, real-server lifecycle tests with a project-local archive. */
    LobbyService(MinecraftServer server,Path archivePath) {
        this(server,archivePath,TableArchiveStore::write);
    }

    LobbyService(MinecraftServer server,Path archivePath,ArchiveWriter archiveWriter) {
        this.server = server;
        this.archivePath=archivePath;
        this.archiveWriter=archiveWriter;
        restore();
    }

    private static FourPlayerRound newRound(FourPlayerMatch.RoundSetup setup) {
        return FourPlayerRound.shuffled(UUID.randomUUID(),setup.players(),setup.dealerSeat(),setup.roundWind(),
                setup.honba(),setup.points(),setup.deposits(),SEEDS.nextLong());
    }
    private static ThreePlayerRound newThreeRound(ThreePlayerMatch.RoundSetup setup) {
        return ThreePlayerRound.shuffled(UUID.randomUUID(),setup.players(),setup.dealerSeat(),setup.roundWind(),
                setup.honba(),setup.points(),setup.deposits(),SEEDS.nextLong());
    }

    public static LobbyService get(MinecraftServer server) {
        return SERVICES.computeIfAbsent(server, LobbyService::new);
    }

    private void restore() {
        final List<TableArchiveStore.Table> archived;
        try {
            archived=TableArchiveStore.read(archivePath);
            // Validate the complete ownership index before publishing any restored seat.
            LobbyRegistry validation=new LobbyRegistry();
            for(var table:archived) {
                Identifier.parse(table.dimension());
                validation.restoreTable(table.tableId(),table.lobbyRevision(),table.mode(),restoredSeats(table));
            }
        } catch(IOException | IllegalArgumentException invalid) {
            archiveBlocked=true;
            Riichicraft.LOGGER.error("Riichicraft world archive could not be loaded; table access is blocked until administrator repair: {}",archivePath);
            return;
        }
        for(var table:archived) {
            TableKey key=new TableKey(ResourceKey.create(Registries.DIMENSION,Identifier.parse(table.dimension())),
                    new BlockPos(table.x(),table.y(),table.z()));
            tables.put(key,table.tableId()); locations.put(table.tableId(),key);
            if(!table.chairSides().isEmpty()) {
                seatedTables.add(table.tableId());
                displaySides.put(table.tableId(),table.chairSides());
            }
            registry.restoreTable(table.tableId(),table.lobbyRevision(),table.mode(),restoredSeats(table));
            try {
                games.put(table.tableId(),new Prototype(table));
            } catch(RuntimeException invalidCore) {
                blockedTables.put(table.tableId(),table);
                Riichicraft.LOGGER.error("Riichicraft table {} has an invalid game archive; its original record and reserved seats are retained for administrator repair",table.tableId());
            }
        }
        archiveDirty=!archived.isEmpty();
    }

    private static List<LobbyRegistry.Seat> restoredSeats(TableArchiveStore.Table table) {
        List<LobbyRegistry.Seat> seats=new ArrayList<>(4);
        for(int index=0;index<table.seats().size();index++) {
            var seat=table.seats().get(index);
            seats.add(new LobbyRegistry.Seat(index,seat.playerId(),seat.name(),false));
        }
        return seats;
    }

    /** Checkpoint only accepted server state. Called on the server thread by native autosave and shutdown. */
    public boolean save() {
        if(archiveBlocked || detached) return false;
        if(!archiveDirty) return !saveFailed;
        try {
            List<TableArchiveStore.Table> archived=new ArrayList<>(blockedTables.values());
            for(var entry:games.entrySet()) {
                UUID tableId=entry.getKey(); Prototype prototype=entry.getValue();
                TableKey key=locations.get(tableId); var lobby=registry.snapshot(tableId).orElseThrow();
                byte[] core=prototype.archive();
                archived.add(new TableArchiveStore.Table(tableId,key.dimension().identifier().toString(),key.pos().getX(),key.pos().getY(),
                        key.pos().getZ(),lobby.mode(),lobby.revision(),prototype.revision,lobby.seats().stream()
                        .map(seat->new TableArchiveStore.Seat(seat.playerId(),seat.name())).toList(),core,
                        seatedTables.contains(tableId)?chairSides(tableId):List.of()));
            }
            archived.sort(Comparator.comparing(table->table.tableId().toString()));
            archiveWriter.write(archivePath,archived);
            archiveDirty=false; saveFailed=false;
            return true;
        } catch(IOException | RuntimeException failure) {
            if(!saveFailed) Riichicraft.LOGGER.error("Riichicraft world archive could not be saved; the previous record is retained: {}",archivePath);
            saveFailed=true;
            // A file changed or damaged after startup must also fail closed, never become an empty replacement.
            try { TableArchiveStore.read(archivePath); }
            catch(IOException invalidExisting) { archiveBlocked=true; }
            for(ServerPlayer player:server.getPlayerList().getPlayers()) {
                if(registry.tableForPlayer(player.getUUID()).isPresent()) player.sendOverlayMessage(Component.translatable(
                        archiveBlocked?"message.riichicraft.persistence.corrupt":"message.riichicraft.persistence.save_failed"));
            }
            return false;
        }
    }

    private boolean blocked(ServerPlayer player,UUID tableId) {
        String reason=archiveBlocked?"message.riichicraft.persistence.corrupt"
                :blockedTables.containsKey(tableId)?"message.riichicraft.persistence.table_corrupt":"";
        if(!reason.isEmpty()) player.sendOverlayMessage(Component.translatable(reason));
        return stopping || !reason.isEmpty();
    }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(LobbyService::get);
        ServerLifecycleEvents.BEFORE_SAVE.register((server,flush,force)-> {
            LobbyService service=SERVICES.get(server);
            if(service!=null) service.save();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> get(server).disconnect(handler.player));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> get(server).connected(handler.player));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, previous, current) -> {
            LobbyService service = SERVICES.get(current.getServer());
            if (service != null) service.tableDisplaySync.forget(player.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LobbyService service = SERVICES.get(server);
            if (service != null) service.tick();
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            LobbyService service = SERVICES.get(level.getServer());
            if (service != null) service.unloadChunk(level, chunk.getPos().x(), chunk.getPos().z());
        });
        ServerChunkEvents.CHUNK_LOAD.register((level,chunk,newChunk)-> {
            LobbyService service=SERVICES.get(level.getServer());
            if(service!=null) service.loadedChunk(level,chunk);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LobbyService service = SERVICES.get(server);
            if (service != null) service.stop();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVICES.remove(server));
    }

    public UUID open(ServerPlayer player, BlockPos pos) {
        return open(player,pos,false);
    }

    public UUID openImmersive(ServerPlayer player, BlockPos pos) {
        return open(player,pos,true);
    }

    private UUID open(ServerPlayer player, BlockPos pos, boolean immersive) {
        if(blocked(player,null)) return null;
        pos=TableStructure.resolve(player.level(),pos);
        if(pos==null) return null;
        TableKey key = new TableKey(player.level().dimension(), pos.immutable());
        if (!accessible(player, key)) return null;
        if(blocked(player,tables.get(key))) return null;
        unloadedTables.remove(tables.get(key));
        disconnected.remove(player.getUUID());
        UUID tableId = tables.computeIfAbsent(key, ignored -> {
            UUID id = UUID.randomUUID();
            locations.put(id, key);
            registry.createTable(id);
            return id;
        });
        if(!games.containsKey(tableId)) refreshStructure(tableId);
        View old = views.remove(player.getUUID());
        if (old != null) sendClosed(player, old, "");
        View view = new View(UUID.randomUUID(), tableId, immersive);
        views.put(player.getUUID(), view);
        Prototype prototype = games.get(tableId);
        if (prototype != null) refreshPresence(tableId, prototype);
        sendSnapshot(player, view, registry.snapshot(tableId).orElseThrow(), "");
        return view.id();
    }

    /** A chair click is a server-owned seat claim and mount, never a client-provided slot. */
    public boolean sit(ServerPlayer player, BlockPos chair) {
        if(blocked(player,null) || !player.isAlive() || player.isSpectator()
                || !player.isWithinBlockInteractionRange(chair,0)) return false;
        var level=player.level(); var state=TableStructure.loadedState(level,chair);
        if(state==null || !state.is(ModBlocks.MAHJONG_CHAIR)) return false;
        Direction facing=state.getValue(dev.riichicraft.block.MahjongChairBlock.FACING);
        BlockPos center=chair.relative(facing,2);
        var physical=TableStructure.inspect(level,center);
        if(!physical.large() || !physical.loaded() || !physical.bodyComplete() || physical.players()==0) return false;
        UUID existing=registry.tableForPlayer(player.getUUID()).orElse(null);
        UUID id=tables.get(new TableKey(level.dimension(),center));
        if(existing!=null && !existing.equals(id)) return false;
        if(player.getVehicle() instanceof MahjongSeatEntity current) {
            if(!chair.equals(current.chair()) || !java.util.Objects.equals(id,current.tableId())) return false;
            openImmersive(player,center); return true;
        }
        if(player.isPassenger()) return false;
        UUID viewId=openImmersive(player,center);
        if(viewId==null) return false;
        id=views.get(player.getUUID()).tableId();
        List<Integer> sides=chairSides(id);
        int slot=sides.indexOf(displaySide(facing.getOpposite()));
        if(slot<0) return false;
        var lobby=registry.snapshot(id).orElseThrow();
        boolean already=lobby.seats().get(slot).playerId()!=null
                && lobby.seats().get(slot).playerId().equals(player.getUUID());
        if(games.containsKey(id) && !already) return false;
        var result=registry.joinAt(id,player.getUUID(),player.getGameProfile().name(),lobby.revision(),slot);
        if(!result.successful()) {
            sendSnapshot(player,views.get(player.getUUID()),result.snapshot(),feedback(result.status())); return false;
        }
        var seat=new MahjongSeatEntity(ModEntities.MAHJONG_SEAT,level);
        seat.bind(id,chair,facing);
        if(!level.addFreshEntity(seat) || !player.startRiding(seat,true,false)) {
            seat.discard();
            if(!already) registry.leave(id,player.getUUID(),result.snapshot().revision());
            broadcast(registry.snapshot(id).orElseThrow(),null,"message.riichicraft.lobby.unavailable");
            return false;
        }
        mountedSeats.put(player.getUUID(),seat);
        seatedTables.add(id); displaySides.put(id,sides);
        player.setYRot(facing.toYRot()); player.setXRot(35);
        var prototype=games.get(id);
        if(prototype!=null) refreshPresence(id,prototype);
        broadcast(registry.snapshot(id).orElseThrow(),null,"");
        playTableSound(id,SoundEvents.WOOD_STEP,0.18F,1.2F);
        return true;
    }

    private boolean mounted(UUID playerId, UUID tableId) {
        var seat=mountedSeats.get(playerId); var player=server.getPlayerList().getPlayer(playerId);
        var state=seat==null || player==null?null:TableStructure.loadedState(player.level(),seat.chair());
        return seat!=null && !seat.isRemoved() && player!=null && player.isAlive()
                && tableId.equals(seat.tableId()) && player.getVehicle()==seat
                && state!=null && state.is(ModBlocks.MAHJONG_CHAIR)
                && state.getValue(dev.riichicraft.block.MahjongChairBlock.FACING)==seat.facing();
    }

    private List<Integer> chairSides(UUID tableId) {
        if(games.containsKey(tableId) && displaySides.containsKey(tableId)) return displaySides.get(tableId);
        var key=locations.get(tableId); var level=key==null?null:server.getLevel(key.dimension());
        var root=level==null?null:TableStructure.loadedState(level,key.pos());
        if(root==null || !root.is(ModBlocks.LARGE_MAHJONG_TABLE)) return List.of();
        var sides=new ArrayList<Integer>();
        Direction side=root.getValue(dev.riichicraft.block.LargeMahjongTableBlock.FACING).getOpposite();
        int count=registry.snapshot(tableId).orElseThrow().seats().size();
        for(int i=0;i<4;i++,side=side.getCounterClockWise()) {
            var state=TableStructure.loadedState(level,key.pos().relative(side,2));
            if(count==4 || state!=null && !state.is(ModBlocks.EMPTY_CHAIR)) sides.add(displaySide(side));
        }
        return sides.size()==count?List.copyOf(sides):displaySides.getOrDefault(tableId,List.of());
    }

    private void refreshMounts() {
        for(var entry:List.copyOf(mountedSeats.entrySet())) {
            var seat=entry.getValue(); UUID id=seat.tableId();
            if(mounted(entry.getKey(),id)) continue;
            mountedSeats.remove(entry.getKey()); seat.discard();
            if(!games.containsKey(id)) registry.disconnect(entry.getKey()).ifPresent(snapshot->broadcast(snapshot,null,""));
            else {
                refreshPresence(id,games.get(id));
                var player=server.getPlayerList().getPlayer(entry.getKey()); var view=views.get(entry.getKey());
                if(player!=null && view!=null) sendSnapshot(player,view,registry.snapshot(id).orElseThrow(),"");
            }
        }
    }

    public void handleAction(ServerPlayer player, LobbyActionPayload payload) {
        View view = views.get(player.getUUID());
        if (view == null || !view.id().equals(payload.viewId())) return;
        if (payload.action() == LobbyActionPayload.CLOSE) {
            views.remove(player.getUUID());
            return;
        }
        if(blocked(player,view.tableId())) return;
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
        if(view.immersive() && payload.action()==LobbyActionPayload.JOIN) {
            sendSnapshot(player,view,registry.snapshot(view.tableId()).orElseThrow(),"message.riichicraft.lobby.not_seated");
            return;
        }
        refreshStructure(view.tableId());
        var current=registry.snapshot(view.tableId()).orElseThrow();
        TableStructure.Layout layout=layout(view.tableId());
        if(layout.large() && (payload.action()==LobbyActionPayload.JOIN || payload.action()==LobbyActionPayload.START
                || payload.action()==LobbyActionPayload.SET_READY && payload.ready() || payload.action()==LobbyActionPayload.SET_MODE)) {
            if(!layout.matches(current.seats().size())) {
                sendSnapshot(player,view,current,layout.players()==3 && current.seats().size()==4
                        ?"message.riichicraft.lobby.leave_before_three":"message.riichicraft.structure.invalid"); return;
            }
            if(payload.action()==LobbyActionPayload.SET_MODE && LobbyRegistry.seatCount(payload.mode())!=layout.players()) {
                sendSnapshot(player,view,current,"message.riichicraft.structure.count_locked"); return;
            }
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
            case LobbyActionPayload.SET_MODE -> registry.setMode(view.tableId(),player.getUUID(),payload.mode(),payload.revision());
            default -> null;
        };
        if (result == null) {
            sendSnapshot(player, view, registry.snapshot(view.tableId()).orElseThrow(),
                    "message.riichicraft.lobby.invalid_action");
        } else if (result.snapshot() != null) {
            if(payload.action()==LobbyActionPayload.LEAVE && result.successful()) {
                var seat=mountedSeats.remove(player.getUUID()); if(seat!=null) seat.discard();
            }
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
        games.put(view.tableId(), new Prototype(UUID.randomUUID(), players, SEEDS.nextLong(),lobby.mode()));
        broadcast(lobby, null, "");
        playTableSound(view.tableId(),SoundEvents.NOTE_BLOCK_PLING.value(),0.25F,1.1F);
    }

    public void handleGameAction(ServerPlayer player, GameActionPayload payload) {
        View view = views.get(player.getUUID());
        if (view == null || !view.id().equals(payload.viewId())) return;
        if(blocked(player,view.tableId())) return;
        Prototype prototype = games.get(view.tableId());
        if (prototype == null || !prototype.gameId.equals(payload.gameId())) return;
        // Recheck in the same server action, closing the gap before the next END_SERVER_TICK.
        refreshPresence(view.tableId(), prototype);
        LobbyRegistry.Snapshot lobby = registry.snapshot(view.tableId()).orElseThrow();
        String feedback = "";
        // Other replies advance the visible revision but never invalidate this same opportunity.
        boolean stable=GameActionPayload.isStableAction(payload.action());
        if ((!stable && payload.revision()!=prototype.revision) || payload.revision()>prototype.revision) feedback = "message.riichicraft.lobby.stale";
        else if (!prototype.players.contains(player.getUUID())) {
            feedback = "message.riichicraft.game.spectator";
        } else if (!accessible(player, locations.get(view.tableId()))) {
            feedback = "message.riichicraft.lobby.unavailable";
        } else if (payload.action() == GameActionPayload.RETURN_LOBBY) {
            if (!prototype.finished) feedback="message.riichicraft.lobby.invalid_action";
            else {
                returnToLobby(view.tableId(), prototype, "");
                return;
            }
        } else if (payload.action() == GameActionPayload.ABORT && !prototype.finished) {
            returnToLobby(view.tableId(), prototype, "message.riichicraft.game.aborted");
            return;
        } else if (prototype.finished) feedback="message.riichicraft.lobby.invalid_action";
        else if (payload.action()==GameActionPayload.NEXT_ROUND) {
            if(!prototype.intermission()) feedback="message.riichicraft.lobby.invalid_action";
            else if(!prototype.unavailable.isEmpty()) feedback="message.riichicraft.game.players_absent";
            else {
                var result=prototype.confirmNext(player.getUUID(),payload.windowSerial());
                if(result==FourPlayerRound.Status.ACCEPTED) {
                    prototype.revision++;prototype.syncStatus();
                    broadcast(lobby,null,"");return;
                }
                feedback=result==FourPlayerRound.Status.ALREADY_RESPONDED?"":"message.riichicraft.lobby.stale";
            }
        }
        else if (payload.action() == GameActionPayload.RESUME) {
            if (prototype.intermission()) feedback="message.riichicraft.lobby.invalid_action";
            else if (!prototype.paused) feedback = "message.riichicraft.game.resumed";
            else if (!prototype.unavailable.isEmpty()) feedback = "message.riichicraft.game.players_absent";
            else if (prototype.confirmations.add(player.getUUID())) {
                prototype.revision++;
                if (prototype.confirmations.size() == prototype.players.size()) {
                    prototype.paused = false;
                    prototype.statusKey = "";
                    broadcast(lobby, null, "message.riichicraft.game.resumed");
                } else broadcast(lobby, null, "");
                return;
            }
        } else {
            if (prototype.paused) feedback = "message.riichicraft.game.paused";
            else if(prototype.waitingPublic!=null) feedback = "message.riichicraft.lobby.invalid_action";
            else {
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
                TableDisplaySnapshot animationBefore=tableDisplay(view.tableId());
                FourPlayerRound.Status result=prototype.act(player.getUUID(),payload,kanChoice);
                if (result==FourPlayerRound.Status.ACCEPTED) {
                    prototype.revision++;
                    prototype.syncStatus();
                    beginResponseDelay(view.tableId(),prototype,payload,prototype.players.indexOf(player.getUUID()));
                    var animationAfter=tableDisplay(view.tableId());
                    tableDisplaySync.record(view.tableId(),animationBefore,animationAfter);
                    if(animationAfter!=null && !animationAfter.equals(animationBefore)) {
                        boolean ended=animationAfter.finished() && (animationBefore==null || !animationBefore.finished());
                        playTableSound(view.tableId(),ended?SoundEvents.NOTE_BLOCK_PLING.value():SoundEvents.STONE_BUTTON_CLICK_ON,
                                ended?0.3F:0.14F,ended?1.45F:1.6F);
                    }
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

    /** Restore idle-lobby ownership rules exactly once, before publishing or saving the transition. */
    private void returnToLobby(UUID tableId, Prototype prototype, String feedback) {
        if (!games.remove(tableId, prototype)) return;
        for (UUID participant : prototype.players) {
            boolean offline = disconnected.contains(participant)
                    || server.getPlayerList().getPlayer(participant) == null;
            if (offline && !feedback.isEmpty()) pendingNotices.put(participant, feedback);
            // Active games reserve absent identities. An idle physical seat requires a valid mount again;
            // legacy entries still retain online members when they merely close their view.
            if (offline || seatedTables.contains(tableId) && !mounted(participant, tableId)) {
                var seat = mountedSeats.remove(participant);
                if (seat != null) seat.discard();
                registry.disconnect(participant);
            }
        }
        clearDisconnected(prototype);
        broadcast(registry.resetReadiness(tableId), null, feedback);
        save();
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

    /** Sounds follow accepted state changes, never replayed from scene/snapshot recovery. */
    private void playTableSound(UUID tableId,SoundEvent event,float volume,float pitch) {
        var key=locations.get(tableId); var level=key==null?null:server.getLevel(key.dimension());
        if(level!=null) level.playSound(null,key.pos(),event,SoundSource.BLOCKS,volume,pitch);
    }

    private boolean present(UUID playerId, UUID tableId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        TableKey location = locations.get(tableId);
        return !stopping && !unloadedTables.contains(tableId) && !disconnected.contains(playerId)
                && player != null && location != null && accessible(player, location)
                && (!seatedTables.contains(tableId) || mounted(playerId,tableId));
    }

    private TableStructure.Layout layout(UUID tableId) {
        TableKey key=locations.get(tableId);
        ServerLevel level=key==null?null:server.getLevel(key.dimension());
        return level==null?new TableStructure.Layout(true,false,false,0):TableStructure.inspect(level,key.pos());
    }

    /** Apply physical capacity only between games; an active game's identities and rules remain fixed. */
    private void refreshStructure(UUID tableId) {
        TableStructure.Layout layout=layout(tableId);
        if(!layout.large()) return;
        int count=layout.players();
        Integer previous=structureCounts.put(tableId,count);
        var before=registry.snapshot(tableId).orElseThrow();
        if(count==3 || count==4) registry.setPlayerCount(tableId,count);
        var after=registry.snapshot(tableId).orElseThrow();
        if(seatedTables.contains(tableId) && layout.matches(after.seats().size())) {
            List<Integer> sides=chairSides(tableId);
            var indices=new HashMap<UUID,Integer>();
            boolean complete=true;
            for(var occupant:after.seats()) if(occupant.occupied()) {
                var mount=mountedSeats.get(occupant.playerId());
                int target=-1;
                if(mount!=null) for(Direction side:Direction.Plane.HORIZONTAL)
                    if(mount.chair().equals(locations.get(tableId).pos().relative(side,2))) target=sides.indexOf(displaySide(side));
                if(target<0) { complete=false;break; }
                indices.put(occupant.playerId(),target);
            }
            if(complete) after=registry.arrange(tableId,indices);
            displaySides.put(tableId,sides);
        }
        boolean changed=previous==null || previous!=count;
        if(changed && after.revision()==before.revision()) after=registry.invalidatePreparation(tableId);
        if(changed || after.revision()!=before.revision()) broadcast(after,null,
                count==0?"message.riichicraft.structure.invalid"
                        :count==3 && after.seats().size()==4?"message.riichicraft.lobby.leave_before_three":"");
    }

    private void connected(ServerPlayer player) {
        disconnected.remove(player.getUUID());
        String notice = pendingNotices.remove(player.getUUID());
        if (notice != null) player.sendOverlayMessage(Component.translatable(notice));
    }

    private void refreshPresence(UUID tableId, Prototype prototype) {
        // A paid result is immutable and remains reopenable even when its players disconnect.
        if (prototype.finished) return;
        var physical=layout(tableId);
        boolean layoutValid=physical.matches(prototype.players.size());
        Integer oldCount=physical.large()?structureCounts.put(tableId,physical.players()):null;
        boolean layoutChanged=physical.large() && !java.util.Objects.equals(oldCount,physical.players());
        Set<UUID> missing = new HashSet<>();
        for (UUID playerId : prototype.players) {
            if (!layoutValid || !present(playerId, tableId)) missing.add(playerId);
        }
        boolean changed = layoutChanged || !missing.equals(prototype.unavailable);
        boolean newlyMissing = missing.stream().anyMatch(id -> !prototype.unavailable.contains(id));
        if(prototype.intermission()) {
            if(newlyMissing) changed|=prototype.clearNext();
            prototype.unavailable=Set.copyOf(missing);
            prototype.paused=!layoutValid;prototype.confirmations.clear();
            String status=!layoutValid?"message.riichicraft.structure.paused":missing.isEmpty()?"":"message.riichicraft.game.player_unavailable";
            if(!status.equals(prototype.statusKey)) changed=true;
            prototype.statusKey=status;
            if(changed) { prototype.revision++;broadcast(registry.snapshot(tableId).orElseThrow(),null,""); }
            return;
        }
        if (!missing.isEmpty() && (!prototype.paused || newlyMissing)) {
            prototype.paused = true;
            prototype.clearResponseDelay();
            prototype.confirmations.clear();
            changed = true;
        }
        prototype.unavailable = Set.copyOf(missing);
        String status = !layoutValid?"message.riichicraft.structure.paused":!prototype.paused ? "" : missing.isEmpty()
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
        if(prototype.waitingPublic!=null && !prototype.paused) {
            int seat=recipient==null?-1:prototype.players.indexOf(recipient);
            var held=seat<0?prototype.waitingPublic:prototype.waitingHands.get(seat);
            var seats=new ArrayList<GameSnapshot.Seat>();
            for(int index=0;index<held.seats().size();index++) {
                var old=held.seats().get(index);
                seats.add(new GameSnapshot.Seat(old.handCount(),old.discards(),present(prototype.players.get(index),tableId),false));
            }
            return new GameSnapshot(held.gameId(),prototype.revision,held.turnSeat(),held.remainingTiles(),seats,held.ownHand(),-1,false,"",held.rules(),held.match());
        }
        return liveGameView(tableId,recipient,prototype);
    }

    private GameSnapshot liveGameView(UUID tableId, UUID recipient, Prototype prototype) {
        if (prototype.three()) {
            var view = prototype.threeView(recipient);
            var context = projectionContext(tableId, prototype);
            var ura = view.end().isPresent() ? revealedUra(prototype) : List.<Integer>of();
            var match = prototype.threeMatch == null ? null : GameSnapshotProjector.matchInfo(prototype.threeMatch.view(recipient));
            return GameSnapshotProjector.threePlayer(view, match, context, ura);
        }
        var view = prototype.roundView(recipient);
        var context = projectionContext(tableId, prototype);
        var ura = view.end().isPresent() ? revealedUra(prototype) : List.<Integer>of();
        var match = prototype.match == null ? null : GameSnapshotProjector.matchInfo(prototype.match.view(recipient));
        return GameSnapshotProjector.fourPlayer(view, match, context, ura);
    }

    private GameSnapshotProjector.Context projectionContext(UUID tableId, Prototype prototype) {
        var available = new HashSet<UUID>();
        for (UUID player : prototype.players) if (present(player, tableId)) available.add(player);
        return new GameSnapshotProjector.Context(prototype.revision, prototype.paused, prototype.statusKey,
                available, prototype.confirmations);
    }

    private void beginResponseDelay(UUID tableId,Prototype prototype,GameActionPayload action,int actor) {
        if(action.action()!=GameActionPayload.DISCARD && action.action()!=GameActionPayload.RIICHI) return;
        var live=liveGameView(tableId,null,prototype);
        // A genuine response is already exposed by the engine. Terminal hands reveal the result directly.
        if(live.rules().phase()!=GameSnapshot.DRAWN_TURN) return;
        int ticks=Math.clamp(responseDelaySource.getAsInt(),0,40);
        if(ticks==0) return;
        if(actor<0) return;
        prototype.responseDelayTicks=ticks+1;
        var held=new ArrayList<GameSnapshot>();
        for(UUID player:prototype.players) held.add(GameSnapshotProjector.waiting(liveGameView(tableId,player,prototype),actor,action.tileId(),action.action()==GameActionPayload.RIICHI,prototype.revision));
        prototype.waitingHands=List.copyOf(held);
        prototype.waitingPublic=GameSnapshotProjector.waiting(live,actor,action.tileId(),action.action()==GameActionPayload.RIICHI,prototype.revision);
    }

    private static List<Integer> revealedUra(Prototype prototype) {
        if(prototype.three()) {
            var view=prototype.threeView(null);
            if(view.end().isEmpty() || view.end().get().kind().ordinal()>1 || view.end().get().winners().stream().noneMatch(seat->view.seats().get(seat).riichi().ordinal()==2)) return List.of();
            var state=prototype.threeMatch==null?prototype.threeGame.serverState():prototype.threeMatch.serverState();
            return state.wall().uraIndicators(view.doraIndicators().size());
        }
        var view=prototype.roundView(null);
        if(view.end().isEmpty() || view.end().get().kind().ordinal()>1 || view.end().get().winners().stream().noneMatch(seat->view.seats().get(seat).riichi().ordinal()==2)) return List.of();
        var state=prototype.match==null?prototype.game.serverState():prototype.match.serverState();
        return state.wall().uraIndicators(view.doraIndicators().size());
    }

    public void disconnect(ServerPlayer player) {
        views.remove(player.getUUID());
        var mount=mountedSeats.remove(player.getUUID()); if(mount!=null) mount.discard();
        if(stopping || archiveBlocked) return;
        UUID tableId = registry.tableForPlayer(player.getUUID()).orElse(null);
        if(blockedTables.containsKey(tableId)) return;
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
        UUID id = tables.get(key);
        if (id == null) return;
        // Destruction is never an implicit repair operation for a record that failed validation.
        if(stopping || archiveBlocked || blockedTables.containsKey(id)) return;
        tables.remove(key);
        structureCounts.remove(id);
        unloadedTables.remove(id);
        locations.remove(id);
        seatedTables.remove(id); displaySides.remove(id);
        for(var entry:List.copyOf(mountedSeats.entrySet())) if(id.equals(entry.getValue().tableId())) {
            mountedSeats.remove(entry.getKey());entry.getValue().discard();
        }
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
        archiveDirty=true;
        save();
    }

    private void unloadChunk(ServerLevel level, int chunkX, int chunkZ) {
        if(stopping || archiveBlocked) return;
        for (TableKey key : List.copyOf(tables.keySet())) {
            boolean rootChunk=key.pos().getX()>>4==chunkX && key.pos().getZ()>>4==chunkZ;
            var root=TableStructure.loadedState(level,key.pos());
            boolean large=root!=null && root.is(ModBlocks.LARGE_MAHJONG_TABLE);
            if (key.dimension().equals(level.dimension()) && (rootChunk || large
                    && chunkX>=((key.pos().getX()-2)>>4) && chunkX<=((key.pos().getX()+2)>>4)
                    && chunkZ>=((key.pos().getZ()-2)>>4) && chunkZ<=((key.pos().getZ()+2)>>4))) {
                UUID tableId=tables.get(key);
                if(blockedTables.containsKey(tableId)) continue;
                if(games.containsKey(tableId)) {
                    unloadedTables.add(tableId);
                    refreshPresence(tableId,games.get(tableId));
                } else if(rootChunk) removeTable(level,key.pos());
            }
        }
        save();
    }

    private void loadedChunk(ServerLevel level,LevelChunk chunk) {
        if(stopping || archiveBlocked) return;
        int chunkX=chunk.getPos().x(),chunkZ=chunk.getPos().z();
        for(TableKey key:List.copyOf(tables.keySet())) {
            if(key.dimension().equals(level.dimension()) && key.pos().getX()>>4==chunkX && key.pos().getZ()>>4==chunkZ) {
                UUID tableId=tables.get(key);
                if(blockedTables.containsKey(tableId)) continue;
                // FULL's future is still completing here: a world lookup would wait recursively on this callback.
                if(!TableStructure.isRoot(chunk.getBlockState(key.pos()))) removeTable(level,key.pos());
                else {
                    unloadedTables.remove(tableId);
                    // Presence and broadcasts run at END_SERVER_TICK, after the chunk can be queried without waiting.
                }
            }
        }
    }

    public void tick() {
        if(stopping || archiveBlocked) return;
        refreshMounts();
        for (TableKey key : List.copyOf(tables.keySet())) {
            ServerLevel level = server.getLevel(key.dimension());
            UUID tableId=tables.get(key);
            if(blockedTables.containsKey(tableId)) continue;
            LevelChunk chunk=level==null?null:level.getChunkSource().getChunkNow(key.pos().getX()>>4,key.pos().getZ()>>4);
            if(chunk!=null) {
                unloadedTables.remove(tableId);
                if(!TableStructure.isRoot(chunk.getBlockState(key.pos()))) removeTable(level,key.pos());
                else if(!games.containsKey(tableId)) refreshStructure(tableId);
            } else if(games.containsKey(tableId)) {
                unloadedTables.add(tableId);
            }
        }
        for (Map.Entry<UUID, Prototype> entry : List.copyOf(games.entrySet())) {
            refreshPresence(entry.getKey(), entry.getValue());
            var prototype=entry.getValue();
            if(prototype.waitingPublic!=null) {
                if(prototype.paused) prototype.clearResponseDelay();
                else if(--prototype.responseDelayTicks<=0) {
                    var before=tableDisplay(entry.getKey());
                    prototype.clearResponseDelay();prototype.revision++;
                    tableDisplaySync.record(entry.getKey(),before,tableDisplay(entry.getKey()));
                    broadcast(registry.snapshot(entry.getKey()).orElseThrow(),null,"");
                }
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
        if (++displayTicks % 5 == 0) tableDisplaySync.tick(server, publicTableDisplays());
    }

    /** No private hand/action data leaves this spectator-only adapter. Does not load chunks. */
    List<TableDisplaySync.Table> publicTableDisplays() {
        return publicTableDisplays(games.entrySet());
    }
    private List<TableDisplaySync.Table> publicTableDisplays(java.util.Collection<Map.Entry<UUID,Prototype>> candidates) {
        List<TableDisplaySync.Table> result = new ArrayList<>();
        displaySides.keySet().retainAll(tables.values());
        for (var entry : candidates) {
            UUID id = entry.getKey(); var prototype = entry.getValue(); var key = locations.get(id);
            if (key == null || blockedTables.containsKey(id)) continue;
            var level = server.getLevel(key.dimension());
            var root = level == null ? null : TableStructure.loadedState(level, key.pos());
            if (root == null || !root.is(ModBlocks.LARGE_MAHJONG_TABLE)) continue;
            var layout = TableStructure.inspect(level, key.pos());
            if (!layout.loaded() || !layout.bodyComplete()) continue;
            var facing = root.getValue(dev.riichicraft.block.LargeMahjongTableBlock.FACING);
            var front = facing.getOpposite();
            if (!seatedTables.contains(id) && layout.matches(prototype.players.size()) || !displaySides.containsKey(id)) {
                int empties = 0;
                for (var side : Direction.Plane.HORIZONTAL)
                    if (TableStructure.loadedState(level, key.pos().relative(side, 2)).is(ModBlocks.EMPTY_CHAIR)) empties++;
                // A missing ordinary chair still leaves the empty side unambiguous after restart.
                if (prototype.players.size() == 3 && empties != 1) continue;
                List<Integer> sides = new ArrayList<>(); var direction = front;
                for (int i = 0; i < 4; i++, direction = direction.getCounterClockWise()) {
                    var chair = TableStructure.loadedState(level, key.pos().relative(direction, 2));
                    if (prototype.players.size() == 4 || !chair.is(ModBlocks.EMPTY_CHAIR)) sides.add(displaySide(direction));
                }
                displaySides.put(id, List.copyOf(sides));
            }
            var sides = displaySides.get(id);
            if (sides == null || sides.size() != prototype.players.size()) continue;
            var snapshot = TableDisplaySnapshot.fromSpectator(gameView(id, null), displaySide(front), sides, prototype::addedKanTile);
            result.add(new TableDisplaySync.Table(key.dimension(), id, key.pos(), snapshot));
        }
        return List.copyOf(result);
    }

    private static int displaySide(Direction side) {
        return switch (side) { case SOUTH -> 0; case EAST -> 1; case NORTH -> 2; case WEST -> 3; default -> throw new IllegalArgumentException("horizontal side"); };
    }

    /** Read-only public integration view, including when no participant has a GUI open. */
    public TableDisplaySnapshot tableDisplay(UUID tableId) {
        var game=games.get(tableId);if(game==null)return null;
        return publicTableDisplays(List.of(Map.entry(tableId,game))).stream().map(TableDisplaySync.Table::snapshot).findFirst().orElse(null);
    }

    /** Save before detaching runtime ownership; native shutdown callbacks must never delete the archive. */
    public void stop() {
        if(stopping) return;
        stopping=true;
        for(var entry:List.copyOf(games.entrySet())) refreshPresence(entry.getKey(),entry.getValue());
        save();
        // stopServer subsequently invokes native saves. Cleared runtime maps are never a new empty checkpoint.
        detached=true;
        for(var mount:mountedSeats.values()) mount.discard();
        mountedSeats.clear(); seatedTables.clear();
        games.clear();
        views.clear();
        tables.clear();
        locations.clear();
        disconnected.clear();
        pendingNotices.clear();
        unloadedTables.clear();
        structureCounts.clear();
        displaySides.clear();
        blockedTables.clear();
        registry.clear();
    }

    private boolean accessible(ServerPlayer player, TableKey key) {
        if(key==null || !player.isAlive() || player.isSpectator() || !player.level().dimension().equals(key.dimension())
                || !player.isWithinBlockInteractionRange(key.pos(),1.0)) return false;
        LevelChunk chunk=player.level().getChunkSource().getChunkNow(key.pos().getX()>>4,key.pos().getZ()>>4);
        return chunk!=null && TableStructure.isRoot(chunk.getBlockState(key.pos()));
    }

    private void broadcast(LobbyRegistry.Snapshot snapshot, UUID actor, String feedback) {
        archiveDirty=true;
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
        var layout=layout(view.tableId());
        if(view.immersive() && ServerPlayNetworking.canSend(player,TableScenePayload.TYPE))
            ServerPlayNetworking.send(player,sceneView(view.id(),view.tableId(),player.getUUID()));
        ServerPlayNetworking.send(player, new LobbySnapshotPayload(view.id(), view.tableId(),
                locations.get(view.tableId()).pos(), snapshot.revision(), seats, ownSeat, snapshot.allReady(), feedback,
                gameView(view.tableId(), player.getUUID()),snapshot.mode(),
                layout.large()?layout.players():-1));
    }

    /** Recipient identity comes from the stored roster, independently of whether it is mounted. */
    public TableScenePayload sceneView(UUID viewId, UUID tableId, UUID recipient) {
        var lobby=registry.snapshot(tableId).orElseThrow(); var prototype=games.get(tableId);
        var key=locations.get(tableId); List<Integer> sides=chairSides(tableId);
        if(sides.size()!=lobby.seats().size()) {
            // Invalid furniture may retain an active scene; its frozen sides come from the archive.
            sides=displaySides.getOrDefault(tableId,lobby.seats().size()==4?List.of(0,1,2,3):List.of(0,1,3));
        }
        var players=lobby.seats().stream().filter(LobbyRegistry.Seat::occupied).map(LobbyRegistry.Seat::playerId).toList();
        int own=-1;for(var seat:lobby.seats()) if(recipient.equals(seat.playerId())) own=seat.index();
        List<List<Integer>> hands=new ArrayList<>(); List<Integer> drawn=new ArrayList<>();
        boolean finished=false; UUID gameId=null;long revision=lobby.revision();
        if(prototype==null) {
            for(int i=0;i<lobby.seats().size();i++) {hands.add(List.of());drawn.add(-1);}
        } else {
            gameId=prototype.gameId;revision=prototype.revision;
            finished=prototype.three()?prototype.threeView(null).phase()==ThreePlayerRound.Phase.FINISHED
                    :prototype.roundView(null).phase()==FourPlayerRound.Phase.FINISHED;
            for(UUID participant:prototype.players) {
                var view=gameView(tableId,participant);
                hands.add(view.ownHand());drawn.add(view.drawnTile());
            }
            var selected=TableHandVisibility.project(recipient,prototype.players,hands,drawn,finished);
            hands=selected.hands();drawn=selected.drawnTiles();own=selected.ownSeat();
        }
        var publicState=prototype==null?null:tableDisplay(tableId);
        return new TableScenePayload(viewId,tableId,key.dimension(),key.pos(),gameId,revision,own,sides,
                mounted(recipient,tableId),hands,drawn,finished,publicState==null?null:publicState.hashCode());
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
            case TOO_MANY_PLAYERS -> "message.riichicraft.lobby.leave_before_three";
            case ALREADY_SEATED_ELSEWHERE -> "message.riichicraft.lobby.other_table";
            case NOT_SEATED -> "message.riichicraft.lobby.not_seated";
            case TABLE_NOT_FOUND -> "message.riichicraft.lobby.unavailable";
            case NOT_EAST -> "message.riichicraft.game.east_starts";
            case INVALID_MODE -> "message.riichicraft.lobby.invalid_action";
        };
    }

    public LobbyRegistry registry() {
        return registry;
    }
}
