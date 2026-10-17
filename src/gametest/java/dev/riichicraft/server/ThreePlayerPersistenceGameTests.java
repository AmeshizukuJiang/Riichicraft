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
public final class ThreePlayerPersistenceGameTests {
    @GameTest
    public void failedShutdownSaveCannotReplaceLastSuccessfulRecordAfterRuntimeDetach(GameTestHelper helper) throws IOException {
        boolean[] fail={false}; int[] writes={0};
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.THREE_SINGLE,(path,tables)-> {
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
    public void serviceReloadRetainsPartialNorthRonAndRequiresThreeFreshConfirmations(GameTestHelper helper) throws IOException {
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.THREE_SINGLE)) {
            helper.assertTrue(scenario.service.save(),"Initial world container exists");
            var old=TableArchiveStore.read(scenario.path).getFirst();
            var round=dev.riichicraft.test.ThreePlayerTestWalls.robbery(UUID.randomUUID(),scenario.players.stream().map(ServerPlayer::getUUID).toList());
            var view=round.view(scenario.players.getFirst().getUUID()); int north=view.turnOptions().kitas().iterator().next();
            round.kita(scenario.players.getFirst().getUUID(),view.command(),north);
            var window=round.view(null).opportunity().orElseThrow().id();
            round.respond(scenario.players.get(1).getUUID(),window,dev.riichicraft.rules.DiscardResponseWindow.Simple.RON);
            var saved=new TableArchiveStore.Table(old.tableId(),old.dimension(),old.x(),old.y(),old.z(),old.mode(),old.lobbyRevision(),old.gameRevision(),old.seats(),dev.riichicraft.rules.ThreePlayerArchiveCodec.encodeRound(round));
            TableArchiveStore.write(scenario.path,List.of(saved));
            scenario.service=new LobbyService(helper.getLevel().getServer(),scenario.path);
            scenario.reopenAll(); var held=scenario.coreViews();
            helper.assertTrue(scenario.game(0).paused() && scenario.pending().equals(List.of(2)) && scenario.game(0).rules().phase()==GameSnapshot.NORTH_RESPONSES,"Real encoded partial north response restores with only second ron pending");
            for(int seat=0;seat<3;seat++) {
                scenario.action(seat,GameActionPayload.RESUME,-1,-1);
                helper.assertTrue(scenario.game(0).paused()==(seat<2) && scenario.coreViews().equals(held),"Third fresh recovery vote resumes exact stored window");
            }
            scenario.action(2,GameActionPayload.RON,-1,window.serial());
            var result=scenario.game(0).rules().result();
            helper.assertTrue(result!=null && result.winners().equals(List.of(1,2)) && scenario.game(0).remainingTiles()==54
                && scenario.game(0).rules().seats().stream().allMatch(seat->seat.norths().isEmpty()),"Restored double ron pays once, without establishing kita or supplementary draw");
            scenario.action(0,GameActionPayload.RETURN_LOBBY,-1,-1);
            helper.assertTrue(TableArchiveStore.read(scenario.path).isEmpty(),"Explicit return clears durable result");
        }
        helper.succeed();
    }

