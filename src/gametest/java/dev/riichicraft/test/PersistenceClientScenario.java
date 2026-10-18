package dev.riichicraft.test;

import dev.riichicraft.client.LobbyScreen;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import dev.riichicraft.server.LobbyService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.impl.client.gametest.context.TestDedicatedServerContextImpl;
import net.fabricmc.fabric.impl.client.gametest.util.DedicatedServerImplUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Same JVM, two genuinely distinct native dedicated servers, one on-disk world and real TCP reconnect. */
@SuppressWarnings("UnstableApiUsage")
final class PersistenceClientScenario {
    private record CoreView(UUID game,int actor,int remaining,List<Integer> hand,int draw,GameSnapshot.RuleState rules) {
        static CoreView of(GameSnapshot view) { return new CoreView(view.gameId(),view.turnSeat(),view.remainingTiles(),view.ownHand(),view.drawnTile(),view.rules()); }
    }
    private record Identity(UUID player,String name) { }
    private static final class Session {
        DrawDiscardClientScenario.TableFixtures table;
        List<Identity> identities; List<CoreView> held; List<Integer> pending;
        BlockPos target; UUID player; UUID tableId; UUID obsoleteView; Path archive; long serial;
    }

    static void run(ClientGameTestContext context,Properties properties) {
        Session session=new Session();
        // The public world builder deletes level-name. Reopen only through the exact pinned test API's native starter.
        // The caller already verified EULA, the project-local run directory, and this fixed child world path.
        try(TestDedicatedServerContext first=reopen(context,properties)) {
            try {
                try(TestDedicatedServerConnection connection=first.connect()) {
                    connection.waitForChunksRender();
                    session.target=context.computeOnClient(c->c.player.blockPosition().offset(0,0,-2));
                    first.runCommand("gamemode creative @a");
                    first.runCommand("execute as @a at @s run setblock ~ ~ ~-2 riichicraft:mahjong_table[facing=south]");
                    connection.waitForClientboundPackets();
                    MahjongTableClientGameTest.openTable(context,connection,session.target);
                    context.clickScreenButton("screen.riichicraft.lobby.join"); sync(connection);
                    require(snapshot(context).ownSeat()==0 && snapshot(context).mode()==0,
                            "The new persistence fixture starts in the transient single-round lobby");
                    session.player=snapshot(context).seats().getFirst().playerId(); session.obsoleteView=snapshot(context).viewId();
                    session.table=DrawDiscardClientScenario.createFixtures(first,connection,session.target,session.obsoleteView);
                    session.tableId=session.table.tableId();
                    sync(connection); context.clickScreenButton("screen.riichicraft.lobby.prepare"); sync(connection);
                    context.clickScreenButton("screen.riichicraft.lobby.start"); sync(connection);
                    boolean found=false;
                    for(int deal=0;deal<16 && !found;deal++) {
                        found=first.computeOnServer(s->seekPartial(LobbyService.get(s),session,connection));
                        sync(connection);
                        if(!found && deal<15) {
                            require(snapshot(context).game().rules().phase()==GameSnapshot.FINISHED,
                                    "An unsuccessful bounded search completes its real played round before returning to the lobby");
                            context.clickScreenButton("screen.riichicraft.game.return_lobby");sync(connection);
                            first.runOnServer(s->{var service=LobbyService.get(s);for(int seat=1;seat<4;seat++) {
                                service.handleAction(player(session,connection,seat),new LobbyActionPayload(session.table.viewIds().get(seat),
                                        service.registry().snapshot(session.tableId).orElseThrow().revision(),LobbyActionPayload.SET_READY,true));
                            }});
                            sync(connection);context.clickScreenButton("screen.riichicraft.lobby.prepare");sync(connection);
                            context.clickScreenButton("screen.riichicraft.lobby.start");sync(connection);
                        }
                    }
                    require(found,"At least one of sixteen legally guided real deals must actually retain a partially answered multi-player window");
                    first.runOnServer(s->{
                        LobbyService service=LobbyService.get(s);
                        session.held=coreViews(service,session); session.pending=pending(service,session);
                        session.serial=service.gameView(session.table.tableId(),session.player).rules().opportunity().serial();
                        session.identities=session.table.fixtures().stream().map(f->new Identity(f.player().getUUID(),f.player().getGameProfile().name())).toList();
                        session.archive=s.getWorldPath(LevelResource.ROOT).resolve("data/riichicraft/tables.dat");
                        require(service.save(),"The real server completes a successful durable checkpoint before graceful shutdown");
                    });
                    sync(connection); shot(context,"riichicraft-persistence-before-stop");
                }
            } finally {
                if(session.table!=null) DrawDiscardClientScenario.closeFixtures(first,session.table);
            }
        }
        session.table=null;
        require(Files.isRegularFile(session.archive),"The first dedicated server leaves its archive in the same project-local world");
        // All connections, player fixtures, native server thread and the first LobbyService have now been closed.
        try(TestDedicatedServerContext second=reopen(context,properties)) {
            try {
                second.runOnServer(s->{
                    LobbyService service=LobbyService.get(s);
                    require(service.registry().tableForPlayer(session.player).orElseThrow().equals(session.tableId),
                            "The new native server restores global occupancy before the real player reconnects");
                    for(Identity identity:session.identities) require(service.registry().tableForPlayer(identity.player()).orElseThrow().equals(session.tableId),
                            "Absent native identities still own their archived seats before their fixture objects exist");
                    require(coreViews(service,session).equals(session.held) && service.gameView(session.tableId,session.player).paused(),
                            "A new native server reads exactly the same private hands, live count and partly collected window from disk");
                });
                try(TestDedicatedServerConnection connection=second.connect()) {
                    connection.waitForChunksRender();
                    session.table=second.computeOnServer(s->{
                        List<ConnectedTestPlayer> fixtures=new ArrayList<>(); List<UUID> views=new ArrayList<>(); views.add(UUID.randomUUID());
                        for(Identity identity:session.identities) {
                            var fixture=new ConnectedTestPlayer(s,connection.getServerLevel(),session.target,identity.player(),identity.name());
                            fixtures.add(fixture); views.add(LobbyService.get(s).open(fixture.player(),session.target));
                        }
                        return new DrawDiscardClientScenario.TableFixtures(List.copyOf(fixtures),List.copyOf(views),session.tableId);
                    });
                    MahjongTableClientGameTest.openTable(context,connection,session.target);
                    List<UUID> freshViews=new ArrayList<>(session.table.viewIds());freshViews.set(0,snapshot(context).viewId());
                    session.table=new DrawDiscardClientScenario.TableFixtures(session.table.fixtures(),List.copyOf(freshViews),session.table.tableId());
                    GameSnapshot restored=snapshot(context).game();
                    require(snapshot(context).ownSeat()==0 && restored.paused() && restored.seats().stream().allMatch(GameSnapshot.Seat::present)
                                    && restored.rules().opportunity().serial()==session.serial && !snapshot(context).viewId().equals(session.obsoleteView),
                            "The real returning client receives its original seat and window through a fresh authenticated view, paused for recovery");
                    second.runOnServer(s->require(coreViews(LobbyService.get(s),session).equals(session.held)
                                    && pending(LobbyService.get(s),session).equals(session.pending),
                            "Recreating connected players preserves the archived partial response choices and every physical hand"));
                    context.runOnClient(c->ClientPlayNetworking.send(new GameActionPayload(session.obsoleteView,restored.gameId(),restored.revision(),
                            GameActionPayload.ABORT,-1)));
                    sync(connection);
                    require(snapshot(context).game().revision()==restored.revision(),"The previous native server's view token cannot abort a restored game over TCP");
                    shot(context,"riichicraft-persistence-restored");
                    second.runOnServer(s->{
                        LobbyService service=LobbyService.get(s);
                        for(int seat=1;seat<4;seat++) {
                            ServerPlayer player=player(session,connection,seat); GameSnapshot own=service.gameView(session.table.tableId(),player.getUUID());
                            service.handleGameAction(player,new GameActionPayload(session.table.viewIds().get(seat),own.gameId(),own.revision(),GameActionPayload.RESUME,-1));
                        }
                    });
                    sync(connection); require(snapshot(context).game().paused(),"Three native recovery votes cannot resume the real client's game");
                    context.clickScreenButton("screen.riichicraft.game.resume"); sync(connection);
                    require(!snapshot(context).game().paused(),"The real fourth resume mouse button restarts the stored response window");
                    second.runOnServer(s->require(coreViews(LobbyService.get(s),session).equals(session.held),"Recovery does not reshuffle, redraw or resolve an unsubmitted response"));
                    for(int seat:session.pending) {
                        if(seat==0) { context.clickScreenButton("screen.riichicraft.game.pass"); sync(connection); }
                        else {
                            final int responder=seat;
                            second.runOnServer(s->{var service=LobbyService.get(s);ServerPlayer player=player(session,connection,responder);
                                var own=service.gameView(session.table.tableId(),player.getUUID());
                                service.handleGameAction(player,new GameActionPayload(session.table.viewIds().get(responder),own.gameId(),own.revision(),
                                        GameActionPayload.PASS,-1,session.serial,-1,List.of()));});
                            sync(connection);
                        }
                    }
                    require(snapshot(context).game().rules().phase()==GameSnapshot.DRAWN_TURN,
                            "Only the remaining original responses resolve the stored window exactly once after real server restart");
                    second.runOnServer(s->DrawDiscardClientScenario.advance(LobbyService.get(s),session.table,connection.getServerPlayer()));
                    sync(connection); shot(context,"riichicraft-persistence-resumed");
                    context.clickScreenButton("screen.riichicraft.game.abort");sync(connection);
                    context.clickScreenButton("screen.riichicraft.game.confirm_abort");sync(connection);
                    require(snapshot(context).game()==null,"The actual second abort button clears the restored session and archive");
                }
            } finally {
                if(session.table!=null) DrawDiscardClientScenario.closeFixtures(second,session.table);
            }
        }
    }

