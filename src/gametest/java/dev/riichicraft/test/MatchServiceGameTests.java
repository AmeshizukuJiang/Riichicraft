package dev.riichicraft.test;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.server.LobbyService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Real service ownership and native connections; these fixtures are not four TCP clients. */
public final class MatchServiceGameTests {
    @GameTest
    public void lobbyModeRequiresEastAndChangingItClearsPreparationAtomically(GameTestHelper helper) {
        LobbyRegistry registry=new LobbyRegistry(); UUID table=UUID.randomUUID(); registry.createTable(table);
        List<UUID> players=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        for(int seat=0;seat<4;seat++) {
            registry.join(table,players.get(seat),"Mode"+seat,registry.snapshot(table).orElseThrow().revision());
            registry.setReady(table,players.get(seat),true,registry.snapshot(table).orElseThrow().revision());
        }
        var ready=registry.snapshot(table).orElseThrow();
        helper.assertTrue(ready.mode()==LobbyRegistry.SINGLE && ready.allReady(),"New tables retain single-round mode by default");
        helper.assertTrue(registry.setMode(table,players.get(1),LobbyRegistry.EAST,ready.revision()).status()==LobbyRegistry.Status.NOT_EAST,
                "A non-east participant cannot choose the table's format");
        helper.assertTrue(registry.setMode(table,players.getFirst(),LobbyRegistry.EAST,ready.revision()-1).status()==LobbyRegistry.Status.STALE_REVISION
                        && registry.snapshot(table).orElseThrow().equals(ready),"A stale mode request changes neither readiness nor mode");
        helper.assertTrue(registry.setMode(table,players.getFirst(),LobbyRegistry.SINGLE,ready.revision()).status()==LobbyRegistry.Status.UNCHANGED
                        && registry.snapshot(table).orElseThrow().equals(ready),"Repeating the actual format does not clear preparation or advance revision");
        var changed=registry.setMode(table,players.getFirst(),LobbyRegistry.EAST,ready.revision()).snapshot();
        helper.assertTrue(changed.mode()==LobbyRegistry.EAST && changed.revision()==ready.revision()+1
                        && !changed.allReady() && changed.seats().stream().noneMatch(LobbyRegistry.Seat::ready),
                "A real format change clears all four preparation flags in one revision");
        registry.disconnect(players.getFirst());
        UUID replacement=UUID.randomUUID(); registry.join(table,replacement,"NewEast",registry.snapshot(table).orElseThrow().revision());
        helper.assertTrue(registry.snapshot(table).orElseThrow().mode()==LobbyRegistry.EAST
                        && registry.setMode(table,players.getFirst(),LobbyRegistry.SOUTH,registry.snapshot(table).orElseThrow().revision()).status()==LobbyRegistry.Status.NOT_EAST,
                "Mode belongs to the same physical table while former east loses authority after leaving");
        helper.assertTrue(registry.setMode(table,replacement,LobbyRegistry.SOUTH,registry.snapshot(table).orElseThrow().revision()).snapshot().mode()==LobbyRegistry.SOUTH,
                "The new current initial-east occupant receives format authority");
        helper.succeed();
    }