    @GameTest
    public void intermissionVotesResetButPaidResultAndFinalResultSurviveServiceReload(GameTestHelper helper) throws IOException {
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.THREE_EAST)) {
            scenario.finishRound();
            GameSnapshot paid=scenario.game(0);
            helper.assertTrue(paid.match().phase()==GameSnapshot.MATCH_ROUND_RESULT,"The first natural east round must be a paid intermission");
            for(int seat=0;seat<2;seat++) scenario.action(seat,GameActionPayload.NEXT_ROUND,-1,paid.match().continueEpoch());
            scenario.reload(); scenario.reopenAll();
            GameSnapshot restored=scenario.game(0);
            helper.assertTrue(!restored.paused() && restored.rules().result().equals(paid.rules().result())
                            && restored.rules().seats().equals(paid.rules().seats()) && restored.match().continueEpoch()>paid.match().continueEpoch()
                            && restored.match().confirmations().equals(List.of(false,false,false)),
                    "Restoration preserves paid accounts and result, clears every continuation vote and invalidates the old epoch");
            scenario.action(2,GameActionPayload.NEXT_ROUND,-1,paid.match().continueEpoch());
            helper.assertTrue(scenario.game(0).match().confirmations().equals(List.of(false,false,false)),
                    "A new view cannot reuse a pre-restart continuation epoch");
            for(int seat=0;seat<3;seat++) scenario.action(seat,GameActionPayload.NEXT_ROUND,-1,restored.match().continueEpoch());
            helper.assertTrue(!scenario.game(0).gameId().equals(paid.gameId()),"Only three fresh intermission confirmations create the next round");
            scenario.action(0,GameActionPayload.ABORT,-1,-1);
        }
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.THREE_SINGLE)) {
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
        try(Scenario scenario=new Scenario(helper,LobbyRegistry.THREE_SINGLE)) {
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
        final UUID[] views=new UUID[3]; LobbyService service; UUID tableId;
        Scenario(GameTestHelper helper,int mode) { this(helper,mode,TableArchiveStore::write); }
        Scenario(GameTestHelper helper,int mode,LobbyService.ArchiveWriter writer) {
            this.helper=helper; pos=helper.absolutePos(new BlockPos(2,2,2));
            path=helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data/riichicraft-tests/"+UUID.randomUUID()+"/tables.dat");
            service=new LobbyService(helper.getLevel().getServer(),path,writer);
            helper.setBlock(new BlockPos(2,1,2),Blocks.STONE); helper.setBlock(new BlockPos(2,2,2),ModBlocks.MAHJONG_TABLE);
            for(int seat=0;seat<3;seat++) {
                var fixture=new ConnectedTestPlayer(helper.getLevel().getServer(),helper.getLevel(),pos,"Save"+seat);
                fixtures.add(fixture); players.add(fixture.player()); views[seat]=service.open(fixture.player(),pos);
                long revision=tableId==null?0:lobby().revision();
                service.handleAction(fixture.player(),new LobbyActionPayload(views[seat],revision,LobbyActionPayload.JOIN,false));
                tableId=service.registry().tableForPlayer(fixture.player().getUUID()).orElseThrow();
            }
            service.handleAction(players.getFirst(),new LobbyActionPayload(views[0],lobby().revision(),LobbyActionPayload.SET_MODE,false,mode));
            start();
            helper.assertTrue(game(0)!=null && !game(0).paused(),"The persistence fixture begins with three connected real server players");
        }
        LobbyRegistry.Snapshot lobby() { return service.registry().snapshot(tableId).orElseThrow(); }
        GameSnapshot game(int seat) { return service.gameView(tableId,players.get(seat).getUUID()); }
        List<CoreView> coreViews() { return players.stream().map(player->CoreView.of(service.gameView(tableId,player.getUUID()))).toList(); }
        List<Integer> pending() { var pending=new ArrayList<Integer>(); for(int seat=0;seat<3;seat++) if(!game(seat).rules().responses().isEmpty()) pending.add(seat); return pending; }
        void action(int seat,int action,int tile,long serial) { var state=game(seat); service.handleGameAction(players.get(seat),new GameActionPayload(views[seat],state.gameId(),state.revision(),action,tile,serial,-1,List.of())); }
        void pass(int seat) { action(seat,GameActionPayload.PASS,-1,game(seat).rules().opportunity().serial()); }
        void start() {
            for(int seat=0;seat<3;seat++) service.handleAction(players.get(seat),new LobbyActionPayload(views[seat],lobby().revision(),LobbyActionPayload.SET_READY,true));
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
        void reopenAll() { for(int seat=0;seat<3;seat++) { views[seat]=service.open(players.get(seat),pos); helper.assertTrue(views[seat]!=null,"Restored participants can authenticate a fresh view at the same table"); } }
        void assertConservation() {
            var seen=new HashSet<Integer>();
            for(int seat=0;seat<3;seat++) for(int tile:game(seat).ownHand()) helper.assertTrue(seen.add(tile),"Private hands contain disjoint physical tiles after restoration");
            var publicView=game(0);
            for(var seat:publicView.seats()) for(int tile:seat.discards()) helper.assertTrue(seen.add(tile),"A discarded physical tile cannot remain concealed");
            helper.assertTrue(seen.size()+publicView.remainingTiles()+14==108,"Held and discarded tiles, live wall and dead wall still total 108");
        }
        @Override public void close() {
            for(var fixture:fixtures) { service.disconnect(fixture.player()); fixture.close(); }
            service.removeTable(helper.getLevel(),pos);
        }
    }
}
