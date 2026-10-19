package dev.riichicraft.test;

import dev.riichicraft.client.TableDisplayClient;
import dev.riichicraft.client.TabletopRenderFixture;
import dev.riichicraft.network.*;
import dev.riichicraft.server.LobbyService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** One real, unseated TCP observer and four native connected fixtures playing legal actions. */
final class TabletopClientScenario {
    private record Session(BlockPos center, UUID tableId, List<ConnectedTestPlayer> fixtures) { }
    static void run(ClientGameTestContext context, TestDedicatedServerContext server) {
        Session session = null;
        context.getInput().resizeWindow(1280,960);
        try {
            TableDisplaySnapshot saved;
            try (var connection = server.connect()) {
                session = server.computeOnServer(s -> {
                    var level = connection.getServerLevel(); BlockPos center = new BlockPos(8, connection.getServerPlayer().blockPosition().getY(), 8);
                    for (int x=-3;x<=3;x++) for (int z=-3;z<=3;z++) {
                        for (int y=0;y<=4;y++) level.setBlockAndUpdate(center.offset(x,y,z), Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(center.offset(x,-1,z), Blocks.STONE.defaultBlockState());
                    }
                    FurnitureTestLayout.body(level, center, Direction.NORTH); FurnitureTestLayout.chairs(level, center, 4);
                    connection.getServerPlayer().setGameMode(GameType.SPECTATOR);
                    connection.getServerPlayer().teleportTo(center.getX()+0.5,center.getY()+3,center.getZ()+1.0);
                    var fixtures = new ArrayList<ConnectedTestPlayer>(); var service = LobbyService.get(s);
                    UUID tableId = null;
                    for (int i=0;i<4;i++) {
                        var fixture = new ConnectedTestPlayer(s,level,center,"TableView"+i); fixtures.add(fixture);
                        var view = service.open(fixture.player(),center);
                        // The first discovered furniture layout advances preparation revision once.
                        service.handleAction(fixture.player(),new LobbyActionPayload(view,tableId==null?1:service.registry().snapshot(tableId).orElseThrow().revision(),LobbyActionPayload.JOIN,false));
                        tableId = service.registry().tableForPlayer(fixture.player().getUUID()).orElseThrow();
                        service.handleAction(fixture.player(),new LobbyActionPayload(view,service.registry().snapshot(tableId).orElseThrow().revision(),LobbyActionPayload.SET_READY,true));
                        service.handleAction(fixture.player(),new LobbyActionPayload(view,0,LobbyActionPayload.CLOSE,false));
                    }
                    var result = new Session(center,tableId,List.copyOf(fixtures)); start(service,result);
                    require(service.registry().tableForPlayer(connection.getServerPlayer().getUUID()).isEmpty(),"Actual TCP observer never joins a seat");
                    return result;
                });
                var active = session; connection.waitForClientboundPackets(); connection.waitForChunksRender();
                context.getInput().lookAt(session.center().above());
                await(context,session.tableId(),true);
                context.runOnClient(c -> require(c.gui.screen()==null,"Public table reception never opens a lobby screen"));
                var opening=current(context,session.tableId()); assertWall(opening);
                require(opening.liveTiles()==69 && opening.replacementDraws()==0,"Actual opening has 83 wall tiles, rather than drawing a second complete 136-tile wall");
                context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1);
                shot(context,"riichicraft-wall-opening-four");
                context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1);
                server.runOnServer(s->{var service=LobbyService.get(s);var own=service.gameView(active.tableId(),active.fixtures().getFirst().player().getUUID());
                    int absent=java.util.stream.IntStream.range(0,136).filter(t->t!=own.drawnTile() && !own.ownHand().contains(t)).findFirst().orElseThrow();
                    act(service,active,0,GameActionPayload.DISCARD,absent,null);
                    require(service.tableDisplay(active.tableId()).equals(opening),"Rejected discard cannot change any public state");});
                context.waitTicks(6);
                context.runOnClient(c->{var packet=TableDisplayClient.snapshots().stream().filter(p->p.tableId().equals(active.tableId())).findFirst().orElseThrow();
                    require(packet.actionSerial()==0 && packet.steps().isEmpty() && !TableDisplayClient.animating(active.tableId()),"Rejected action produces no event or playback on the real observer");});
                // Two accepted public mutations occur before the next 5-tick observer scan.
                server.runOnServer(s->{var service=LobbyService.get(s);advance(service,active,false);advance(service,active,false);});
                var animated=server.computeOnServer(s->LobbyService.get(s).tableDisplay(active.tableId()));
                awaitSnapshot(context,active.tableId(),animated);
                context.runOnClient(c->{
                    var packet=TableDisplayClient.snapshots().stream().filter(p->p.tableId().equals(active.tableId())).findFirst().orElseThrow();
                    require(packet.steps().size()==2 && packet.steps().getFirst().serial()+1==packet.steps().getLast().serial(),"Actual TCP preserves both mutations from one observer interval");
                    require(TableDisplayClient.animating(active.tableId()) && !TableDisplayClient.frames(packet,System.nanoTime()).equals(dev.riichicraft.table.TableAnimationPlayer.still(packet.snapshot())),
                            "Authoritative snapshot has arrived while actual world poses are still moving");
                });
                context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1);shot(context,"riichicraft-animation-live-four");
                context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1);
                server.runOnServer(s -> {
                    var service=LobbyService.get(s); boolean reached=false;
                    for(int deal=0;deal<8 && !reached;deal++) {
                        for(int action=0;action<400;action++) {
                            var v=service.gameView(active.tableId(),null);
                            if(v.rules().phase()==GameSnapshot.FINISHED)break;
                            if(v.seats().stream().mapToInt(seat->seat.discards().size()).sum()>=16
                                    && v.rules().seats().stream().anyMatch(seat->!seat.melds().isEmpty())) { reached=true; break; }
                            advance(service,active,true);
                        }
                        if(!reached) { act(service,active,0,GameActionPayload.RETURN_LOBBY,-1,null); start(service,active); }
                    }
                    require(reached,"Bounded genuine deals establish a public meld and populated rivers");
                });
                saved=server.computeOnServer(s->LobbyService.get(s).tableDisplay(active.tableId()));
                awaitSnapshot(context,active.tableId(),saved);
                context.runOnClient(c->require(!TableDisplayClient.animating(active.tableId()),"A long legal burst converges without replaying seconds of old turns"));
                var received=current(context,active.tableId());
                assertWall(received);
                server.runOnServer(s -> {
                    var service=LobbyService.get(s); var hidden=new HashSet<Integer>();
                    for(var fixture:active.fixtures()) hidden.addAll(service.gameView(active.tableId(),fixture.player().getUUID()).ownHand());
                    List<Integer> exposed=new ArrayList<>(received.indicators());
                    for(var seat:received.seats()) { seat.river().forEach(d->exposed.add(d.tileId())); seat.melds().forEach(m->exposed.addAll(m.tiles())); exposed.addAll(seat.norths()); }
                    require(exposed.stream().noneMatch(hidden::contains),"Actual observer packet contains none of the four private hands");
                });
                // The anonymous nearby stream stays public; explicit watching gets a separate full-hand view.
                server.runOnServer(s->{
                    var actual=connection.getServerPlayer();actual.setGameMode(GameType.CREATIVE);
                    actual.teleportTo(active.center().getX()+.5,active.center().getY(),active.center().getZ()+3.2);
                    require(LobbyService.get(s).openImmersive(actual,active.center())!=null,"Explicit observer opens the table without occupying any chair");
                });
                connection.waitForClientboundPackets();context.waitForScreen(dev.riichicraft.client.TableScreen.class);
                context.runOnClient(c->{var scene=dev.riichicraft.client.TableInteractionClient.scene();
                    require(scene.ownSeat()==-1 && !scene.mounted() && scene.hands().stream().allMatch(hand->!hand.isEmpty()),
                            "A real TCP nonparticipant's explicit view includes every current concealed hand");});
                context.runOnClient(c->require(dev.riichicraft.client.TableCamera.overhead(),"An explicit live observer opens directly in the full-hand overhead camera"));
                context.waitTicks(3);
                shot(context,"riichicraft-immersive-spectator");
                server.runOnServer(s->{connection.getServerPlayer().setGameMode(GameType.SPECTATOR);
                    connection.getServerPlayer().teleportTo(active.center().getX()+.5,active.center().getY()+3,active.center().getZ()+1);
                    LobbyService.get(s).tick();});
                connection.waitForClientboundPackets();context.waitForScreen(null);
                context.runOnClient(c->require(!dev.riichicraft.client.TableCamera.active(),"Revoked explicit spectator view restores the original camera"));
                context.getInput().lookAt(active.center().above());
                context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1);
                shot(context,"riichicraft-tabletop-live-four");
                context.runOnClient(c->TabletopRenderFixture.show(active.center(),TableDisplayGameTests.showcase(false)));
                shot(context,"riichicraft-tabletop-layout-four");
                context.runOnClient(c->TabletopRenderFixture.show(active.center(),TableDisplayGameTests.revealedUra(false)));
                shot(context,"riichicraft-ura-layout-four");
                context.runOnClient(c->TabletopRenderFixture.hide());
                var kanScene=TableAnimationGameTests.scenes().stream().filter(s->s.name().equals("closed-kan-and-flip")).findFirst().orElseThrow();
                context.runOnClient(c->TabletopRenderFixture.showFrame(active.center(),kanScene.after(),TableAnimationGameTests.sample(kanScene,340_000_000L)));
                shot(context,"riichicraft-animation-kan-mid-layout");
                context.runOnClient(c->TabletopRenderFixture.show(active.center(),kanScene.after()));
                shot(context,"riichicraft-animation-kan-end-layout");
                context.runOnClient(c->TabletopRenderFixture.hide());
                context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1);
                server.runOnServer(s->connection.getServerPlayer().teleportTo(active.center().getX()+45.5,active.center().getY()+4,active.center().getZ()+0.5));
                await(context,active.tableId(),false);
                context.runOnClient(c->require(!TableDisplayClient.animating(active.tableId()),"Range removal clears transient playback"));
                server.runOnServer(s->connection.getServerPlayer().teleportTo(active.center().getX()+0.5,active.center().getY()+4,active.center().getZ()+1.0));
                awaitSnapshot(context,active.tableId(),saved);
                server.runOnServer(s->connection.getServerPlayer().teleportTo(s.getLevel(Level.NETHER),8.5,80,8.5,java.util.Set.of(),0,0,true));
                context.waitFor(c->c.level!=null && c.level.dimension().equals(Level.NETHER),400);
                await(context,active.tableId(),false);
                server.runOnServer(s->connection.getServerPlayer().teleportTo(s.overworld(),active.center().getX()+0.5,active.center().getY()+4,active.center().getZ()+1.0,java.util.Set.of(),0,0,true));
                context.waitFor(c->c.level!=null && c.level.dimension().equals(Level.OVERWORLD),400);
                awaitSnapshot(context,active.tableId(),saved);
                context.getInput().lookAt(active.center().above());
                server.runOnServer(s->{s.overworld().destroyBlock(active.center().south(2),false);LobbyService.get(s).tick();});
                context.waitFor(c->find(active.tableId())!=null && find(active.tableId()).paused(),200);
                var paused=current(context,active.tableId());
                context.runOnClient(c->require(!TableDisplayClient.animating(active.tableId()),"Pause settles immediately to the accepted static state"));
                require(paused.seats().equals(saved.seats()) && paused.indicators().equals(saved.indicators())
                        && paused.liveTiles()==saved.liveTiles() && paused.replacementDraws()==saved.replacementDraws(),"Broken chair pauses without losing any public tile or wall position");
                server.runOnServer(s->{FurnitureTestLayout.chairs(s.overworld(),active.center(),4);LobbyService.get(s).tick();
                    for(int i=0;i<4;i++)act(LobbyService.get(s),active,i,GameActionPayload.RESUME,-1,null);});
                awaitSnapshot(context,active.tableId(),saved);
                server.runOnServer(s->{var service=LobbyService.get(s);for(int i=0;i<500 && service.gameView(active.tableId(),null).rules().phase()!=GameSnapshot.FINISHED;i++)advance(service,active,false);
                    require(service.gameView(active.tableId(),null).rules().phase()==GameSnapshot.FINISHED,"Legal all-pass suffix finishes the live public round");});
                saved=server.computeOnServer(s->LobbyService.get(s).tableDisplay(active.tableId()));
                awaitSnapshot(context,active.tableId(),saved); require(saved.finished(),"Finished public tiles remain visible");
                context.runOnClient(c->require(!TableDisplayClient.animating(active.tableId()),"Settlement does not retain a stale animation"));
                assertWall(saved);
                shot(context,"riichicraft-tabletop-live-result");
            }
            var active=session;
            try(var connection=server.connect()) {
                connection.waitForChunksRender(); awaitSnapshot(context,active.tableId(),saved);
                require(context.computeOnClient(c->c.gui.screen()==null),"A late/reconnected bystander receives the complete result without GUI subscription");
                server.runOnServer(s->act(LobbyService.get(s),active,0,GameActionPayload.RETURN_LOBBY,-1,null));
                await(context,active.tableId(),false);
                server.runOnServer(s->start(LobbyService.get(s),active)); await(context,active.tableId(),true);
                server.runOnServer(s->s.overworld().destroyBlock(active.center(),false)); await(context,active.tableId(),false);
                server.runOnServer(s->require(LobbyService.get(s).gameView(active.tableId(),null)==null,"Breaking the table removes the authoritative round as well as its rendering"));
            }
        } finally {
            context.runOnClient(c->TabletopRenderFixture.hide());
            context.getInput().resizeWindow(640,480);
            if(session!=null) {var active=session;server.runOnServer(s->{for(var fixture:active.fixtures()) {LobbyService.get(s).disconnect(fixture.player());fixture.close();}});}
        }
    }
    private static void start(LobbyService service,Session session) {
        for(var fixture:session.fixtures()) {
            var view=service.open(fixture.player(),session.center());
            service.handleAction(fixture.player(),new LobbyActionPayload(view,service.registry().snapshot(session.tableId()).orElseThrow().revision(),LobbyActionPayload.SET_READY,true));
            service.handleAction(fixture.player(),new LobbyActionPayload(view,0,LobbyActionPayload.CLOSE,false));
        }
        var first=session.fixtures().getFirst().player(); var view=service.open(first,session.center());
        service.handleAction(first,new LobbyActionPayload(view,service.registry().snapshot(session.tableId()).orElseThrow().revision(),LobbyActionPayload.START,false));
        service.handleAction(first,new LobbyActionPayload(view,0,LobbyActionPayload.CLOSE,false));
        require(service.gameView(session.tableId(),null)!=null,"All native participants start a real round then close every view");
    }
    private static void advance(LobbyService service,Session session,boolean calls) {
        var v=service.gameView(session.tableId(),null);
        if(v.rules().phase()==GameSnapshot.DRAWN_TURN || v.rules().phase()==GameSnapshot.CALL_DISCARD) {
            int actor=v.turnSeat();var own=service.gameView(session.tableId(),session.fixtures().get(actor).player().getUUID());
            int tile=own.rules().legalDiscards().contains(own.drawnTile())?own.drawnTile():own.rules().legalDiscards().getFirst();
            if(calls && own.rules().seats().stream().allMatch(s->s.melds().isEmpty())) {
                outer:for(int candidate:own.rules().legalDiscards()) for(int seat=0;seat<4;seat++) if(seat!=actor) {
                    var other=service.gameView(session.tableId(),session.fixtures().get(seat).player().getUUID());
                    if(other.ownHand().stream().filter(t->t/4==candidate/4).count()>=2) {tile=candidate;break outer;}
                }
            }
            act(service,session,actor,GameActionPayload.DISCARD,tile,null);
        } else if(v.rules().phase()!=GameSnapshot.FINISHED) {
            long serial=v.rules().opportunity().serial();
            for(int seat=0;seat<4;seat++) {
                var own=service.gameView(session.tableId(),session.fixtures().get(seat).player().getUUID());
                if(own.rules().opportunity()==null || own.rules().opportunity().serial()!=serial || own.rules().responses().isEmpty())continue;
                var choice=calls?own.rules().responses().stream().filter(o->o.action()==GameActionPayload.PON || o.action()==GameActionPayload.CHI || o.action()==GameActionPayload.OPEN_KAN).findFirst().orElse(null):null;
                act(service,session,seat,choice==null?GameActionPayload.PASS:choice.action(),-1,choice);
            }
        }
    }
    private static void act(LobbyService service,Session session,int seat,int action,int tile,GameSnapshot.ActionOption choice) {
        ServerPlayer player=session.fixtures().get(seat).player(); var view=service.open(player,session.center());var own=service.gameView(session.tableId(),player.getUUID());
        long window=GameActionPayload.isResponse(action) && own.rules().opportunity()!=null?own.rules().opportunity().serial():-1;
        service.handleGameAction(player,new GameActionPayload(view,own.gameId(),own.revision(),action,tile,
                window,choice==null?-1:choice.meldIndex(),choice==null?List.of():choice.handTiles()));
        service.handleAction(player,new LobbyActionPayload(view,0,LobbyActionPayload.CLOSE,false));
    }
    private static TableDisplaySnapshot find(UUID id) { return TableDisplayClient.snapshots().stream().filter(p->p.tableId().equals(id)).map(TableDisplayPayload::snapshot).findFirst().orElse(null); }
    private static TableDisplaySnapshot current(ClientGameTestContext context,UUID id) { return context.computeOnClient(c->find(id)); }
    private static void await(ClientGameTestContext context,UUID id,boolean present) { context.waitFor(c->(find(id)!=null)==present,200); }
    private static void awaitSnapshot(ClientGameTestContext context,UUID id,TableDisplaySnapshot expected) {
        try { context.waitFor(c->expected.equals(find(id)),200); }
        catch (AssertionError failure) { throw new AssertionError("Public snapshot mismatch: expected="+expected+", actual="+current(context,id),failure); }
    }
    private static void shot(ClientGameTestContext context,String name) {context.runOnClient(c->{c.gui.toastManager().clear();c.gui.hud.getChat().clearMessages(true);});context.getInput().setCursorPos(0,0);context.waitTicks(3);context.takeScreenshot(name);}
    static void assertWall(TableDisplaySnapshot snapshot) {
        var wall=dev.riichicraft.table.TableTileLayout.tiles(snapshot).stream().filter(t->t.seat()==-1).toList();
        require(wall.size()==snapshot.liveTiles()+14,"Actual client projects precisely live plus fourteen reserve tiles");
        require(wall.stream().filter(t->t.face()>=0).map(dev.riichicraft.table.TableTileLayout.Tile::face).collect(java.util.stream.Collectors.toSet())
                .equals(new java.util.HashSet<>(snapshot.indicators())),"World wall faces contain exactly the already public indicators");
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
