package dev.riichicraft.server;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.network.*;
import dev.riichicraft.persistence.TableArchiveStore;
import dev.riichicraft.rules.*;
import dev.riichicraft.table.TableTileLayout;
import dev.riichicraft.test.ConnectedTestPlayer;
import dev.riichicraft.test.FurnitureTestLayout;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.storage.LevelResource;

public final class PlaytestFixGameTests {
    @GameTest public void coverDistributionHasExactQuarterSelectionAndDecreasingDuration(GameTestHelper h) {
        int selected=0;int[] histogram=new int[41];
        for(int i=0;i<10000;i++) {
            if(ResponseDelay.sample((i+.5)/10000,.5)>0) selected++;
            int tick=ResponseDelay.sample(0,(i+.5)/10000);histogram[tick]++;
            h.assertTrue(tick>=4 && tick<=40,"Cover lasts 0.2 through 2.0 seconds at 20 ticks/second");
        }
        h.assertTrue(selected==2500 && histogram[4]>histogram[40]*9,"25% selection and short durations roughly ten times as likely as long durations");
        for(int tick=5;tick<=40;tick++) h.assertTrue(histogram[tick]<=histogram[tick-1]+1,"Duration density decreases rather than being uniform");
        h.succeed();
    }

    @GameTest public void allFormatsHoldOnlyUnopposedDiscardsAndPreserveDurableCore(GameTestHelper h) {
        for(int mode=0;mode<6;mode++) try(var s=new Scenario(h,mode)) {
            s.service.responseDelaySource=()->4;
            boolean held=false;
            for(int step=0;step<200 && !held;step++) {
                var view=s.game(0);
                if(view.rules().phase()==GameSnapshot.FINISHED) throw new AssertionError("No unopposed discard found in a full real round");
                if(view.rules().opportunity()!=null) { s.passAll();continue; }
                int actor=view.turnSeat();var own=s.game(actor);
                s.action(actor,GameActionPayload.DISCARD,own.rules().legalDiscards().getFirst(),-1);
                held=s.game(0).rules().opportunity()!=null && s.players.stream().allMatch(p->s.service.gameView(s.id,p.player().getUUID()).rules().responses().isEmpty());
                if(!held && s.game(0).rules().opportunity()!=null) {
                    long revision=s.game(0).revision();s.passAll();
                    h.assertTrue(s.game(0).revision()>revision,"Real offered responses remain immediately actionable despite a positive cover sampler");
                }
            }
            h.assertTrue(held,"Actual no-option discard receives the selected cover");
            var before=s.game(0);
            var observer=s.service.gameView(s.id,null);
            h.assertTrue(observer.ownHand().isEmpty() && observer.drawnTile()==-1 && observer.rules().responses().isEmpty(),
                    "Null-identity public observers receive the same bounded wait without a private hand");
            h.assertTrue(s.service.tableDisplay(s.id).liveTiles()==before.remainingTiles(),"Large-table public projection remains valid throughout the cover");
            h.assertTrue(s.service.save(),"Latest accepted action is durable even while its presentation is held");
            try {
                var copy=s.path.resolveSibling("cover-reopen.dat");java.nio.file.Files.copy(s.path,copy);
                var restored=new LobbyService(h.getLevel().getServer(),copy);
                try {
                    var next=restored.gameView(s.id,s.players.get((before.turnSeat()+1)%s.players.size()).player().getUUID());
                    h.assertTrue(next.paused() && next.rules().phase()==GameSnapshot.DRAWN_TURN && next.drawnTile()>=0
                            && next.remainingTiles()==before.remainingTiles()-1,"Reload retains the accepted core/draw, pauses for original players and does not replay a cosmetic wait");
                } finally {restored.stop();}
            } catch(java.io.IOException error) {throw new AssertionError(error);}
            int concealed=s.players.stream().mapToInt(p->s.service.gameView(s.id,p.player().getUUID()).ownHand().size()).sum();
            int river=before.seats().stream().mapToInt(seat->seat.discards().size()).sum();
            h.assertTrue(concealed+river+before.remainingTiles()+14==(mode>=3?108:136),"Held hand/wall/river counts conserve the full physical set");
            for(int seat=0;seat<s.players.size();seat++) {
                var view=s.game(seat);var scene=s.service.sceneView(s.views.get(seat),s.id,s.players.get(seat).player().getUUID());
                h.assertTrue(view.drawnTile()==-1 && view.rules().legalDiscards().isEmpty() && view.rules().responses().isEmpty()
                        && scene.hands().get(seat).equals(view.ownHand()) && scene.drawnTiles().get(seat)==-1,
                        "No future draw, button or opponent option leaks through either private channel");
            }
            s.action(0,GameActionPayload.DISCARD,before.ownHand().getFirst(),-1);
            h.assertTrue(s.game(0).equals(before),"Actions submitted during the held display are rejected atomically");
            for(int tick=0;tick<4;tick++) s.service.tick();
            h.assertTrue(s.game(0).rules().opportunity()!=null,"Selected cover is retained through the requested interval");
            s.service.tick();
            h.assertTrue(s.game(0).rules().phase()==GameSnapshot.DRAWN_TURN && s.game(0).remainingTiles()==before.remainingTiles()-1,
                    "Expiry exposes exactly one already accepted draw, with no duplicate turn");
            h.assertTrue(s.service.save(),"Latest accepted core remains saveable after cosmetic timing");
        }
        h.succeed();
    }