    @GameTest
    public void matchVotesSurviveClosingButAbsenceRevokesEpochAndFullMatchRetainsRankedResult(GameTestHelper helper) {
        try(Scenario scenario=new Scenario(helper)) {
            scenario.setMode(0,LobbyRegistry.EAST); scenario.start();
            GameSnapshot first=scenario.game(0);
            helper.assertTrue(first.match()!=null && first.match().format()==GameSnapshot.FORMAT_EAST
                            && first.match().phase()==GameSnapshot.MATCH_PLAYING && first.rules().dealerSeat()==0
                            && first.rules().roundWind()==27 && first.rules().honba()==0 && first.rules().deposits()==0,
                    "Prepared east format begins a fresh match at east-one with initial dealer zero and fresh accounts");
            helper.assertTrue(first.rules().seats().stream().allMatch(s->s.points()==25_000),"Every newly started match begins at twenty-five thousand");
            scenario.finishRound(); GameSnapshot result=scenario.game(0);
            helper.assertTrue(result.match().phase()==GameSnapshot.MATCH_ROUND_RESULT && result.rules().result()!=null && !result.paused(),
                    "The first paid round remains as an intermission rather than returning to the lobby");
            var paid=result.rules().result(); UUID oldRound=result.gameId(); long epoch=result.match().continueEpoch();
            scenario.action(0,GameActionPayload.RETURN_LOBBY,-1,-1,result.revision());
            helper.assertTrue(scenario.game(0).equals(result),"Returning to the lobby is refused while an east match still has another round");
            scenario.vote(0,epoch,result.revision());
            helper.assertTrue(scenario.game(0).match().confirmations().equals(List.of(true,false,false,false)),"First continuation vote belongs only to its actual participant");
            scenario.closeAndReopen(0);
            helper.assertTrue(scenario.game(0).match().confirmations().getFirst() && scenario.game(0).rules().result().equals(paid),
                    "Closing and reopening an intermission retains both paid result and its already-cast vote");
            scenario.vote(1,epoch,result.revision());
            helper.assertTrue(scenario.game(0).match().confirmations().equals(List.of(true,true,false,false)),
                    "Another vote accepts the original outer revision through the same stable round and epoch");
            GameSnapshot voted=scenario.game(0);
            scenario.vote(1,epoch,voted.revision());
            helper.assertTrue(scenario.game(0).equals(voted),"Repeated continuation votes are inert");
            scenario.service.disconnect(scenario.players.get(2)); scenario.service.tick();
            GameSnapshot missing=scenario.game(0);
            helper.assertTrue(!missing.paused() && missing.match().continueEpoch()>epoch
                            && missing.match().confirmations().stream().noneMatch(Boolean::booleanValue)
                            && missing.rules().result().equals(paid),"Intermission absence revokes all old votes and their epoch without resuming or undoing the paid round");
            scenario.reopen(2); scenario.service.tick();
            long currentEpoch=scenario.game(0).match().continueEpoch(); GameSnapshot returned=scenario.game(0);
            scenario.vote(0,epoch,returned.revision());
            helper.assertTrue(scenario.game(0).equals(returned),"A pre-absence epoch cannot cast a delayed vote after everyone returns");
            scenario.vote(0,currentEpoch,returned.revision()+1);
            helper.assertTrue(scenario.game(0).equals(returned),"A future outer revision cannot cast even a current-epoch vote");
            ServerPlayer outsider=(ServerPlayer)helper.makeMockServerPlayer(GameType.CREATIVE);
            outsider.setPos(scenario.pos.getX()+0.5,scenario.pos.getY(),scenario.pos.getZ()+1.5);
            UUID outsiderView=scenario.service.open(outsider,scenario.pos);
            scenario.service.handleGameAction(outsider,new GameActionPayload(outsiderView,oldRound,returned.revision(),
                    GameActionPayload.NEXT_ROUND,-1,currentEpoch,-1,List.of()));
            helper.assertTrue(scenario.game(0).equals(returned),"An unseated observer cannot participate in the continuation quorum");
            for(int seat=0;seat<4;seat++) {
                scenario.vote(seat,currentEpoch,returned.revision());
                if(seat<3) helper.assertTrue(scenario.game(0).gameId().equals(oldRound) && scenario.game(0).rules().result().equals(paid),
                        "Fewer than four votes keep exactly the already-paid old round");
            }
            GameSnapshot next=scenario.game(0);
            assertFreshRound(helper,result,next);
            scenario.service.handleGameAction(scenario.players.getFirst(),new GameActionPayload(scenario.views[0],oldRound,next.revision(),
                    GameActionPayload.NEXT_ROUND,-1,currentEpoch,-1,List.of()));
            helper.assertTrue(scenario.game(0).equals(next),"An old round's stable ticket cannot deal an extra round after the fourth vote");
            int rounds=1;
            while(scenario.game(0).match().phase()!=GameSnapshot.MATCH_RESULT) {
                helper.assertTrue(++rounds<=32,"The all-pass east match must finish within a bounded number of real hands");
                scenario.finishRound(); GameSnapshot end=scenario.game(0);
                if(end.match().phase()==GameSnapshot.MATCH_RESULT) break;
                long stable=end.revision(), ticket=end.match().continueEpoch();
                for(int seat=0;seat<4;seat++) scenario.vote(seat,ticket,stable);
            }
            GameSnapshot finished=scenario.game(0); var finalResult=finished.match().result();
            helper.assertTrue(finalResult!=null && finalResult.standings().size()==4
                            && finalResult.standings().stream().mapToInt(GameSnapshot.Standing::points).sum()==100_000
                            && finalResult.standings().stream().mapToInt(GameSnapshot.Standing::totalTenths).sum()==0,
                    "A genuine completed match exposes four final ranks with conserved table points and zero-sum final scores");
            helper.assertTrue(new HashSet<>(finalResult.standings().stream().map(GameSnapshot.Standing::seat).toList()).size()==4
                            && new HashSet<>(finalResult.standings().stream().map(GameSnapshot.Standing::rank).toList()).size()==4,
                    "Every participant appears exactly once in the actual final ranking");
            scenario.service.disconnect(scenario.players.getFirst()); scenario.service.tick(); scenario.reopen(0);
            helper.assertTrue(!scenario.game(0).paused() && scenario.game(0).match().result().equals(finalResult),
                    "A terminal match disconnect and reopen cannot retract points, rankings or demand recovery votes");
            scenario.action(1,GameActionPayload.RETURN_LOBBY,-1,-1,scenario.game(1).revision());
            helper.assertTrue(scenario.game(0)==null && scenario.lobby().mode()==LobbyRegistry.EAST
                            && scenario.lobby().seats().stream().noneMatch(LobbyRegistry.Seat::ready),
                    "After final rankings any actual participant can return the whole table with its chosen format retained and preparation cleared");
            scenario.setMode(0,LobbyRegistry.SOUTH); scenario.start();
            helper.assertTrue(scenario.game(0).match().format()==GameSnapshot.FORMAT_SOUTH
                            && !scenario.game(0).match().matchId().equals(first.match().matchId())
                            && scenario.game(0).rules().seats().stream().allMatch(s->s.points()==25_000),
                    "The same table can select south for a new independent match with fresh accounts");
            boolean southSeen=false;int southHands=0;
            while(scenario.game(0).match().phase()!=GameSnapshot.MATCH_RESULT) {
                helper.assertTrue(++southHands<=48,"A complete all-pass south match has a bounded real-hand budget");
                GameSnapshot started=scenario.game(0);
                if(started.match().roundIndex()>=4 && started.match().roundIndex()<=7) {
                    southSeen=true;
                    helper.assertTrue(started.rules().roundWind()==28 && started.rules().dealerSeat()==started.match().roundIndex()%4,
                            "The actual south match reaches a real south-wind hand with the corresponding current dealer");
                }
                scenario.finishRound(true);GameSnapshot paidSouth=scenario.game(0);
                if(paidSouth.match().phase()==GameSnapshot.MATCH_RESULT) break;
                for(int seat=0;seat<4;seat++)scenario.vote(seat,paidSouth.match().continueEpoch(),paidSouth.revision());
                assertFreshRound(helper,paidSouth,scenario.game(0));
            }
            var southResult=scenario.game(0).match().result();
            helper.assertTrue(southSeen && southResult!=null && southResult.standings().size()==4
                            && southResult.standings().stream().mapToInt(GameSnapshot.Standing::points).sum()==100_000
                            && southResult.standings().stream().mapToInt(GameSnapshot.Standing::totalTenths).sum()==0,
                    "A genuinely played south-wind match ends with conserved final table points and zero-sum ranked scores");
            scenario.action(0,GameActionPayload.RETURN_LOBBY,-1,-1,scenario.game(0).revision());scenario.start();
            helper.assertTrue(scenario.game(0).match().format()==GameSnapshot.FORMAT_SOUTH
                            && scenario.game(0).rules().seats().stream().allMatch(s->s.points()==25_000),
                    "Returning after south final standings permits a fresh independent short fixture");
            scenario.action(0,GameActionPayload.ABORT,-1,-1,scenario.game(0).revision());
            helper.assertTrue(scenario.game(0)==null && scenario.lobby().seats().stream().noneMatch(LobbyRegistry.Seat::ready),
                    "An authenticated participant's abort terminates the entire fresh match rather than one round");
            scenario.service.disconnect(outsider);
        }
        helper.succeed();
    }

