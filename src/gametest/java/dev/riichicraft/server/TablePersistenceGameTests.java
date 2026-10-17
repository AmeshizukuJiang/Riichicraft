package dev.riichicraft.server;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.persistence.TableArchiveStore;
import dev.riichicraft.test.ConnectedTestPlayer;
import dev.riichicraft.test.PersistenceTestActions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Real Minecraft players and disk IO across independent service owners; TCP covers full server restart. */
public final class TablePersistenceGameTests {
    @GameTest
    public void failedShutdownSaveCannotReplaceLastSuccessfulRecordAfterRuntimeDetach(GameTestHelper helper) throws IOException {
        boolean[] fail={false}; int[] writes={0};
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.SINGLE,(path,tables)-> {
            writes[0]++;
            if(fail[0]) throw new IOException("Simulated write failure with an intact readable prior checkpoint");
            TableArchiveStore.write(path,tables);
        })) {
            helper.assertTrue(scenario.service.save(),"A valid durable checkpoint must exist before the simulated IO failure");
            byte[] checkpoint=Files.readAllBytes(scenario.path);
            scenario.action(0,GameActionPayload.DISCARD,scenario.game(0).drawnTile(),-1);
            fail[0]=true; scenario.service.stop(); fail[0]=false;
            helper.assertTrue(writes[0]==2 && TableArchiveStore.read(scenario.path).size()==1,
                    "Shutdown attempts the second write while its previous successful checkpoint remains readable and valid");
            helper.assertTrue(!scenario.service.save() && Arrays.equals(Files.readAllBytes(scenario.path),checkpoint)
                            && TableArchiveStore.read(scenario.path).size()==1 && writes[0]==2,
                    "A later native save after failed shutdown and runtime detach cannot overwrite the prior successful record with an empty archive");
        }
        helper.succeed();
    }

    @GameTest
    public void serviceReloadRetainsPartialResponsesAndRequiresFourFreshConfirmations(GameTestHelper helper) throws IOException {
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.SINGLE)) {
            boolean partial=false;
            for(int deal=0;deal<4 && !partial;deal++) {
                for(int command=0;command<400 && !partial;command++) {
                    GameSnapshot before=scenario.game(0);
                    if(before.rules().phase()==GameSnapshot.FINISHED) break;
                    List<Integer> pending=scenario.pending();
                    if(pending.size()>=2) {
                        scenario.pass(pending.getFirst());
                        partial=true;
                    } else scenario.step();
                }
                if(!partial && deal<3) scenario.newDeal();
            }
            helper.assertTrue(partial,"A real bounded trajectory must reach and partially answer a multi-player response window");
            List<CoreView> held=scenario.coreViews();
            List<Integer> pending=scenario.pending();
            long serial=scenario.game(0).rules().opportunity().serial();
            UUID[] oldViews=scenario.views.clone();
            scenario.reload();
            helper.assertTrue(scenario.coreViews().equals(held) && scenario.pending().equals(pending)
                            && scenario.game(0).paused(),
                    "Disk restoration retains each hand, wall count, response window and already collected response");
            helper.assertTrue(scenario.players.stream().allMatch(player->scenario.service.registry().tableForPlayer(player.getUUID()).orElseThrow().equals(scenario.tableId)),
                    "Every archived UUID owns its original seat before any player reopens the table");
            int responder=pending.getFirst();
            GameSnapshot paused=scenario.game(responder);
            scenario.service.handleGameAction(scenario.players.get(responder),new GameActionPayload(oldViews[responder],paused.gameId(),paused.revision(),
                    GameActionPayload.PASS,-1,serial,-1,List.of()));
            helper.assertTrue(scenario.coreViews().equals(held),"Pre-restart view tokens cannot submit a stored response");
            scenario.reopenAll();
            scenario.pass(responder);
            helper.assertTrue(scenario.coreViews().equals(held),"Even a newly authenticated response is refused before four recovery confirmations");
            for(int seat=0;seat<4;seat++) {
                scenario.action(seat,GameActionPayload.RESUME,-1,-1);
                helper.assertTrue(scenario.game(0).paused()==(seat<3) && scenario.coreViews().equals(held),
                        "Exactly four fresh votes resume the unchanged partial response window");
            }
            for(int seat:pending) scenario.pass(seat);
            helper.assertTrue(scenario.game(0).rules().phase()==GameSnapshot.DRAWN_TURN
                            && scenario.game(0).rules().opportunity()==null,
                    "The stored window resolves once through its original remaining legal responses");
            scenario.assertConservation();
            scenario.action(0,GameActionPayload.ABORT,-1,-1);
            helper.assertTrue(TableArchiveStore.read(scenario.path).isEmpty(),"Explicit abort durably removes the active record");
        }
        helper.succeed();
    }

    @GameTest
    public void intermissionVotesResetButPaidResultAndFinalResultSurviveServiceReload(GameTestHelper helper) throws IOException {
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.EAST)) {
            scenario.finishRound();
            GameSnapshot paid=scenario.game(0);
            helper.assertTrue(paid.match().phase()==GameSnapshot.MATCH_ROUND_RESULT,"The first natural east round must be a paid intermission");
            for(int seat=0;seat<3;seat++) scenario.action(seat,GameActionPayload.NEXT_ROUND,-1,paid.match().continueEpoch());
            scenario.reload(); scenario.reopenAll();
            GameSnapshot restored=scenario.game(0);
            helper.assertTrue(!restored.paused() && restored.rules().result().equals(paid.rules().result())
                            && restored.rules().seats().equals(paid.rules().seats()) && restored.match().continueEpoch()>paid.match().continueEpoch()
                            && restored.match().confirmations().equals(List.of(false,false,false,false)),
                    "Restoration preserves paid accounts and result, clears every continuation vote and invalidates the old epoch");
            scenario.action(3,GameActionPayload.NEXT_ROUND,-1,paid.match().continueEpoch());
            helper.assertTrue(scenario.game(0).match().confirmations().equals(List.of(false,false,false,false)),
                    "A new view cannot reuse a pre-restart continuation epoch");
            for(int seat=0;seat<4;seat++) scenario.action(seat,GameActionPayload.NEXT_ROUND,-1,restored.match().continueEpoch());
            helper.assertTrue(!scenario.game(0).gameId().equals(paid.gameId()),"Only four fresh intermission confirmations create the next round");
            scenario.action(0,GameActionPayload.ABORT,-1,-1);
        }
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.SINGLE)) {
            scenario.finishRound(); GameSnapshot paid=scenario.game(0);
            scenario.reload(); scenario.reopenAll();
            GameSnapshot restored=scenario.game(0);
            helper.assertTrue(!restored.paused() && restored.rules().result().equals(paid.rules().result())
                            && restored.rules().seats().equals(paid.rules().seats()),
                    "A final paid result restores without entering active-game recovery or paying a second time");
            scenario.action(0,GameActionPayload.RETURN_LOBBY,-1,-1);
            helper.assertTrue(TableArchiveStore.read(scenario.path).isEmpty(),"Returning to the lobby durably removes the retained result");
            scenario.service.stop(); scenario.service=new LobbyService(helper.getLevel().getServer(),scenario.path);
            helper.assertTrue(scenario.game(0)==null && scenario.service.registry().tableForPlayer(scenario.players.getFirst().getUUID()).isEmpty(),
                    "An explicitly cleared result cannot revive after another disk restoration; idle lobby seats are transient");
        }
        helper.succeed();
    }

    @GameTest
    public void invalidCoreReservesItsSeatsWithoutBlockingOtherTablesAndInvalidFileNeverOverwrites(GameTestHelper helper) throws IOException {
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.SINGLE)) {
            helper.assertTrue(scenario.service.save(),"The initial valid archive must reach disk");
            var original=TableArchiveStore.read(scenario.path).getFirst();
            var invalid=new TableArchiveStore.Table(original.tableId(),original.dimension(),original.x(),original.y(),original.z(),
                    original.mode(),original.lobbyRevision(),original.gameRevision(),original.seats(),new byte[]{1,2,3});
            TableArchiveStore.write(scenario.path,List.of(invalid));
            scenario.service=new LobbyService(helper.getLevel().getServer(),scenario.path);
            helper.assertTrue(scenario.service.open(scenario.players.getFirst(),scenario.pos)==null,
                    "An invalid core record cannot be opened as a fresh lobby or game");
            for(ServerPlayer player:scenario.players) scenario.service.disconnect(player);
            scenario.service.removeTable(helper.getLevel(),scenario.pos);
            helper.assertTrue(scenario.players.stream().allMatch(player->scenario.service.registry().tableForPlayer(player.getUUID()).orElseThrow().equals(scenario.tableId)),
                    "Disconnecting or breaking an invalid table never silently releases its reserved UUIDs");
            BlockPos other=scenario.pos.offset(2,0,2); helper.getLevel().setBlockAndUpdate(other,ModBlocks.MAHJONG_TABLE.defaultBlockState());
            UUID otherView=scenario.service.open(scenario.players.getFirst(),other);
            helper.assertTrue(otherView!=null,"One invalid core does not globally block another physical table");
            scenario.service.handleAction(scenario.players.getFirst(),new LobbyActionPayload(otherView,0,LobbyActionPayload.JOIN,false));
            helper.assertTrue(scenario.service.registry().tableForPlayer(scenario.players.getFirst().getUUID()).orElseThrow().equals(scenario.tableId),
                    "An invalid record's UUID cannot obtain a second seat at a healthy table");
            helper.assertTrue(scenario.service.save() && TableArchiveStore.read(scenario.path).equals(List.of(invalid)),
                    "Healthy persistence preserves an invalid core's exact raw record instead of replacing it");
            byte[] damaged={7,8,9}; Files.write(scenario.path,damaged);
            LobbyService blocked=new LobbyService(helper.getLevel().getServer(),scenario.path);
            helper.assertTrue(blocked.open(scenario.players.getFirst(),other)==null && !blocked.save()
                            && Arrays.equals(Files.readAllBytes(scenario.path),damaged),
                    "An invalid outer archive blocks all tables and its original bytes survive attempted saving");
        }
        helper.succeed();
    }

    private record CoreView(UUID round,int actor,int remaining,List<Integer> hand,int draw,GameSnapshot.RuleState rules) {
        static CoreView of(GameSnapshot snapshot) { return new CoreView(snapshot.gameId(),snapshot.turnSeat(),snapshot.remainingTiles(),snapshot.ownHand(),snapshot.drawnTile(),snapshot.rules()); }
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper helper; final Path path; final BlockPos pos;
        final List<ConnectedTestPlayer> fixtures=new ArrayList<>(); final List<ServerPlayer> players=new ArrayList<>();
        final UUID[] views=new UUID[4]; LobbyService service; UUID tableId;
        Scenario(GameTestHelper helper,int mode) { this(helper,mode,TableArchiveStore::write); }
        Scenario(GameTestHelper helper,int mode,LobbyService.ArchiveWriter writer) {
            this.helper=helper; pos=helper.absolutePos(new BlockPos(2,2,2));
            path=helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data/riichicraft-tests/"+UUID.randomUUID()+"/tables.dat");
            service=new LobbyService(helper.getLevel().getServer(),path,writer);
            helper.setBlock(new BlockPos(2,1,2),Blocks.STONE); helper.setBlock(new BlockPos(2,2,2),ModBlocks.MAHJONG_TABLE);
            for(int seat=0;seat<4;seat++) {
                var fixture=new ConnectedTestPlayer(helper.getLevel().getServer(),helper.getLevel(),pos,"Save"+seat);
                fixtures.add(fixture); players.add(fixture.player()); views[seat]=service.open(fixture.player(),pos);
                long revision=tableId==null?0:lobby().revision();
                service.handleAction(fixture.player(),new LobbyActionPayload(views[seat],revision,LobbyActionPayload.JOIN,false));
                tableId=service.registry().tableForPlayer(fixture.player().getUUID()).orElseThrow();
            }
            if(mode!=LobbyRegistry.SINGLE) service.handleAction(players.getFirst(),new LobbyActionPayload(views[0],lobby().revision(),LobbyActionPayload.SET_MODE,false,mode));
            start();
            helper.assertTrue(game(0)!=null && !game(0).paused(),"The persistence fixture begins with four connected real server players");
        }
        LobbyRegistry.Snapshot lobby() { return service.registry().snapshot(tableId).orElseThrow(); }
        GameSnapshot game(int seat) { return service.gameView(tableId,players.get(seat).getUUID()); }
        List<CoreView> coreViews() { return players.stream().map(player->CoreView.of(service.gameView(tableId,player.getUUID()))).toList(); }
        List<Integer> pending() { var pending=new ArrayList<Integer>(); for(int seat=0;seat<4;seat++) if(!game(seat).rules().responses().isEmpty()) pending.add(seat); return pending; }
        void action(int seat,int action,int tile,long serial) { var state=game(seat); service.handleGameAction(players.get(seat),new GameActionPayload(views[seat],state.gameId(),state.revision(),action,tile,serial,-1,List.of())); }
        void pass(int seat) { action(seat,GameActionPayload.PASS,-1,game(seat).rules().opportunity().serial()); }
        void start() {
            for(int seat=0;seat<4;seat++) service.handleAction(players.get(seat),new LobbyActionPayload(views[seat],lobby().revision(),LobbyActionPayload.SET_READY,true));
            service.handleAction(players.getFirst(),new LobbyActionPayload(views[0],lobby().revision(),LobbyActionPayload.START,false));
        }
        void newDeal() {
            helper.assertTrue(game(0).rules().phase()==GameSnapshot.FINISHED,"An unsuccessful search completes its real round before the next legal deal");
            action(0,GameActionPayload.RETURN_LOBBY,-1,-1); start();
        }
        void step() {
            var pending=pending(); if(!pending.isEmpty()) { for(int seat:pending) pass(seat); return; }
            var state=game(0); int actor=state.turnSeat(); var own=game(actor);
            int tile=PersistenceTestActions.multiResponseDiscard(players.stream().map(player->service.gameView(tableId,player.getUUID())).toList());
            action(actor,GameActionPayload.DISCARD,tile,-1);
        }
        void finishRound() { for(int command=0;command<400 && game(0).rules().phase()!=GameSnapshot.FINISHED;command++) step(); helper.assertTrue(game(0).rules().phase()==GameSnapshot.FINISHED,"A bounded all-pass trajectory reaches a real paid result"); }
        void reload() { helper.assertTrue(service.save(),"A successful durable checkpoint is required before reload"); service.stop(); service=new LobbyService(helper.getLevel().getServer(),path); }
        void reopenAll() { for(int seat=0;seat<4;seat++) { views[seat]=service.open(players.get(seat),pos); helper.assertTrue(views[seat]!=null,"Restored participants can authenticate a fresh view at the same table"); } }
        void assertConservation() {
            var seen=new HashSet<Integer>();
            for(int seat=0;seat<4;seat++) for(int tile:game(seat).ownHand()) helper.assertTrue(seen.add(tile),"Private hands contain disjoint physical tiles after restoration");
            var publicView=game(0);
            for(var seat:publicView.seats()) for(int tile:seat.discards()) helper.assertTrue(seen.add(tile),"A discarded physical tile cannot remain concealed");
            helper.assertTrue(seen.size()+publicView.remainingTiles()+14==136,"Held and discarded tiles, live wall and dead wall still total 136");
        }
        @Override public void close() {
            for(var fixture:fixtures) { service.disconnect(fixture.player()); fixture.close(); }
            service.removeTable(helper.getLevel(),pos);
        }
    }
}