    @GameTest public void realRiichiWinsRevealExactUraEvenWhenUraCountIsZero(GameTestHelper h) throws Exception {
        for(int count:new int[]{3,4}) for(boolean riichi:new boolean[]{false,true}) {
            var ids=IntStream.range(0,count).mapToObj(i->UUID.randomUUID()).toList();
            var core=win(count,ids,riichi);var pos=h.absolutePos(new BlockPos(2,2,2));
            FurnitureTestLayout.body(h.getLevel(),pos,net.minecraft.core.Direction.NORTH);
            FurnitureTestLayout.chairs(h.getLevel(),pos,count);
            var path=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data/riichicraft-tests/ura-"+UUID.randomUUID()+"/tables.dat");
            UUID table=UUID.randomUUID();
            var roster=IntStream.range(0,count).mapToObj(i->new TableArchiveStore.Seat(ids.get(i),"Ura"+i)).toList();
            TableArchiveStore.write(path,List.of(new TableArchiveStore.Table(table,"minecraft:overworld",pos.getX(),pos.getY(),pos.getZ(),count==3?3:0,0,0,roster,core)));
            var service=new LobbyService(h.getLevel().getServer(),path);
            try {
                var view=service.gameView(table,null);var result=view.rules().result();
                h.assertTrue(result!=null && result.kind()==1 && result.values().getFirst().ura()==0,"Complete ordered wall and legal actions produce a genuine zero-ura tsumo");
                h.assertTrue(result.uraIndicators().equals(riichi?List.of(124):List.of()),"Only an established riichi winner reveals the actual hidden indicator, independently of bonus count");
                var publicView=service.tableDisplay(table);
                h.assertTrue(publicView.uraIndicators().equals(result.uraIndicators()),"Result and public wall use the same authorized indicator list");
                var tiles=TableTileLayout.tiles(publicView);
                h.assertTrue(tiles.stream().filter(t->t.face()==124 && t.group()==TableTileLayout.Group.INDICATOR).count()==(riichi?1:0),"Ura's own physical wall tile is revealed once, with no duplicated loose tile");
                var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
                try { GameSnapshot.CODEC.encode(b,view);h.assertTrue(GameSnapshot.CODEC.decode(b).equals(view) && b.readableBytes()==0,"Revealed zero-bonus indicator round-trips through the actual result codec"); } finally { b.release(); }
            } finally { service.stop(); }
        }
        h.succeed();
    }

