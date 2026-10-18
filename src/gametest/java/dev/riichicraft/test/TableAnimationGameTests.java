package dev.riichicraft.test;

import dev.riichicraft.network.*;
import dev.riichicraft.table.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;

public final class TableAnimationGameTests {
    public record Scene(String name,TableDisplaySnapshot before,TableDisplaySnapshot after) { }
    public static TableDisplaySnapshot discard(TableDisplaySnapshot s,int actor,int tile,boolean draw){
        var seats=new ArrayList<>(s.seats());var own=seats.get(actor);var river=new ArrayList<>(own.river());river.add(new TableDisplaySnapshot.Discard(tile,false));
        seats.set(actor,new TableDisplaySnapshot.Seat(own.side(),own.concealedCount()-1,river,own.melds(),own.norths()));
        if(draw){int next=(actor+1)%seats.size();var n=seats.get(next);seats.set(next,new TableDisplaySnapshot.Seat(n.side(),n.concealedCount()+1,n.river(),n.melds(),n.norths()));}
        return copy(s,seats,s.liveTiles()-(draw?1:0),s.indicators(),false);
    }
    public static List<Scene> scenes(){
        var base=TableDisplayGameTests.opening(false);var discard=discard(base,0,0,true);var offered=discard(base,0,0,false);
        var result=new ArrayList<Scene>();result.add(new Scene("discard-and-draw",base,discard));
        for(int kind:new int[]{1,2}) {
            var seats=new ArrayList<>(offered.seats());var river=seats.get(0);seats.set(0,new TableDisplaySnapshot.Seat(river.side(),river.concealedCount(),List.of(),List.of(),List.of()));
            seats.set(2,new TableDisplaySnapshot.Seat(2,11,List.of(),List.of(new TableDisplaySnapshot.Meld(kind,kind==1?List.of(0,1,2):List.of(0,1,2,3),0,0,-1)),List.of()));
            result.add(new Scene(kind==1?"pon":"open-kan",offered,copy(offered,seats,kind==1?69:68,List.of(100),false)));
        }
        var pon=result.get(1).after();var addedSeats=new ArrayList<>(pon.seats());
        addedSeats.set(2,new TableDisplaySnapshot.Seat(2,11,List.of(),List.of(new TableDisplaySnapshot.Meld(2,List.of(0,1,2,3),0,0,3)),List.of()));
        result.add(new Scene("added-kan",pon,copy(pon,addedSeats,68,List.of(100),false)));
        var closedSeats=new ArrayList<>(base.seats());closedSeats.set(0,new TableDisplaySnapshot.Seat(0,11,List.of(),List.of(new TableDisplaySnapshot.Meld(3,List.of(0,1,2,3),-1,-1,-1)),List.of()));
        result.add(new Scene("closed-kan-and-flip",base,copy(base,closedSeats,68,List.of(100,104),false)));
        var three=TableDisplayGameTests.opening(true);var northSeats=new ArrayList<>(three.seats());northSeats.set(0,new TableDisplaySnapshot.Seat(0,14,List.of(),List.of(),List.of(120)));
        result.add(new Scene("kita",three,copy(three,northSeats,53,List.of(100),false)));
        var open=result.get(2).after();result.add(new Scene("deferred-flip-and-discard",open,copy(discard(open,2,8,true),discard(open,2,8,true).seats(),67,List.of(100,104),false)));
        // Valid chi with three distinct public tile types, before the caller's mandatory discard.
        var chiSeats=new ArrayList<>(offered.seats());chiSeats.set(0,new TableDisplaySnapshot.Seat(0,13,List.of(),List.of(),List.of()));
        chiSeats.set(1,new TableDisplaySnapshot.Seat(1,11,List.of(),List.of(new TableDisplaySnapshot.Meld(0,List.of(0,4,8),0,0,-1)),List.of()));
        result.add(new Scene("chi",offered,copy(offered,chiSeats,69,List.of(100),false)));
        return List.copyOf(result);
    }
    public static TableDisplayPayload packet(UUID id,UUID epoch,long sequence,long serial,TableDisplaySnapshot s,List<TableAnimationStep> steps){
        return new TableDisplayPayload(Level.OVERWORLD,id,BlockPos.ZERO,sequence,s,epoch,serial,steps);
    }
    public static List<TableAnimationPlayer.Frame> sample(Scene scene,long time){
        var id=UUID.randomUUID();var epoch=UUID.randomUUID();var p=new TableAnimationPlayer();
        p.accept(packet(id,epoch,1,0,scene.before(),List.of()),0);
        p.accept(packet(id,epoch,2,1,scene.after(),List.of(new TableAnimationStep(1,scene.before(),scene.after()))),0);
        return p.frames(id,time);
    }
    private static TableDisplaySnapshot copy(TableDisplaySnapshot s,List<TableDisplaySnapshot.Seat> seats,int live,List<Integer> indicators,boolean paused){
        return new TableDisplaySnapshot(s.gameId(),paused,false,s.frontSide(),live,indicators,seats);
    }
    @GameTest public void everyBasicActionHasSingleInstancesAndFrameIndependentExactEndpoints(GameTestHelper h){
        for(var scene:scenes())for(int fps:new int[]{30,60,144}) {
            var id=UUID.randomUUID();var epoch=UUID.randomUUID();var player=new TableAnimationPlayer();
            player.accept(packet(id,epoch,1,0,scene.before(),List.of()),0);
            player.accept(packet(id,epoch,2,1,scene.after(),List.of(new TableAnimationStep(1,scene.before(),scene.after()))),0);
            h.assertTrue(player.animating(id,0),"Scene actually animates: "+scene.name());
            int count=TableTileLayout.tiles(scene.after()).size();
            for(int frame=0;frame<=fps;frame++) {
                var view=player.frames(id,frame*1_000_000_000L/fps);
                h.assertTrue(view.size()==count && view.stream().map(f->f.tile().key()).distinct().count()==count,"Each animated physical tile has exactly one model");
                h.assertTrue(view.stream().allMatch(f->Float.isFinite(f.pose().x()) && Float.isFinite(f.pose().y()) && Float.isFinite(f.pose().z())
                        && (f.tile().group()!=TableTileLayout.Group.HAND || f.tile().face()==-1)),"Finite poses and no concealed face identity");
            }
            h.assertTrue(player.frames(id,2_000_000_000L).equals(TableAnimationPlayer.still(scene.after())),"Exact shared static endpoint at every frame rate");
        }h.succeed();
    }
    @GameTest public void callsMoveTheExactDiscardAndSupplementsWaitAtTheirExactWallEnd(GameTestHelper h){
        for(var scene:scenes()) {
            var id=UUID.randomUUID();var epoch=UUID.randomUUID();var p=new TableAnimationPlayer();
            p.accept(packet(id,epoch,1,0,scene.before(),List.of()),0);p.accept(packet(id,epoch,2,1,scene.after(),List.of(new TableAnimationStep(1,scene.before(),scene.after()))),0);
            var old=TableAnimationPlayer.still(scene.before());var initial=p.frames(id,0);
            if(scene.name().equals("pon") || scene.name().equals("chi") || scene.name().equals("open-kan"))
                h.assertTrue(find(initial,"p:0").pose().equals(find(old,"p:0").pose()),"The actual called copy starts in its provider's river");
            var finalKeys=TableTileLayout.tiles(scene.after()).stream().map(TableTileLayout.Tile::key).toList();
            var removed=old.stream().filter(f->f.tile().key().startsWith("w:") && !finalKeys.contains(f.tile().key())).toList();
            if(!removed.isEmpty())h.assertTrue(initial.stream().anyMatch(f->f.tile().group()==TableTileLayout.Group.HAND && f.pose().equals(removed.getFirst().pose())),
                    "Draw starts at the exact ordinary/rinshan slot, not at the incorporated live tail");
            if(scene.name().equals("kita"))h.assertTrue(p.frames(id,100_000_000L).stream().anyMatch(f->f.tile().group()==TableTileLayout.Group.HAND && f.pose().equals(removed.getFirst().pose())),
                    "Replacement waits while the north moves first");
        }h.succeed();
    }
    @GameTest public void boundedJournalHandlesMultipleActionsAndLifecycleWithoutReplayingOldHistory(GameTestHelper h){
        var s=TableDisplayGameTests.opening(false);var tape=new TableAnimationJournal(s);var epoch=tape.epoch();
        for(int i=0;i<20;i++){var next=discard(s,i%4,i*4,true);tape.record(s,next);s=next;}
        h.assertTrue(tape.serial()==20 && tape.since(epoch,4).size()==16 && tape.since(epoch,0).isEmpty(),"Bounded history preserves consecutive accepted states and refuses missing prefixes");
        var unchanged=tape.serial();tape.record(s,s);h.assertTrue(tape.serial()==unchanged,"No public change is not an animation event");
        var paused=copy(s,s.seats(),s.liveTiles(),s.indicators(),true);tape.synchronize(paused);
        h.assertTrue(!tape.epoch().equals(epoch) && tape.serial()==0 && tape.since(epoch,19).isEmpty(),"Pause/new baseline retires every old event");
        h.succeed();
    }
    @GameTest public void lateJoinDuplicatesGapsAndBacklogsConvergeToTheAuthoritativeSnapshot(GameTestHelper h){
        var s=TableDisplayGameTests.opening(false);var tape=new TableAnimationJournal(s);var epoch=tape.epoch();var id=UUID.randomUUID();
        for(int i=0;i<4;i++){var next=discard(s,i,i*4,true);tape.record(s,next);s=next;}
        var batch=packet(id,epoch,2,4,s,tape.since(epoch,0));var late=new TableAnimationPlayer();late.accept(batch,0);
        h.assertTrue(!late.animating(id,0) && late.frames(id,0).equals(TableAnimationPlayer.still(s)),"First arrival never replays old actions");
        var initial=batch.steps().getFirst().before();var p=new TableAnimationPlayer();p.accept(packet(id,epoch,1,0,initial,List.of()),0);p.accept(batch,0);
        h.assertTrue(!p.animating(id,0) && p.frames(id,0).equals(TableAnimationPlayer.still(s)),"Long burst skips to current state instead of queuing seconds of stale play");
        var first=batch.steps().getFirst();var one=packet(id,epoch,2,1,first.after(),List.of(first));
        p.clear();p.accept(packet(id,epoch,1,0,initial,List.of()),0);p.accept(one,0);p.accept(one,100_000_000L);
        h.assertTrue(p.frames(id,240_000_000L).equals(new TableAnimationPlayerFrameProbe(one).frames(240_000_000L)),"Duplicate snapshot cannot restart playback");
        p.accept(packet(id,UUID.randomUUID(),3,0,s,List.of()),250_000_000L);
        h.assertTrue(!p.animating(id,250_000_000L),"New stream/pause/reload snaps immediately");p.clear();h.assertTrue(p.frames(id,0).isEmpty(),"Dimension clear leaves no lingering model");h.succeed();
    }
    private static final class TableAnimationPlayerFrameProbe {
        final TableAnimationPlayer player=new TableAnimationPlayer();final UUID id;
        TableAnimationPlayerFrameProbe(TableDisplayPayload p){id=p.tableId();player.accept(packet(id,p.epoch(),1,0,p.steps().getFirst().before(),List.of()),0);player.accept(p,0);}
        List<TableAnimationPlayer.Frame> frames(long now){return player.frames(id,now);}
    }
    @GameTest public void animationCodecBoundsAndContinuityAreValidated(GameTestHelper h){
        var scene=scenes().getFirst();var p=packet(UUID.randomUUID(),UUID.randomUUID(),3,1,scene.after(),List.of(new TableAnimationStep(1,scene.before(),scene.after())));
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
        try{TableDisplayPayload.CODEC.encode(b,p);h.assertTrue(TableDisplayPayload.CODEC.decode(b).equals(p) && b.readableBytes()==0,"Ordered public transition round-trip");}finally{b.release();}
        for(int n:new int[]{-1,17,Integer.MAX_VALUE}) {
            b=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
            try{TableDisplayPayload.CODEC.encode(b,packet(p.tableId(),p.epoch(),1,0,scene.before(),List.of()));b.writerIndex(b.writerIndex()-1);b.writeVarInt(n);
                boolean rejected=false;try{TableDisplayPayload.CODEC.decode(b);}catch(DecoderException expected){rejected=true;}
                h.assertTrue(rejected,"Animation collection bound is checked before allocating");}finally{b.release();}
        }
        boolean rejected=false;try{packet(p.tableId(),p.epoch(),3,2,scene.after(),p.steps());}catch(IllegalArgumentException expected){rejected=true;}
        h.assertTrue(rejected,"A contradictory serial cannot masquerade as a continuous event stream");
        rejected=false;try{packet(p.tableId(),UUID.randomUUID(),4,0,null,List.of());}catch(IllegalArgumentException expected){rejected=true;}
        h.assertTrue(rejected,"Removals cannot carry unencodable animation metadata; their canonical empty form round-trips");h.succeed();
    }
    private static TableAnimationPlayer.Frame find(List<TableAnimationPlayer.Frame> list,String key){return list.stream().filter(f->f.tile().key().equals(key)).findFirst().orElseThrow();}
}