    static TestDedicatedServerContext reopen(ClientGameTestContext context,Properties properties) {
        return new TestDedicatedServerContextImpl(context,DedicatedServerImplUtil.start(context,properties));
    }
    private static boolean seekPartial(LobbyService service,Session session,TestDedicatedServerConnection connection) {
        for(int action=0;action<400;action++) {
            GameSnapshot state=service.gameView(session.tableId,session.player);
            if(state.rules().phase()==GameSnapshot.FINISHED) return false;
            List<Integer> pending=pending(service,session);
            if(pending.size()>=2) {
                int seat=pending.getFirst();ServerPlayer responder=player(session,connection,seat);
                var own=service.gameView(session.tableId,responder.getUUID());
                service.handleGameAction(responder,new GameActionPayload(session.table.viewIds().get(seat),own.gameId(),own.revision(),
                        GameActionPayload.PASS,-1,own.rules().opportunity().serial(),-1,List.of()));
                return true;
            }
            if(!pending.isEmpty()) DrawDiscardClientScenario.advance(service,session.table,connection.getServerPlayer());
            else {
                List<GameSnapshot> recipients=new ArrayList<>(4);
                for(int seat=0;seat<4;seat++) recipients.add(service.gameView(session.tableId,player(session,connection,seat).getUUID()));
                int actor=state.turnSeat(),tile=PersistenceTestActions.multiResponseDiscard(recipients);
                service.handleGameAction(player(session,connection,actor),new GameActionPayload(session.table.viewIds().get(actor),state.gameId(),
                        state.revision(),GameActionPayload.DISCARD,tile));
            }
        }
        throw new AssertionError("One legal search deal exceeded its finite tile and response budget");
    }
    private static ServerPlayer player(Session session,TestDedicatedServerConnection connection,int seat) {
        return seat==0?connection.getServerPlayer():session.table.fixtures().get(seat-1).player();
    }
    private static UUID identity(Session session,int seat) { return seat==0?session.player:session.identities.get(seat-1).player(); }
    private static List<CoreView> coreViews(LobbyService service,Session session) {
        List<CoreView> views=new ArrayList<>(4);
        for(int seat=0;seat<4;seat++) views.add(CoreView.of(service.gameView(session.tableId,
                session.identities==null?(seat==0?session.player:session.table.fixtures().get(seat-1).player().getUUID()):identity(session,seat))));
        return List.copyOf(views);
    }
    private static List<Integer> pending(LobbyService service,Session session) {
        List<Integer> seats=new ArrayList<>();
        for(int seat=0;seat<4;seat++) {
            UUID id=session.identities==null?(seat==0?session.player:session.table.fixtures().get(seat-1).player().getUUID()):identity(session,seat);
            if(!service.gameView(session.tableId,id).rules().responses().isEmpty()) seats.add(seat);
        }
        return List.copyOf(seats);
    }
    private static LobbySnapshotPayload snapshot(ClientGameTestContext context) { return context.computeOnClient(c->((LobbyScreen)c.gui.screen()).snapshot()); }
    private static void shot(ClientGameTestContext context,String name) { context.runOnClient(c->c.gui.toastManager().clear());context.waitTick();context.takeScreenshot(name); }
    private static void sync(TestDedicatedServerConnection connection) { connection.waitForServerboundPackets();connection.waitForClientboundPackets(); }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