    static void assertFreshRound(GameTestHelper helper,GameSnapshot previous,GameSnapshot next) {
        helper.assertTrue(!next.gameId().equals(previous.gameId()) && next.match().matchId().equals(previous.match().matchId())
                        && next.revision()>previous.revision() && next.match().phase()==GameSnapshot.MATCH_PLAYING
                        && next.rules().phase()==GameSnapshot.DRAWN_TURN && next.remainingTiles()==69,
                "Only the fourth stable vote atomically installs one fresh round while the outer match revision continues increasing");
        helper.assertTrue(next.rules().seats().stream().map(GameSnapshot.SeatRules::points).toList()
                        .equals(previous.rules().seats().stream().map(GameSnapshot.SeatRules::points).toList())
                        && next.rules().deposits()==previous.rules().deposits() && next.rules().honba()==previous.rules().honba()+1,
                "A real all-pass draw carries exactly its scores, deposits and incremented honba into the fresh round");
        helper.assertTrue(next.rules().dealerSeat()==next.match().roundIndex()%4 && next.rules().roundWind()==27+next.match().roundIndex()/4
                        && next.rules().seats().stream().allMatch(s->s.melds().isEmpty() && s.river().isEmpty() && s.riichiPhase()==0)
                        && next.seats().stream().allMatch(s->s.discards().isEmpty()) && next.match().confirmations().stream().noneMatch(Boolean::booleanValue),
                "Dealer and round wind match the actual match position, while melds, rivers, riichi and continuation votes reset");
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper helper; final LobbyService service; final BlockPos pos;
        final List<ConnectedTestPlayer> fixtures=new ArrayList<>();final List<ServerPlayer> players=new ArrayList<>();final UUID[] views=new UUID[4]; UUID table;
        Scenario(GameTestHelper helper) {
            this.helper=helper;service=LobbyService.get(helper.getLevel().getServer());BlockPos relative=new BlockPos(3,2,3);
            helper.setBlock(relative.below(),Blocks.STONE);helper.setBlock(relative,ModBlocks.MAHJONG_TABLE);pos=helper.absolutePos(relative);
            try {
                for(int seat=0;seat<4;seat++) {
                    var fixture=new ConnectedTestPlayer(helper.getLevel().getServer(),helper.getLevel(),pos,"Match"+seat);
                    fixtures.add(fixture);players.add(fixture.player());reopen(seat);
                    service.handleAction(players.get(seat),new LobbyActionPayload(views[seat],table==null?0:lobby().revision(),LobbyActionPayload.JOIN,false));
                    if(table==null)table=service.registry().tableForPlayer(players.get(seat).getUUID()).orElseThrow();
                }
            } catch(RuntimeException|Error failure) {close();throw failure;}
        }
        GameSnapshot game(int seat){return service.gameView(table,players.get(seat).getUUID());}
        LobbyRegistry.Snapshot lobby(){return service.registry().snapshot(table).orElseThrow();}
        void reopen(int seat){views[seat]=service.open(players.get(seat),pos);helper.assertTrue(views[seat]!=null,"Present native player opens its own bound view");}
        void closeAndReopen(int seat){service.handleAction(players.get(seat),new LobbyActionPayload(views[seat],lobby().revision(),LobbyActionPayload.CLOSE,false));reopen(seat);}
        void setMode(int seat,int mode){service.handleAction(players.get(seat),new LobbyActionPayload(views[seat],lobby().revision(),LobbyActionPayload.SET_MODE,false,mode));}
        void start(){for(int seat=0;seat<4;seat++)service.handleAction(players.get(seat),new LobbyActionPayload(views[seat],lobby().revision(),LobbyActionPayload.SET_READY,true));service.handleAction(players.getFirst(),new LobbyActionPayload(views[0],lobby().revision(),LobbyActionPayload.START,false));}
        void action(int seat,int action,int tile,long serial,long revision){service.handleGameAction(players.get(seat),new GameActionPayload(views[seat],game(seat).gameId(),revision,action,tile,serial,-1,List.of()));}
        void vote(int seat,long epoch,long revision){action(seat,GameActionPayload.NEXT_ROUND,-1,epoch,revision);}
        void finishRound(){finishRound(false);}
        void finishRound(boolean preventNagashi){
            int actions=0;
            while(game(0).rules().phase()!=GameSnapshot.FINISHED){
                helper.assertTrue(++actions<=400,"Every real all-pass hand has a finite tile and response budget");GameSnapshot before=game(0);
                if(before.rules().opportunity()!=null){
                    boolean submitted=false;for(int seat=0;seat<4;seat++)if(!game(seat).rules().responses().isEmpty()){
                        action(seat,GameActionPayload.PASS,-1,before.rules().opportunity().serial(),game(seat).revision());submitted=true;break;}
                    helper.assertTrue(submitted,"A genuine unresolved window has a pending participant");
                }else{
                    int seat=before.turnSeat();var own=game(seat);
                    int tile=own.rules().legalDiscards().contains(own.drawnTile())?own.drawnTile():own.rules().legalDiscards().getFirst();
                    if(preventNagashi && own.seats().get(seat).discards().stream().noneMatch(MatchServiceGameTests::simpleTile))
                        tile=own.rules().legalDiscards().stream().filter(MatchServiceGameTests::simpleTile).findFirst().orElse(tile);
                    action(seat,GameActionPayload.DISCARD,tile,-1,own.revision());
                }
                helper.assertTrue(game(0).revision()>before.revision(),"Each offered native intention advances once");
            }
        }
        @Override public void close(){for(var fixture:fixtures){service.disconnect(fixture.player());fixture.close();}service.removeTable(helper.getLevel(),pos);}
    }
    private static boolean simpleTile(int tile){int type=tile/4;return type<27 && type%9>0 && type%9<8;}
}
