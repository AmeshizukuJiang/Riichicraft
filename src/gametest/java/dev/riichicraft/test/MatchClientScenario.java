package dev.riichicraft.test;

import dev.riichicraft.client.LobbyScreen;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import dev.riichicraft.server.LobbyService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.glfw.GLFW;

/** One actual TCP client plus three native server fixtures, including a complete east match and reconnects. */
final class MatchClientScenario {
    private static final class Session {
        DrawDiscardClientScenario.TableFixtures table; BlockPos target; UUID player;
        void useView(UUID view) {
            List<UUID> views=new ArrayList<>(table.viewIds());views.set(0,view);
            table=new DrawDiscardClientScenario.TableFixtures(table.fixtures(),List.copyOf(views),table.tableId());
        }
    }

    static void run(ClientGameTestContext context,TestDedicatedServerContext server) {
        Session session=new Session(); GameSnapshot firstResult; long obsoleteEpoch; UUID matchId;
        GameSnapshot.MatchResult finalResult; UUID finalRound;
        try {
            try(TestDedicatedServerConnection connection=server.connect()) {
                connection.waitForChunksRender();session.target=context.computeOnClient(c->c.player.blockPosition().offset(0,0,-2));
                MahjongTableClientGameTest.openTable(context,connection,session.target);
                context.clickScreenButton("screen.riichicraft.lobby.join");sync(connection);
                require(snapshot(context).ownSeat()==0,"A separate match scenario must start from the released east lobby seat");
                session.player=snapshot(context).seats().getFirst().playerId();
                session.table=DrawDiscardClientScenario.createFixtures(server,connection,session.target,snapshot(context).viewId());sync(connection);
                require(snapshot(context).mode()==0,"The old single-round regression leaves the mode explicitly single");
                clickMode(context);sync(connection);
                require(snapshot(context).mode()==1 && snapshot(context).seats().stream().noneMatch(LobbySnapshotPayload.Seat::ready),
                        "The actual east mode button changes format through TCP and clears prior fixture preparation");
                shot(context,"riichicraft-match-lobby");prepareAndStart(context,server,connection,session);
                GameSnapshot initial=snapshot(context).game();matchId=initial.match().matchId();
                require(initial.match().format()==GameSnapshot.FORMAT_EAST && initial.rules().dealerSeat()==0
                                && initial.rules().roundWind()==27 && initial.rules().honba()==0 && initial.remainingTiles()==69,
                        "The real decoded east match begins at east-one with initial dealer zero");
                finishRound(server,connection,session);sync(connection);firstResult=snapshot(context).game();
                require(firstResult.match().phase()==GameSnapshot.MATCH_ROUND_RESULT && firstResult.rules().result()!=null,
                        "The first completed real hand remains as a paid intermission");
                shot(context,"riichicraft-match-intermission");
                context.clickScreenButton("screen.riichicraft.game.next_round");sync(connection);
                require(snapshot(context).game().match().confirmations().equals(List.of(true,false,false,false)),
                        "The actual continue button casts only the real client's vote");
                voteNative(server,connection,session,1,1);sync(connection);
                require(snapshot(context).game().match().confirmations().equals(List.of(true,true,false,false)),
                        "A second participant's stable vote does not begin the next round");
                obsoleteEpoch=snapshot(context).game().match().continueEpoch();
            }
            server.waitFor(s->{var state=LobbyService.get(s).gameView(session.table.tableId(),session.player);
                return state!=null && !state.seats().getFirst().present() && !state.paused()
                        && state.match().continueEpoch()>obsoleteEpoch && state.match().confirmations().stream().noneMatch(Boolean::booleanValue);});
            try(TestDedicatedServerConnection connection=server.connect()) {
                connection.waitForChunksRender();MahjongTableClientGameTest.openTable(context,connection,session.target);
                session.useView(snapshot(context).viewId());GameSnapshot restored=snapshot(context).game();
                require(snapshot(context).ownSeat()==0 && !restored.paused() && restored.match().matchId().equals(matchId)
                                && restored.gameId().equals(firstResult.gameId()) && restored.rules().result().equals(firstResult.rules().result())
                                && restored.match().confirmations().stream().noneMatch(Boolean::booleanValue),
                        "Real intermission reconnect retains seat and paid result while revoking every pre-disconnect continuation vote");
                shot(context,"riichicraft-match-intermission-reconnected");
                UUID currentView=snapshot(context).viewId();
                context.runOnClient(c->ClientPlayNetworking.send(new GameActionPayload(currentView,restored.gameId(),
                        restored.revision(),GameActionPayload.NEXT_ROUND,-1,obsoleteEpoch,-1,List.of())));
                sync(connection);
                require(snapshot(context).game().match().confirmations().stream().noneMatch(Boolean::booleanValue),
                        "A delayed pre-disconnect epoch is rejected over the new real connection");
                LobbySnapshotPayload oldRoundPacket=snapshot(context);
                voteNative(server,connection,session,1,3);sync(connection);
                require(snapshot(context).game().gameId().equals(restored.gameId())
                                && snapshot(context).game().match().confirmations().stream().filter(Boolean::booleanValue).count()==3,
                        "Three native votes keep the actual old result until the returning real client confirms");
                context.clickScreenButton("screen.riichicraft.game.next_round");sync(connection);
                GameSnapshot next=snapshot(context).game();assertNext(restored,next);
                server.runOnServer(s->ServerPlayNetworking.send(connection.getServerPlayer(),oldRoundPacket));sync(connection);
                require(snapshot(context).game().gameId().equals(next.gameId()) && snapshot(context).game().revision()==next.revision(),
                        "A genuinely transmitted late old-round payload with the same view and match cannot roll the client back after the new round UUID changes");
                boolean eastTwo=next.match().roundIndex()==1;
                if(eastTwo)shot(context,"riichicraft-match-next-round");
                int hands=1;
                while(snapshot(context).game().match().phase()!=GameSnapshot.MATCH_RESULT) {
                    require(++hands<=32,"A complete all-pass east match must finish within a finite real-hand budget");
                    finishRound(server,connection,session);sync(connection);GameSnapshot paid=snapshot(context).game();
                    if(paid.match().phase()==GameSnapshot.MATCH_RESULT)break;
                    voteNative(server,connection,session,1,3);sync(connection);
                    context.clickScreenButton("screen.riichicraft.game.next_round");sync(connection);
                    GameSnapshot fresh=snapshot(context).game();assertNext(paid,fresh);
                    if(!eastTwo && fresh.match().roundIndex()==1){eastTwo=true;shot(context,"riichicraft-match-next-round");}
                }
                GameSnapshot finished=snapshot(context).game();finalResult=finished.match().result();finalRound=finished.gameId();
                require(eastTwo && finalResult!=null && finalResult.standings().size()==4
                                && finalResult.standings().stream().mapToInt(GameSnapshot.Standing::points).sum()==100_000
                                && finalResult.standings().stream().mapToInt(GameSnapshot.Standing::totalTenths).sum()==0,
                        "Real east-one to east-two play continues through actual final standings with conserved table points and zero-sum final scores");
                shot(context,"riichicraft-match-final");
            }
            server.waitFor(s->{var game=LobbyService.get(s).gameView(session.table.tableId(),session.player);
                return game!=null && game.match().phase()==GameSnapshot.MATCH_RESULT && !game.seats().getFirst().present() && !game.paused();});
            try(TestDedicatedServerConnection connection=server.connect()) {
                connection.waitForChunksRender();MahjongTableClientGameTest.openTable(context,connection,session.target);
                session.useView(snapshot(context).viewId());GameSnapshot returned=snapshot(context).game();
                require(returned.gameId().equals(finalRound) && returned.match().matchId().equals(matchId)
                                && returned.match().result().equals(finalResult) && !returned.paused(),
                        "Real terminal reconnect preserves the same final ranks and paid accounts without requiring recovery votes");
                shot(context,"riichicraft-match-final-reconnected");
                context.clickScreenButton("screen.riichicraft.game.return_lobby");sync(connection);
                require(snapshot(context).game()==null && snapshot(context).mode()==1
                                && snapshot(context).seats().stream().noneMatch(LobbySnapshotPayload.Seat::ready),
                        "The real final-result return button clears the table for a new match without changing its selected format");
                clickMode(context);sync(connection);
                require(snapshot(context).mode()==2,"The same actual mode button can now select south for the next match");
                shot(context,"riichicraft-match-lobby-south");prepareAndStart(context,server,connection,session);
                require(snapshot(context).game().match().format()==GameSnapshot.FORMAT_SOUTH
                                && !snapshot(context).game().match().matchId().equals(matchId)
                                && snapshot(context).game().rules().seats().stream().allMatch(seat->seat.points()==25_000),
                        "A new south match starts fresh instead of reusing the completed east match's accounts");
                boolean southSeen=false;int southHands=0;
                while(snapshot(context).game().match().phase()!=GameSnapshot.MATCH_RESULT) {
                    require(++southHands<=48,"The actual south match finishes within a bounded real-hand budget");
                    GameSnapshot start=snapshot(context).game();
                    if(start.match().roundIndex()>=4 && start.match().roundIndex()<=7) {
                        require(start.rules().roundWind()==28 && start.rules().dealerSeat()==start.match().roundIndex()%4,
                                "The real client reaches south wind with a correctly rotated current dealer");
                        if(!southSeen){southSeen=true;shot(context,"riichicraft-match-south-round");}
                    }
                    finishRound(server,connection,session,true);sync(connection);GameSnapshot paidSouth=snapshot(context).game();
                    if(paidSouth.match().phase()==GameSnapshot.MATCH_RESULT)break;
                    voteNative(server,connection,session,1,3);sync(connection);
                    context.clickScreenButton("screen.riichicraft.game.next_round");sync(connection);
                    assertNext(paidSouth,snapshot(context).game());
                }
                var southFinal=snapshot(context).game().match().result();
                require(southSeen && southFinal!=null && southFinal.standings().size()==4
                                && southFinal.standings().stream().mapToInt(GameSnapshot.Standing::points).sum()==100_000
                                && southFinal.standings().stream().mapToInt(GameSnapshot.Standing::totalTenths).sum()==0,
                        "The complete real south match visits south wind and conserves final table points and ranked scores");
                shot(context,"riichicraft-match-south-final");
                context.clickScreenButton("screen.riichicraft.game.return_lobby");sync(connection);
                require(snapshot(context).game()==null && snapshot(context).mode()==2,"South final return retains the chosen format at the same physical table");
                prepareAndStart(context,server,connection,session);
                GameSnapshot shortMatch=snapshot(context).game();
                require(shortMatch.rules().seats().stream().allMatch(seat->seat.points()==25_000),"The separate short abort fixture starts with new accounts after the completed south match");
                context.clickScreenButton("screen.riichicraft.game.abort_match");sync(connection);
                require(snapshot(context).game()!=null && snapshot(context).game().revision()==shortMatch.revision(),
                        "The first whole-match terminate click only arms local confirmation and sends no abort");
                context.clickScreenButton("screen.riichicraft.game.confirm_abort");sync(connection);
                require(snapshot(context).game()==null,"The separate fresh match terminates only after the actual second confirmation click");
            }
        } finally {if(session.table!=null)DrawDiscardClientScenario.closeFixtures(server,session.table);}
    }

