package dev.riichicraft.table;

import dev.riichicraft.network.TableAnimationStep;
import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.network.TableDisplaySnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.table.TableTileLayout.Group.*;

/** Cosmetic, caller-clock driven player. The authoritative snapshot is never delayed or edited. */
public final class TableAnimationPlayer {
    public record Frame(TableTileLayout.Tile tile,TileMotion.Pose pose) { }
    private record Tween(TableTileLayout.Tile target,TileMotion.Pose from,long delay,long duration,float arc) {
        Frame frame(long elapsed){return new Frame(target,TileMotion.sample(from,pose(target),
                duration==0?1:TileMotion.progress(elapsed,delay,duration),arc));}
    }
    private record Plan(List<Tween> tweens,long duration) {
        List<Frame> frames(long elapsed){return tweens.stream().map(t->t.frame(elapsed)).toList();}
    }
    private static final long MOVE=240_000_000L,DRAW=260_000_000L,FLIP=220_000_000L,LIMIT=1_200_000_000L;
    private static final class Track {
        TableDisplaySnapshot state;UUID epoch;long cursor,start;
        final ArrayDeque<Plan> queue=new ArrayDeque<>();
        Track(TableDisplayPayload p){state=p.snapshot();epoch=p.epoch();cursor=p.actionSerial();}
        void advance(long now){while(!queue.isEmpty() && now-start>=queue.getFirst().duration()) {start+=queue.removeFirst().duration();}}
    }
    private final Map<UUID,Track> tracks=new HashMap<>();
    public void clear(){tracks.clear();}
    public void retain(Set<UUID> ids){tracks.keySet().retainAll(ids);}
    public void accept(TableDisplayPayload p,long now){
        if(p.snapshot()==null){tracks.remove(p.tableId());return;}
        Track old=tracks.get(p.tableId());
        if(old!=null && old.epoch.equals(p.epoch()) && old.cursor==p.actionSerial() && old.state.equals(p.snapshot()))return;
        if(old==null || !old.epoch.equals(p.epoch()) || p.steps().isEmpty()
                || p.steps().getFirst().serial()!=old.cursor+1 || !p.steps().getFirst().before().equals(old.state)) {
            tracks.put(p.tableId(),new Track(p));return;
        }
        old.advance(now);if(old.queue.isEmpty())old.start=now;
        for(var step:p.steps()) {var plan=plan(step);if(plan.duration()>0)old.queue.addLast(plan);}
        old.state=p.snapshot();old.cursor=p.actionSerial();
        long backlog=old.queue.stream().mapToLong(Plan::duration).sum()-(now-old.start);
        if(backlog>LIMIT)old.queue.clear();
    }
    public boolean animating(UUID id,long now){var t=tracks.get(id);if(t==null)return false;t.advance(now);return !t.queue.isEmpty();}
    public List<Frame> frames(UUID id,long now){
        var t=tracks.get(id);if(t==null)return List.of();t.advance(now);
        return t.queue.isEmpty()?still(t.state):t.queue.getFirst().frames(Math.max(0,now-t.start));
    }
    public static List<Frame> still(TableDisplaySnapshot s){return TableTileLayout.tiles(s).stream().map(t->new Frame(t,pose(t))).toList();}
    private static TileMotion.Pose pose(TableTileLayout.Tile t){return new TileMotion.Pose(t.x(),t.y(),t.z(),t.yaw(),t.flat()?(t.down()?90:-90):0,t.scale());}
    private static Map<String,TableTileLayout.Tile> index(List<TableTileLayout.Tile> tiles){
        var map=new HashMap<String,TableTileLayout.Tile>();for(var t:tiles)if(map.put(t.key(),t)!=null)throw new IllegalArgumentException("duplicate render key");return map;
    }
    private static Plan plan(TableAnimationStep step){
        var before=TableTileLayout.tiles(step.before());var after=TableTileLayout.tiles(step.after());
        try {return plan(step,before,after,index(before),index(after));}
        catch(IllegalArgumentException invalid){return new Plan(after.stream().map(t->new Tween(t,pose(t),0,0,0)).toList(),0);}
    }
    private static Plan plan(TableAnimationStep step,List<TableTileLayout.Tile> before,List<TableTileLayout.Tile> after,
                             Map<String,TableTileLayout.Tile> from,Map<String,TableTileLayout.Tile> to){
        var removedWall=before.stream().filter(t->t.key().startsWith("w:") && !to.containsKey(t.key())).toList();
        boolean declaration=false,discard=false;int drawOwner=-1;
        for(var t:after)if(!from.containsKey(t.key())) {
            if(t.group()==MELD || t.group()==NORTH){declaration=true;drawOwner=t.seat();}
            if(t.group()==RIVER)discard=true;
        }
        if(removedWall.size()>1)throw new IllegalArgumentException("missing action boundary");
        if(!removedWall.isEmpty() && !declaration)for(int seat=0;seat<step.after().seats().size();seat++)
            if(step.after().seats().get(seat).concealedCount()>step.before().seats().get(seat).concealedCount())drawOwner=seat;
        String drawKey=null;
        if(!removedWall.isEmpty()) {
            if(drawOwner<0)throw new IllegalArgumentException("unknown draw owner");
            drawKey="h:"+drawOwner+":"+(step.after().seats().get(drawOwner).concealedCount()-1);
            if(!to.containsKey(drawKey))throw new IllegalArgumentException("missing draw destination");
        }
        Map<Integer,ArrayDeque<TableTileLayout.Tile>> anonymous=new HashMap<>();
        for(var t:before)if(t.group()==HAND && (!to.containsKey(t.key()) || t.key().equals(drawKey)))
            anonymous.computeIfAbsent(t.seat(),k->new ArrayDeque<>()).addLast(t);
        boolean flip=after.stream().anyMatch(t->t.group()==INDICATOR && from.containsKey(t.key()) && from.get(t.key()).face()<0);
        long lead=flip && !declaration?FLIP:0;
        long drawDelay=(discard || declaration?MOVE:0)+(flip?FLIP:0);
        List<Tween> tweens=new ArrayList<>();long length=0;
        for(var target:after) {
            var source=from.get(target.key());long delay=0,duration=0;float arc=0;
            if(target.key().equals(drawKey)){source=removedWall.getFirst();delay=drawDelay;duration=DRAW;arc=3.5F;}
            else if(source==null) {
                var pool=anonymous.get(target.seat());
                if(pool==null || pool.isEmpty())throw new IllegalArgumentException("unknown public source");
                source=pool.removeFirst();delay=lead;duration=MOVE;arc=3.5F;
            } else if(target.group()==INDICATOR && source.face()<0){delay=declaration?MOVE:0;duration=FLIP;}
            else if(!pose(source).equals(pose(target))) {delay=lead;duration=MOVE;arc=target.group()==MELD?3.5F:0;}
            tweens.add(new Tween(target,pose(source),delay,duration,arc));length=Math.max(length,delay+duration);
        }
        return new Plan(List.copyOf(tweens),length);
    }
}
