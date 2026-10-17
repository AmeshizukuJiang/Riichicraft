package dev.riichicraft.test;

import dev.riichicraft.network.*;
import dev.riichicraft.server.LobbyService;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import static dev.riichicraft.test.ThreePlayerClientScenario.*;

/** Genuine graceful dedicated-server replacement, same on-disk world, three identities, and kita state. */
final class ThreePlayerPersistenceClientScenario {
    private record Identity(UUID id,String name) { }
    private record Core(UUID game,int actor,int live,List<Integer> hand,int draw,GameSnapshot.RuleState rules) {
        static Core of(GameSnapshot v){return new Core(v.gameId(),v.turnSeat(),v.remainingTiles(),v.ownHand(),v.drawnTile(),v.rules());}
    }
    private static final class Session {
        DrawDiscardClientScenario.TableFixtures table;BlockPos target;UUID player,obsoleteView;List<Identity> identities;List<Core> saved;
    }
    static void run(ClientGameTestContext context,Properties properties) {
        var session=new Session();
        try(var first=PersistenceClientScenario.reopen(context,properties)) {
            try {
                try(var connection=first.connect()) {
                    connection.waitForChunksRender();session.target=context.computeOnClient(c->c.player.blockPosition().offset(0,0,-2));
                    first.runCommand("gamemode creative @a");first.runCommand("execute as @a at @s run setblock ~ ~ ~-2 riichicraft:mahjong_table[facing=south]");
                    MahjongTableClientGameTest.openTable(context,connection,session.target);context.clickScreenButton("screen.riichicraft.lobby.join");sync(connection);
                    if(snapshot(context).seats().size()==4){click(context,"screen.riichicraft.lobby.players.4");sync(connection);}
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
                    first.runOnServer(s->{var service=LobbyService.get(s);session.saved=cores(service,session);session.identities=session.table.fixtures().stream().map(f->new Identity(f.player().getUUID(),f.player().getGameProfile().name())).toList();require(service.save(),"Real sanma checkpoint written");});
                    shot(context,"riichicraft-three-before-stop");
                }
            } finally {if(session.table!=null)DrawDiscardClientScenario.closeFixtures(first,session.table);}
        }
        UUID tableId=session.table.tableId();session.table=null;
        try(var second=PersistenceClientScenario.reopen(context,properties)) {
            try {
                second.runOnServer(s->require(LobbyService.get(s).registry().tableForPlayer(session.player).orElseThrow().equals(tableId),"Three-player UUID ownership restored before TCP connect"));
                try(var connection=second.connect()) {
                    connection.waitForChunksRender();MahjongTableClientGameTest.openTable(context,connection,session.target);
                    UUID currentView=snapshot(context).viewId();
                    session.table=second.computeOnServer(s->{var service=LobbyService.get(s);
                        var fixtures=new ArrayList<ConnectedTestPlayer>();var views=new ArrayList<UUID>();views.add(currentView);
                        // Opening needs an accessible FULL chunk; wait for the real client to load it first.
                        for(var identity:session.identities){var fixture=new ConnectedTestPlayer(s,s.overworld(),session.target,identity.id(),identity.name());fixtures.add(fixture);UUID view=service.open(fixture.player(),session.target);require(view!=null,"Restored native participant can access loaded table");views.add(view);}
                        return new DrawDiscardClientScenario.TableFixtures(List.copyOf(fixtures),List.copyOf(views),tableId);});
                    sync(connection);
                    second.runOnServer(s->require(cores(LobbyService.get(s),session).equals(session.saved),"Actual new server restores all three private hands, live wall, kita and pending draw source without redealing"));
                    require(snapshot(context).game().paused()&&snapshot(context).seats().size()==3,"Restored sanma waits for three confirmations");shot(context,"riichicraft-three-restored");
                    var current=snapshot(context).game();
                    context.runOnClient(c->ClientPlayNetworking.send(new GameActionPayload(session.obsoleteView,current.gameId(),current.revision(),GameActionPayload.RESUME,-1)));sync(connection);
                    require(snapshot(context).game().seats().stream().noneMatch(GameSnapshot.Seat::confirmed),"Pre-restart view rejected over new real TCP connection");
                    second.runOnServer(s->{var service=LobbyService.get(s);for(int seat=1;seat<3;seat++){var own=service.gameView(tableId,player(session,connection,seat).getUUID());service.handleGameAction(player(session,connection,seat),new GameActionPayload(session.table.viewIds().get(seat),own.gameId(),own.revision(),GameActionPayload.RESUME,-1));}});sync(connection);
                    context.clickScreenButton("screen.riichicraft.game.resume");sync(connection);
                    require(!snapshot(context).game().paused(),"Actual third recovery click resumes saved sanma");shot(context,"riichicraft-three-restart-resumed");
                    context.clickScreenButton("screen.riichicraft.game.abort");context.clickScreenButton("screen.riichicraft.game.confirm_abort");sync(connection);require(snapshot(context).game()==null,"Confirmed terminate clears restored sanma");
                }
            } finally {if(session.table!=null)DrawDiscardClientScenario.closeFixtures(second,session.table);}
        }
    }
    private static ServerPlayer player(Session session,TestDedicatedServerConnection connection,int seat){return seat==0?connection.getServerPlayer():session.table.fixtures().get(seat-1).player();}
    private static List<Core> cores(LobbyService service,Session session){var result=new ArrayList<Core>();result.add(Core.of(service.gameView(session.table.tableId(),session.player)));for(var fixture:session.table.fixtures())result.add(Core.of(service.gameView(session.table.tableId(),fixture.player().getUUID())));return List.copyOf(result);}
}