    /** A complete ordered wall and public choices, never injected winning flags or private mutable hands. */
    private static byte[] win(int count,List<UUID> ids,boolean riichi) {
        int[] order=new int[count==3?108:136];Arrays.fill(order,-1);boolean[] used=new boolean[136];
        class Wall {
            int put(int position,int type) { int tile=type*4;while(used[tile]) tile++;if(tile/4!=type) throw new AssertionError("fifth fixture tile");order[position]=tile;used[tile]=true;return tile; }
        }
        var wall=new Wall();wall.put(count==3?99:127,31);
        int[] hand={9,10,11,12,13,14,15,16,17,18,19,20,27};
        for(int i=0;i<13;i++) wall.put(i*count,hand[i]);
        int discard=wall.put(count*13,28);
        for(int i=1;i<count;i++) wall.put(count*13+i,29+i);
        wall.put(count*13+count,27);
        int cursor=0;for(int i=0;i<order.length;i++) if(order[i]<0) {while(used[cursor] || count==3 && !ThreePlayerTiles.contains(cursor))cursor++;order[i]=cursor;used[cursor]=true;}
        var tiles=Arrays.stream(order).boxed().toList();
        if(count==4) {
            var r=FourPlayerRound.ordered(UUID.randomUUID(),ids,0,27,0,Collections.nCopies(count,25000),0,tiles);
            r.discard(ids.getFirst(),r.view(ids.getFirst()).command(),discard,riichi);
            for(int step=0;step<20;step++) {
                var v=r.view(null);
                if(v.opportunity().isPresent()) {for(UUID id:ids) {var own=r.view(id);if(!own.responses().isEmpty())r.respond(id,own.opportunity().orElseThrow().id(),DiscardResponseWindow.Simple.PASS);}continue;}
                if(v.actorSeat()==0) {if(r.tsumo(ids.getFirst(),r.view(ids.getFirst()).command())!=FourPlayerRound.Status.ACCEPTED)throw new AssertionError("real tsumo unavailable");return GameArchiveCodec.encodeRound(r);}
                UUID actor=ids.get(v.actorSeat());var own=r.view(actor);r.discard(actor,own.command(),own.ownDraw().orElseThrow(),false);
            }
        } else {
            var r=ThreePlayerRound.ordered(UUID.randomUUID(),ids,0,27,0,Collections.nCopies(count,35000),0,tiles);
            r.discard(ids.getFirst(),r.view(ids.getFirst()).command(),discard,riichi);
            for(int step=0;step<20;step++) {
                var v=r.view(null);
                if(v.opportunity().isPresent()) {for(UUID id:ids) {var own=r.view(id);if(!own.responses().isEmpty())r.respond(id,own.opportunity().orElseThrow().id(),DiscardResponseWindow.Simple.PASS);}continue;}
                if(v.actorSeat()==0) {if(r.tsumo(ids.getFirst(),r.view(ids.getFirst()).command())!=ThreePlayerRound.Status.ACCEPTED)throw new AssertionError("real sanma tsumo unavailable");return ThreePlayerArchiveCodec.encodeRound(r);}
                UUID actor=ids.get(v.actorSeat());var own=r.view(actor);r.discard(actor,own.command(),own.ownDraw().orElseThrow(),false);
            }
        }
        throw new AssertionError("ordered wall did not reach the next dealer draw");
    }

    private static final class Scenario implements AutoCloseable {
        final LobbyService service;final java.nio.file.Path path;final List<ConnectedTestPlayer> players=new ArrayList<>();final List<UUID> views=new ArrayList<>();UUID id;
        Scenario(GameTestHelper h,int mode) {
            var pos=h.absolutePos(new BlockPos(2,2,2));
            FurnitureTestLayout.body(h.getLevel(),pos,net.minecraft.core.Direction.NORTH);
            FurnitureTestLayout.chairs(h.getLevel(),pos,mode>=3?3:4);
            path=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data/riichicraft-tests/cover-"+UUID.randomUUID()+"/tables.dat");
            service=new LobbyService(h.getLevel().getServer(),path);
            for(int seat=0;seat<(mode>=3?3:4);seat++) {
                var fixture=new ConnectedTestPlayer(h.getLevel().getServer(),h.getLevel(),pos,"Cover"+seat);players.add(fixture);views.add(service.open(fixture.player(),pos));
                service.handleAction(fixture.player(),new LobbyActionPayload(views.get(seat),id==null?1:service.registry().snapshot(id).orElseThrow().revision(),LobbyActionPayload.JOIN,false));
                id=service.registry().tableForPlayer(fixture.player().getUUID()).orElseThrow();
            }
            service.handleAction(players.getFirst().player(),new LobbyActionPayload(views.getFirst(),service.registry().snapshot(id).orElseThrow().revision(),LobbyActionPayload.SET_MODE,false,mode));
            for(int seat=0;seat<players.size();seat++) service.handleAction(players.get(seat).player(),new LobbyActionPayload(views.get(seat),service.registry().snapshot(id).orElseThrow().revision(),LobbyActionPayload.SET_READY,true));
            service.handleAction(players.getFirst().player(),new LobbyActionPayload(views.getFirst(),service.registry().snapshot(id).orElseThrow().revision(),LobbyActionPayload.START,false));
        }
        GameSnapshot game(int seat) { return service.gameView(id,players.get(seat).player().getUUID()); }
        void action(int seat,int action,int tile,long window) {var v=game(seat);service.handleGameAction(players.get(seat).player(),new GameActionPayload(views.get(seat),v.gameId(),v.revision(),action,tile,window,-1,List.of()));}
        void passAll() { for(int seat=0;seat<players.size();seat++) {var v=game(seat);if(!v.rules().responses().isEmpty())action(seat,GameActionPayload.PASS,-1,v.rules().opportunity().serial());} }
        @Override public void close() { for(var fixture:players){service.disconnect(fixture.player());fixture.close();}service.stop(); }
    }
}
