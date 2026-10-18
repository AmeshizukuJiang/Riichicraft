package dev.riichicraft.test;

import dev.riichicraft.client.TableCamera;
import dev.riichicraft.client.TableInteractionClient;
import dev.riichicraft.client.TableScreen;
import dev.riichicraft.entity.MahjongSeatEntity;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.TableDisplaySnapshot;
import dev.riichicraft.server.LobbyService;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Actual world save/reload for v2 chair bindings; the mount itself must never be restored. */
final class ImmersivePersistenceClientScenario {
    private record Identity(UUID id,String name) { }
    private record Core(UUID game,int actor,int live,List<Integer> hand,int draw,GameSnapshot.RuleState rules) {
        static Core of(GameSnapshot game) { return new Core(game.gameId(),game.turnSeat(),game.remainingTiles(),game.ownHand(),game.drawnTile(),game.rules()); }
    }
    private static final class Session {
        BlockPos center; UUID table,actual; List<Identity> identities; List<Core> cores; TableDisplaySnapshot publicState;
        List<ConnectedTestPlayer> fixtures=new ArrayList<>(); List<UUID> views=new ArrayList<>();
    }
    static void run(ClientGameTestContext context, Properties properties) {
        var saved=new Session();
        try(var first=PersistenceClientScenario.reopen(context,properties)) {
            try(var connection=first.connect()) {
                connection.waitForChunksRender();
                first.runOnServer(server->{
                    var level=connection.getServerLevel();var actual=connection.getServerPlayer();var service=LobbyService.get(server);
                    saved.center=new BlockPos(40,actual.blockPosition().getY(),12);saved.actual=actual.getUUID();
                    for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) {
                        for(int y=0;y<5;y++) level.setBlockAndUpdate(saved.center.offset(x,y,z),Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(saved.center.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                    }
                    FurnitureTestLayout.body(level,saved.center,Direction.WEST);
                    FurnitureTestLayout.chairs(level,saved.center,3);
                    actual.setGameMode(GameType.CREATIVE);near(actual,saved.center.east(2));
                    require(service.sit(actual,saved.center.east(2)),"Actual player mounts initial East at the physical east side");
                    saved.views.add(service.openImmersive(actual,saved.center));
                    saved.table=service.registry().tableForPlayer(actual.getUUID()).orElseThrow();
                    for(Direction side:List.of(Direction.WEST,Direction.SOUTH)) {
                        var fixture=new ConnectedTestPlayer(server,level,saved.center,"MountSave"+saved.fixtures.size());
                        saved.fixtures.add(fixture);near(fixture.player(),saved.center.relative(side,2));
                        require(service.sit(fixture.player(),saved.center.relative(side,2)),"Original sanma fixture mounts its rotated side");
                        saved.views.add(service.open(fixture.player(),saved.center));
                    }
                    for(int seat=0;seat<3;seat++) service.handleAction(player(saved,actual,seat),new LobbyActionPayload(saved.views.get(seat),
                            service.registry().snapshot(saved.table).orElseThrow().revision(),LobbyActionPayload.SET_READY,true));
                    service.handleAction(actual,new LobbyActionPayload(saved.views.getFirst(),service.registry().snapshot(saved.table).orElseThrow().revision(),LobbyActionPayload.START,false));
                    require(service.gameView(saved.table,saved.actual)!=null,"Mounted sanma starts before a real durable save");
                    saved.cores=cores(saved,service);saved.publicState=service.tableDisplay(saved.table);
                    saved.identities=saved.fixtures.stream().map(f->new Identity(f.player().getUUID(),f.player().getGameProfile().name())).toList();
                    require(service.save(),"v2 chair state checkpoint succeeds");
                });
                connection.waitForClientboundPackets();connection.waitForChunksRender();context.waitForScreen(TableScreen.class);
                // Save player RootVehicle metadata and entity chunks while all three mounts still exist.
                first.runCommand("save-all flush");
            } finally { closeFixtures(first,saved); }
        }
        saved.views=new ArrayList<>();
        try(var second=PersistenceClientScenario.reopen(context,properties)) {
            try(var connection=second.connect()) {
                connection.waitForChunksRender();
                second.runOnServer(server->{
                    var service=LobbyService.get(server);var level=connection.getServerLevel();var actual=connection.getServerPlayer();
                    require(actual.getUUID().equals(saved.actual) && !actual.isPassenger(),"The real identity reconnects without a saved or orphan mount");
                    require(service.registry().tableForPlayer(saved.actual).orElseThrow().equals(saved.table),"Original table ownership survives the actual server replacement");
                    near(actual,saved.center.east(2));
                    require(level.getEntitiesOfClass(MahjongSeatEntity.class,new AABB(saved.center).inflate(5)).isEmpty(),
                            "Native chunk reload never restores transient mount entities");
                    saved.views.add(service.openImmersive(actual,saved.center));
                    for(var identity:saved.identities) {
                        var fixture=new ConnectedTestPlayer(server,level,saved.center,identity.id(),identity.name());saved.fixtures.add(fixture);
                        saved.views.add(service.open(fixture.player(),saved.center));
                    }
                    require(cores(saved,service).equals(saved.cores),"New native server restores all original private hands, wall, points and pending turn");
                    require(service.gameView(saved.table,saved.actual).paused(),"Loaded physical game stays paused before anyone remounts");
                    var projected=service.sceneView(saved.views.getFirst(),saved.table,saved.actual);
                    require(projected.ownSeat()==0 && !projected.mounted() && projected.sides().equals(List.of(1,3,0))
                            && projected.hands().getFirst().equals(saved.cores.getFirst().hand())
                            && projected.hands().get(1).isEmpty() && projected.hands().get(2).isEmpty(),
                            "Restored participant keeps frozen rotated chair and only its own faces while unmounted");
                    require(dev.riichicraft.table.TableTileLayout.tiles(service.tableDisplay(saved.table))
                            .equals(dev.riichicraft.table.TableTileLayout.tiles(saved.publicState)),"Every restored public model stays at its original position");
                });
                connection.waitForClientboundPackets();connection.waitForChunksRender();context.waitForScreen(TableScreen.class);
                context.waitFor(c->TableInteractionClient.scene()!=null && !TableInteractionClient.scene().mounted(),200);
                require(context.computeOnClient(c->!TableCamera.active()),"An unmounted restored player is not forced into a board camera");
                second.runOnServer(server->{
                    var service=LobbyService.get(server);var actual=connection.getServerPlayer();
                    var chairs=List.of(saved.center.east(2),saved.center.west(2),saved.center.south(2));
                    for(int seat=0;seat<3;seat++) {
                        var p=player(saved,actual,seat);near(p,chairs.get(seat));require(service.sit(p,chairs.get(seat)),"Original physical chair is reclaimed after genuine world reload");
                        saved.views.set(seat,seat==0?service.openImmersive(p,saved.center):service.open(p,saved.center));
                    }
                    for(int seat=0;seat<3;seat++) {
                        var p=player(saved,actual,seat);var game=service.gameView(saved.table,p.getUUID());
                        service.handleGameAction(p,new GameActionPayload(saved.views.get(seat),game.gameId(),game.revision(),GameActionPayload.RESUME,-1));
                    }
                    require(!service.gameView(saved.table,saved.actual).paused() && cores(saved,service).equals(saved.cores),
                            "Original mounts plus full fresh quorum resume the identical saved game");
                });
                connection.waitForClientboundPackets();context.waitFor(c->TableInteractionClient.scene()!=null && TableInteractionClient.scene().mounted()
                        && !TableInteractionClient.snapshot().game().paused(),200);
                context.takeScreenshot("riichicraft-immersive-restored");
                context.clickScreenButton("screen.riichicraft.game.abort");
                context.clickScreenButton("screen.riichicraft.game.confirm_abort");
                connection.waitForServerboundPackets();connection.waitForClientboundPackets();
                require(context.computeOnClient(c->TableInteractionClient.snapshot().game()==null),"Confirmed termination cleans the restored physical game");
            } finally { closeFixtures(second,saved); }
        }
    }
    private static void near(ServerPlayer player,BlockPos chair) {player.teleportTo(chair.getX()+.5,chair.getY(),chair.getZ()+.5);}
    private static ServerPlayer player(Session session,ServerPlayer actual,int seat) {return seat==0?actual:session.fixtures.get(seat-1).player();}
    private static List<Core> cores(Session session,LobbyService service) {
        var result=new ArrayList<Core>();result.add(Core.of(service.gameView(session.table,session.actual)));
        for(var fixture:session.fixtures) result.add(Core.of(service.gameView(session.table,fixture.player().getUUID())));
        return List.copyOf(result);
    }
    private static void closeFixtures(TestDedicatedServerContext server,Session session) {
        server.runOnServer(s->{for(var fixture:session.fixtures) {LobbyService.get(s).disconnect(fixture.player());fixture.close();}session.fixtures.clear();});
    }
    private static void require(boolean condition,String message) {if(!condition) throw new AssertionError(message);}
}
