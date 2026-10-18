package dev.riichicraft.test;

import dev.riichicraft.network.*;
import dev.riichicraft.server.LobbyService;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.block.TableStructure;
import dev.riichicraft.client.LobbyScreen;
import net.minecraft.client.gui.components.Button;
import org.lwjgl.glfw.GLFW;
import net.minecraft.server.level.ServerPlayer;
import static dev.riichicraft.test.ThreePlayerClientScenario.*;

/** Genuine graceful dedicated-server replacement, same on-disk world, three identities, and kita state. */
final class ThreePlayerPersistenceClientScenario {
    private record Identity(UUID id,String name) { }
    private record Core(UUID game,int actor,int live,List<Integer> hand,int draw,GameSnapshot.RuleState rules) {
        static Core of(GameSnapshot v){return new Core(v.gameId(),v.turnSeat(),v.remainingTiles(),v.ownHand(),v.drawnTile(),v.rules());}
    }
    private static final class Session {
        DrawDiscardClientScenario.TableFixtures table;BlockPos target;UUID player,obsoleteView;List<Identity> identities;List<Core> saved;TableDisplaySnapshot publicState;
    }
    static void run(ClientGameTestContext context,Properties properties) {
        run(context,properties,false);
    }
    static void runFurniture(ClientGameTestContext context,Properties properties) {
        run(context,properties,true);
    }
    private static void run(ClientGameTestContext context,Properties properties,boolean furniture) {
        var session=new Session();
        try(var first=PersistenceClientScenario.reopen(context,properties)) {
            try {
                try(var connection=first.connect()) {
                    connection.waitForChunksRender();session.target=context.computeOnClient(c->c.player.blockPosition().offset(0,0,-2));
                    first.runCommand("gamemode creative @a");
                    if(furniture) prepareFurniture(context,first,connection,session.target);
                    else first.runCommand("execute as @a at @s run setblock ~ ~ ~-2 riichicraft:mahjong_table[facing=south]");
                    if(furniture) openFurniture(context,connection,session.target);
                    else MahjongTableClientGameTest.openTable(context,connection,session.target);
                    context.clickScreenButton("screen.riichicraft.lobby.join");sync(connection);
                    if(furniture) {
                        require(snapshot(context).structurePlayers()==3 && snapshot(context).seats().size()==3,"Physical chairs select sanma over real TCP without a count toggle");
                        context.runOnClient(c->{var screen=(LobbyScreen)c.gui.screen();require(screen.children().stream().filter(v->v instanceof Button)
                                .map(v->(Button)v).anyMatch(b->b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("screen.riichicraft.lobby.chairs.3").getString())&&!b.active),"Chair-derived player count is displayed and cannot be toggled");});
                        shot(context,"riichicraft-furniture-lobby");
                    }
                    if(!furniture && snapshot(context).seats().size()==4){click(context,"screen.riichicraft.lobby.players.4");sync(connection);}
                    while(snapshot(context).mode()!=3){click(context,"screen.riichicraft.lobby.mode");sync(connection);}
                    session.player=snapshot(context).seats().getFirst().playerId();session.obsoleteView=snapshot(context).viewId();
                    session.table=DrawDiscardClientScenario.createFixtures(first,connection,session.target,session.obsoleteView);sync(connection);start(context,first,connection,session.table);
                    boolean extracted=false;
                    for(int deal=0;deal<8&&!extracted;deal++) {
                        extracted=first.computeOnServer(s->{var service=LobbyService.get(s);for(int step=0;step<400;step++) {
                            var state=service.gameView(session.table.tableId(),session.player);if(state.rules().result()!=null)return false;
                            int seat=state.turnSeat();var player=player(session,connection,seat);var own=service.gameView(session.table.tableId(),player.getUUID());
                            if(!own.rules().kitas().isEmpty()) {
                                service.handleGameAction(player,new GameActionPayload(session.table.viewIds().get(seat),own.gameId(),own.revision(),GameActionPayload.KITA,own.rules().kitas().getFirst()));
                                for(int pending=0;service.gameView(session.table.tableId(),session.player).rules().opportunity()!=null;pending++) {
                                    require(pending<3,"At most two north responders");DrawDiscardClientScenario.advance(service,session.table,connection.getServerPlayer());
                                }
                                require(service.gameView(session.table.tableId(),session.player).rules().seats().stream().mapToInt(v->v.norths().size()).sum()==1,"One real kita established before restart");return true;
                            }
                            DrawDiscardClientScenario.advance(service,session.table,connection.getServerPlayer());
                        }throw new AssertionError("Finite kita seek");});sync(connection);
                        if(!extracted){context.clickScreenButton("screen.riichicraft.game.return_lobby");sync(connection);start(context,first,connection,session.table);}
                    }
                    require(extracted,"One of eight normal sanma deals establishes kita");
                    if(furniture) awaitPublic(context,session.table.tableId(),false);
                    first.runOnServer(s->{var service=LobbyService.get(s);session.saved=cores(service,session);session.identities=session.table.fixtures().stream().map(f->new Identity(f.player().getUUID(),f.player().getGameProfile().name())).toList();
                        if(furniture) {session.publicState=service.tableDisplay(session.table.tableId());s.overworld().setBlockAndUpdate(session.target.south(2),Blocks.AIR.defaultBlockState());service.tick();require(service.gameView(session.table.tableId(),session.player).paused(),"Missing chair pauses before the durable save");}
                        require(service.save(),"Real sanma checkpoint written");});sync(connection);
                    shot(context,furniture?"riichicraft-furniture-paused":"riichicraft-three-before-stop");
                }
            } finally {if(session.table!=null)DrawDiscardClientScenario.closeFixtures(first,session.table);}
        }
        UUID tableId=session.table.tableId();session.table=null;
        try(var second=PersistenceClientScenario.reopen(context,properties)) {
            try {
                second.runOnServer(s->require(LobbyService.get(s).registry().tableForPlayer(session.player).orElseThrow().equals(tableId),"Three-player UUID ownership restored before TCP connect"));
                try(var connection=second.connect()) {
                    connection.waitForChunksRender();
                    if(furniture) openFurniture(context,connection,session.target);
                    else MahjongTableClientGameTest.openTable(context,connection,session.target);
                    UUID currentView=snapshot(context).viewId();
                    session.table=second.computeOnServer(s->{var service=LobbyService.get(s);
                        var fixtures=new ArrayList<ConnectedTestPlayer>();var views=new ArrayList<UUID>();views.add(currentView);
                        // Opening needs an accessible FULL chunk; wait for the real client to load it first.
                        for(var identity:session.identities){var fixture=new ConnectedTestPlayer(s,s.overworld(),session.target,identity.id(),identity.name());fixtures.add(fixture);UUID view=service.open(fixture.player(),session.target);require(view!=null,"Restored native participant can access loaded table");views.add(view);}
                        return new DrawDiscardClientScenario.TableFixtures(List.copyOf(fixtures),List.copyOf(views),tableId);});
                    sync(connection);
                    second.runOnServer(s->require(cores(LobbyService.get(s),session).equals(session.saved),"Actual new server restores all three private hands, live wall, kita and pending draw source without redealing"));
                    require(snapshot(context).game().paused()&&snapshot(context).seats().size()==3,"Restored sanma waits for three confirmations");
                    if(furniture) {
                        awaitPublic(context,tableId,true);
                        var actual=context.computeOnClient(c->dev.riichicraft.client.TableDisplayClient.snapshots().stream().filter(p->p.tableId().equals(tableId)).findFirst().orElseThrow().snapshot());
                        require(actual.liveTiles()==session.publicState.liveTiles() && actual.replacementDraws()==session.publicState.replacementDraws()
                                && dev.riichicraft.table.TableTileLayout.tiles(actual).equals(dev.riichicraft.table.TableTileLayout.tiles(session.publicState)),
                                "A genuine world reload reconstructs every public wall/indicator/kita model at exactly the same coordinates");
                    }
                    shot(context,furniture?"riichicraft-furniture-restored":"riichicraft-three-restored");
                    var current=snapshot(context).game();
                    context.runOnClient(c->ClientPlayNetworking.send(new GameActionPayload(session.obsoleteView,current.gameId(),current.revision(),GameActionPayload.RESUME,-1)));sync(connection);
                    require(snapshot(context).game().seats().stream().noneMatch(GameSnapshot.Seat::confirmed),"Pre-restart view rejected over new real TCP connection");
                    if(furniture) {
                        require(snapshot(context).structurePlayers()==0,"The missing chair remains absent after genuine world reload");
                        second.runOnServer(s->{FurnitureTestLayout.chairs(s.overworld(),session.target,4);LobbyService.get(s).tick();});sync(connection);
                        require(snapshot(context).structurePlayers()==4 && snapshot(context).game().paused(),"A restored three-player game cannot adopt a valid four-chair layout");
                        second.runOnServer(s->{FurnitureTestLayout.chairs(s.overworld(),session.target,3);LobbyService.get(s).tick();});sync(connection);
                        require(snapshot(context).structurePlayers()==3 && snapshot(context).game().paused(),"The original physical count is restored before fresh confirmation");
                    }
                    second.runOnServer(s->{var service=LobbyService.get(s);for(int seat=1;seat<3;seat++){var own=service.gameView(tableId,player(session,connection,seat).getUUID());service.handleGameAction(player(session,connection,seat),new GameActionPayload(session.table.viewIds().get(seat),own.gameId(),own.revision(),GameActionPayload.RESUME,-1));}});sync(connection);
                    context.clickScreenButton("screen.riichicraft.game.resume");sync(connection);
                    require(!snapshot(context).game().paused(),"Actual third recovery click resumes saved sanma");shot(context,furniture?"riichicraft-furniture-resumed":"riichicraft-three-restart-resumed");
                    context.clickScreenButton("screen.riichicraft.game.abort");context.clickScreenButton("screen.riichicraft.game.confirm_abort");sync(connection);require(snapshot(context).game()==null,"Confirmed terminate clears restored sanma");
                }
            } finally {if(session.table!=null)DrawDiscardClientScenario.closeFixtures(second,session.table);}
        }
    }
    private static void prepareFurniture(ClientGameTestContext context,TestDedicatedServerContext server,TestDedicatedServerConnection connection,BlockPos center) {
        server.runOnServer(s->{var level=connection.getServerLevel();
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
                for(int y=0;y<=3;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());
            }
            connection.getServerPlayer().teleportTo(center.getX()+0.5,center.getY(),center.getZ()+2.5);
        });
        server.runCommand("item replace entity @a weapon.mainhand with riichicraft:large_mahjong_table");
        context.waitTicks(3);sync(connection);connection.waitForChunksRender();
        context.getInput().lookAt(center.below());context.waitTick();
        context.runOnClient(c->require(c.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(center.below())&&hit.getDirection()==Direction.UP,
                "The actual placement ray hits the table center's ground support: player="+c.player.position()+", hit="+c.hitResult+", support="+center.below()));
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);sync(connection);
        server.runOnServer(s->require(TableStructure.inspect(s.overworld(),center).bodyComplete(),"Real mouse and BlockItem place all nine table parts"));
        server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
        server.runOnServer(s->{FurnitureTestLayout.chairs(s.overworld(),center,3);
            connection.getServerPlayer().teleportTo(center.getX()+4.5,center.getY()+2.5,center.getZ()+4.5);});
        sync(connection);connection.waitForChunksRender();context.getInput().lookAt(center);context.waitTicks(3);
        shot(context,"riichicraft-furniture-world");
        context.getInput().resizeWindow(1280,960);
        context.runOnClient(c->dev.riichicraft.client.TabletopRenderFixture.show(center,TableDisplayGameTests.opening(true)));
        context.getInput().pressKey(GLFW.GLFW_KEY_F1);context.waitTicks(3);
        context.takeScreenshot("riichicraft-wall-opening-three-layout");
        context.getInput().pressKey(GLFW.GLFW_KEY_F1);
        context.runOnClient(c->dev.riichicraft.client.TabletopRenderFixture.show(center,TableDisplayGameTests.showcase(true)));
        context.getInput().pressKey(GLFW.GLFW_KEY_F1);context.waitTicks(3);
        context.takeScreenshot("riichicraft-tabletop-layout-three");
        context.getInput().pressKey(GLFW.GLFW_KEY_F1);
        context.runOnClient(c->dev.riichicraft.client.TabletopRenderFixture.hide());
        context.getInput().resizeWindow(640,480);
        server.runOnServer(s->connection.getServerPlayer().teleportTo(center.getX()+0.5,center.getY(),center.getZ()+3.5));
        sync(connection);context.waitTicks(2);
    }
    private static void openFurniture(ClientGameTestContext context,TestServerConnection connection,BlockPos center) {
        // Aim at the actual tabletop height; BlockPos.lookAt aims below the slab and may hit a chair.
        float[] angles=context.computeOnClient(c->{
            Vec3 delta=Vec3.atCenterOf(center.south()).add(0,0.49,0).subtract(c.player.getEyePosition());
            return new float[]{(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90),
                    (float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z)))};
        });
        context.getInput().lookAt(angles[0],angles[1]);context.waitTick();
        context.runOnClient(c->require(c.hitResult instanceof BlockHitResult hit && c.level.getBlockState(hit.getBlockPos()).is(ModBlocks.LARGE_MAHJONG_TABLE),
                "Actual furniture crosshair must hit a table part: player="+c.player.position()+", hit="+c.hitResult+", center="+center));
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);sync(connection);context.waitForScreen(LobbyScreen.class);
        require(snapshot(context).pos().equals(center),"A real mouse click on the rim opens the canonical table center");
    }
    private static ServerPlayer player(Session session,TestDedicatedServerConnection connection,int seat){return seat==0?connection.getServerPlayer():session.table.fixtures().get(seat-1).player();}
    private static void awaitPublic(ClientGameTestContext context,UUID id,boolean paused) {
        context.waitFor(c->dev.riichicraft.client.TableDisplayClient.snapshots().stream().anyMatch(p->p.tableId().equals(id)
                && p.snapshot().seats().size()==3 && p.snapshot().paused()==paused
                && p.snapshot().seats().stream().mapToInt(s->s.norths().size()).sum()==1),200);
        var actual=context.computeOnClient(c->dev.riichicraft.client.TableDisplayClient.snapshots().stream().filter(p->p.tableId().equals(id)).findFirst().orElseThrow().snapshot());
        TabletopClientScenario.assertWall(actual);
        require(actual.replacementDraws()==1 && actual.liveTiles()==snapshot(context).game().remainingTiles(),
                "Established kita consumes one shared replacement, with public wall counts matching the private view before and after restart");
    }
    private static List<Core> cores(LobbyService service,Session session){var result=new ArrayList<Core>();result.add(Core.of(service.gameView(session.table.tableId(),session.player)));for(var fixture:session.table.fixtures())result.add(Core.of(service.gameView(session.table.tableId(),fixture.player().getUUID())));return List.copyOf(result);}
}