    private static void prepareAndStart(ClientGameTestContext context,TestDedicatedServerContext server,
            TestDedicatedServerConnection connection,Session session) {
        server.runOnServer(s->{var service=LobbyService.get(s);for(int seat=1;seat<4;seat++){
            ServerPlayer player=session.table.fixtures().get(seat-1).player();service.handleAction(player,new LobbyActionPayload(session.table.viewIds().get(seat),
                    service.registry().snapshot(session.table.tableId()).orElseThrow().revision(),LobbyActionPayload.SET_READY,true));}});sync(connection);
        context.clickScreenButton("screen.riichicraft.lobby.prepare");sync(connection);
        context.clickScreenButton("screen.riichicraft.lobby.start");sync(connection);
    }
    private static void finishRound(TestDedicatedServerContext server,TestDedicatedServerConnection connection,Session session) {
        finishRound(server,connection,session,false);
    }
    private static void finishRound(TestDedicatedServerContext server,TestDedicatedServerConnection connection,Session session,boolean preventNagashi) {
        server.runOnServer(s->{var service=LobbyService.get(s);int actions=0;
            while(service.gameView(session.table.tableId(),connection.getServerPlayer().getUUID()).rules().phase()!=GameSnapshot.FINISHED){
                require(++actions<=400,"Each actual round has a finite tile and legal-response budget");
                if(preventNagashi) {
                    GameSnapshot state=service.gameView(session.table.tableId(),connection.getServerPlayer().getUUID());
                    if(state.rules().phase()==GameSnapshot.DRAWN_TURN) {
                        int seat=state.turnSeat();ServerPlayer actor=seat==0?connection.getServerPlayer():session.table.fixtures().get(seat-1).player();
                        GameSnapshot own=service.gameView(session.table.tableId(),actor.getUUID());
                        if(own.seats().get(seat).discards().stream().noneMatch(MatchClientScenario::simpleTile)) {
                            Integer tile=own.rules().legalDiscards().stream().filter(MatchClientScenario::simpleTile).findFirst().orElse(null);
                            if(tile!=null) {
                                service.handleGameAction(actor,new GameActionPayload(session.table.viewIds().get(seat),own.gameId(),own.revision(),GameActionPayload.DISCARD,tile));
                                require(service.gameView(session.table.tableId(),actor.getUUID()).revision()>own.revision(),"South fixture breaks nagashi only by a genuine currently legal simple-tile discard");
                                continue;
                            }
                        }
                    }
                }
                DrawDiscardClientScenario.advance(service,session.table,connection.getServerPlayer());}});
    }
    private static boolean simpleTile(int tile){int type=tile/4;return type<27 && type%9>0 && type%9<8;}
    private static void clickMode(ClientGameTestContext context) {
        double[] position=context.computeOnClient(c->{
            Button button=c.gui.screen().children().stream().filter(child->child instanceof Button).map(child->(Button)child)
                    .filter(widget->widget.active&&widget.visible&&widget.getMessage().getContents() instanceof TranslatableContents contents
                            &&contents.getKey().equals("screen.riichicraft.lobby.mode")).findFirst().orElseThrow();
            var window=c.getWindow();return new double[]{(button.getX()+button.getWidth()/2.0)*window.getScreenWidth()/window.getGuiScaledWidth(),
                    (button.getY()+button.getHeight()/2.0)*window.getScreenHeight()/window.getGuiScaledHeight()};});
        context.getInput().setCursorPos(position[0],position[1]);context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void voteNative(TestDedicatedServerContext server,TestDedicatedServerConnection connection,Session session,int first,int last) {
        server.runOnServer(s->{var service=LobbyService.get(s);var before=service.gameView(session.table.tableId(),connection.getServerPlayer().getUUID());
            for(int seat=first;seat<=last;seat++)service.handleGameAction(session.table.fixtures().get(seat-1).player(),new GameActionPayload(session.table.viewIds().get(seat),
                    before.gameId(),before.revision(),GameActionPayload.NEXT_ROUND,-1,before.match().continueEpoch(),-1,List.of()));});
    }
    private static void assertNext(GameSnapshot paid,GameSnapshot fresh) {
        require(!paid.gameId().equals(fresh.gameId()) && paid.match().matchId().equals(fresh.match().matchId()) && fresh.revision()>paid.revision()
                        && fresh.match().phase()==GameSnapshot.MATCH_PLAYING && fresh.rules().phase()==GameSnapshot.DRAWN_TURN && fresh.remainingTiles()==69,
                "The real fourth continue button installs exactly one fresh round and keeps the match revision monotonic");
        require(fresh.rules().seats().stream().map(GameSnapshot.SeatRules::points).toList().equals(paid.rules().seats().stream().map(GameSnapshot.SeatRules::points).toList())
                        && fresh.rules().deposits()==paid.rules().deposits() && fresh.rules().honba()==paid.rules().honba()+1
                        && fresh.rules().dealerSeat()==fresh.match().roundIndex()%4 && fresh.rules().roundWind()==27+fresh.match().roundIndex()/4
                        && fresh.seats().stream().allMatch(seat->seat.discards().isEmpty()) && fresh.rules().seats().stream().allMatch(seat->seat.melds().isEmpty()&&seat.riichiPhase()==0),
                "Actual successive draws carry points, deposits and honba while resetting all new-round tile and riichi state");
    }
    private static LobbySnapshotPayload snapshot(ClientGameTestContext context){return context.computeOnClient(c->((LobbyScreen)c.gui.screen()).snapshot());}
    private static void shot(ClientGameTestContext context,String name){context.runOnClient(c->c.gui.toastManager().clear());context.waitTick();context.takeScreenshot(name);}
    private static void sync(TestServerConnection connection){connection.waitForServerboundPackets();connection.waitForClientboundPackets();}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
