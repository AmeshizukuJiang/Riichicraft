package dev.riichicraft.test;

import dev.riichicraft.client.LobbyScreen;
import dev.riichicraft.network.*;
import dev.riichicraft.server.LobbyService;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.lwjgl.glfw.GLFW;

/** One real TCP client plus TWO native server fixtures; mouse kita, recovery, and full two formats. */
final class ThreePlayerClientScenario {
    static void run(ClientGameTestContext context,TestDedicatedServerContext server) {
        DrawDiscardClientScenario.TableFixtures table=null;BlockPos target;UUID player;GameSnapshot saved;
        try {
            try(TestDedicatedServerConnection connection=server.connect()) {
                connection.waitForChunksRender();target=context.computeOnClient(c->c.player.blockPosition().offset(0,0,-2));
                MahjongTableClientGameTest.openTable(context,connection,target);
                context.clickScreenButton("screen.riichicraft.lobby.join");sync(connection);
                if(snapshot(context).seats().size()==4) {click(context,"screen.riichicraft.lobby.players.4");sync(connection);}
                while(snapshot(context).mode()!=3) {click(context,"screen.riichicraft.lobby.mode");sync(connection);}
                require(snapshot(context).seats().size()==3,"Actual count button selects three real seats");
                player=snapshot(context).seats().getFirst().playerId();
                table=DrawDiscardClientScenario.createFixtures(server,connection,target,snapshot(context).viewId());sync(connection);
                shot(context,"riichicraft-three-lobby"); start(context,server,connection,table);
                boolean north=false;
                for(int deal=0;deal<8 && !north;deal++) {
                    var active=table;
                    north=server.computeOnServer(s->{var service=LobbyService.get(s);for(int action=0;action<400;action++) {
                        var v=service.gameView(active.tableId(),connection.getServerPlayer().getUUID());
                        if(v.rules().phase()==GameSnapshot.FINISHED)return false;
                        if(v.turnSeat()==0 && !v.rules().kitas().isEmpty())return true;
                        DrawDiscardClientScenario.advance(service,active,connection.getServerPlayer());
                    }throw new AssertionError("Bounded seek for east north");});sync(connection);
                    if(!north) {context.clickScreenButton("screen.riichicraft.game.return_lobby");sync(connection);start(context,server,connection,table);}
                }
                require(north,"One of eight legitimate deals provides actual client's north turn");
                int live=snapshot(context).game().remainingTiles();
                click(context,snapshot(context).game().rules().canTsumo()?"screen.riichicraft.game.tsumo_menu":"screen.riichicraft.game.actions");
                click(context,"screen.riichicraft.game.kita");sync(connection);
                var active=table;
                server.runOnServer(s->{var service=LobbyService.get(s);for(int seat=1;seat<3;seat++) {
                    var responder=active.fixtures().get(seat-1).player();var v=service.gameView(active.tableId(),responder.getUUID());
                    if(!v.rules().responses().isEmpty())service.handleGameAction(responder,new GameActionPayload(active.viewIds().get(seat),v.gameId(),v.revision(),GameActionPayload.PASS,-1,v.rules().opportunity().serial(),-1,List.of()));
                }});sync(connection);
                saved=snapshot(context).game();
                require(saved.remainingTiles()==live-1 && saved.rules().seats().getFirst().norths().size()==1,"Actual mouse kita establishes one north and draws once");
                shot(context,"riichicraft-three-kita");
            }
            var active=table;server.waitFor(s->LobbyService.get(s).gameView(active.tableId(),player).paused());
            try(TestDedicatedServerConnection connection=server.connect()) {
                connection.waitForChunksRender();MahjongTableClientGameTest.openTable(context,connection,target);
                var views=new ArrayList<>(table.viewIds());views.set(0,snapshot(context).viewId());table=new DrawDiscardClientScenario.TableFixtures(table.fixtures(),List.copyOf(views),table.tableId());
                require(snapshot(context).game().paused() && snapshot(context).game().ownHand().equals(saved.ownHand()) && snapshot(context).game().rules().equals(saved.rules()),"TCP reconnect retains three-player hand, north and exact rule window");
                var joined=table;
                server.runOnServer(s->{var service=LobbyService.get(s);for(int seat=1;seat<3;seat++) {var v=service.gameView(joined.tableId(),joined.fixtures().get(seat-1).player().getUUID());service.handleGameAction(joined.fixtures().get(seat-1).player(),new GameActionPayload(joined.viewIds().get(seat),v.gameId(),v.revision(),GameActionPayload.RESUME,-1));}});sync(connection);
                require(snapshot(context).game().paused(),"Two native recovery votes cannot resume three-player game");
                context.clickScreenButton("screen.riichicraft.game.resume");sync(connection);
                require(!snapshot(context).game().paused(),"Actual third mouse recovery vote resumes");shot(context,"riichicraft-three-resumed");
                context.clickScreenButton("screen.riichicraft.game.abort");require(snapshot(context).game()!=null,"First terminate click only arms confirmation");
                context.clickScreenButton("screen.riichicraft.game.confirm_abort");sync(connection);
                for(int format=1;format<=2;format++) {
                    while(snapshot(context).mode()!=3+format) {click(context,"screen.riichicraft.lobby.mode");sync(connection);}
                    start(context,server,connection,table);boolean south=false;int hands=0;
                    while(snapshot(context).game().match().phase()!=GameSnapshot.MATCH_RESULT) {
                        require(++hands<=40,"Finite full three-player match");
                        var round=snapshot(context).game();if(format==2 && round.rules().roundWind()==28) {south=true;shot(context,"riichicraft-three-south");}
                        var owned=table;
                        server.runOnServer(s->{var service=LobbyService.get(s);for(int step=0;service.gameView(owned.tableId(),connection.getServerPlayer().getUUID()).rules().phase()!=GameSnapshot.FINISHED;step++) {
                            require(step<400,"Finite complete round");DrawDiscardClientScenario.advance(service,owned,connection.getServerPlayer());
                        }});sync(connection);
                        if(snapshot(context).game().match().phase()==GameSnapshot.MATCH_RESULT)break;
                        server.runOnServer(s->{var service=LobbyService.get(s);for(int seat=1;seat<3;seat++) {var v=service.gameView(owned.tableId(),owned.fixtures().get(seat-1).player().getUUID());service.handleGameAction(owned.fixtures().get(seat-1).player(),new GameActionPayload(owned.viewIds().get(seat),v.gameId(),v.revision(),GameActionPayload.NEXT_ROUND,-1,v.match().continueEpoch(),-1,List.of()));}});sync(connection);
                        require(snapshot(context).game().rules().phase()==GameSnapshot.FINISHED,"Two votes retain old paid result");
                        context.clickScreenButton("screen.riichicraft.game.next_round");sync(connection);
                        require(snapshot(context).game().remainingTiles()==54,"Actual third vote deals next 108-tile round");
                    }
                    var result=snapshot(context).game().match().result();
                    require(result.standings().size()==3 && result.standings().stream().mapToInt(GameSnapshot.Standing::points).sum()==105000,"Three final ranks conserve 105000 points");
                    require(format!=2 || south,"Half game genuinely entered south wind");shot(context,"riichicraft-three-final-"+format);
                    context.clickScreenButton("screen.riichicraft.game.return_lobby");sync(connection);
                }
            }
        } finally {if(table!=null)DrawDiscardClientScenario.closeFixtures(server,table);}
    }
    static void start(ClientGameTestContext context,TestDedicatedServerContext server,TestDedicatedServerConnection connection,DrawDiscardClientScenario.TableFixtures table) {
        server.runOnServer(s->{var service=LobbyService.get(s);for(int seat=1;seat<3;seat++)service.handleAction(table.fixtures().get(seat-1).player(),new LobbyActionPayload(table.viewIds().get(seat),service.registry().snapshot(table.tableId()).orElseThrow().revision(),LobbyActionPayload.SET_READY,true));});
        sync(connection);context.clickScreenButton("screen.riichicraft.lobby.prepare");sync(connection);context.clickScreenButton("screen.riichicraft.lobby.start");sync(connection);
    }
    static void click(ClientGameTestContext context,String key) {
        double[] point=context.computeOnClient(c->{Button button=c.gui.screen().children().stream().filter(child->child instanceof Button).map(child->(Button)child)
            .filter(b->b.active&&b.visible&&b.getMessage().getContents() instanceof TranslatableContents t&&t.getKey().equals(key)).findFirst().orElseThrow();var w=c.getWindow();return new double[]{(button.getX()+button.getWidth()/2.0)*w.getScreenWidth()/w.getGuiScaledWidth(),(button.getY()+button.getHeight()/2.0)*w.getScreenHeight()/w.getGuiScaledHeight()};});
        context.getInput().setCursorPos(point[0],point[1]);context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    static LobbySnapshotPayload snapshot(ClientGameTestContext context){return context.computeOnClient(c->((LobbyScreen)c.gui.screen()).snapshot());}
    static void sync(TestServerConnection connection){connection.waitForServerboundPackets();connection.waitForClientboundPackets();}
    static void shot(ClientGameTestContext context,String name){context.getInput().setCursorPos(0,0);context.runOnClient(c->c.gui.toastManager().clear());context.waitTick();context.takeScreenshot(name);}
    static void require(boolean good,String detail){if(!good)throw new AssertionError(detail);}
}
