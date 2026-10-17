package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Immutable server-only 108-tile wall. Logical indices are not a physical table animation. */
public final class ThreePlayerWall {
    public enum Source { LIVE, LAST_LIVE, KAN, KITA }
    public record Draw(int tileId, Source source, ThreePlayerWall wall) { }
    public record Deal(List<HandInput> hands, ThreePlayerWall wall) {
        public Deal { hands=List.copyOf(hands); Objects.requireNonNull(wall); }
    }
    private final List<Integer> order;
    private final int head, tail, kans, kitas;

    private ThreePlayerWall(List<Integer> order,int head,int tail,int kans,int kitas) {
        this.order=order; this.head=head; this.tail=tail; this.kans=kans; this.kitas=kitas;
    }
    public static Deal shuffled(long seed) {
        var order=new ArrayList<>(ThreePlayerTiles.allTiles()); var random=new Random(seed);
        for(int left=order.size();left>1;left--) Collections.swap(order,left-1,random.nextInt(left));
        return deal(order);
    }
    public static Deal deal(List<Integer> tiles) {
        var order=List.copyOf(tiles);
        if(order.size()!=108 || new HashSet<>(order).size()!=108) throw new IllegalArgumentException("108 distinct sanma tiles required");
        order.forEach(ThreePlayerTiles::typeOf);
        var hands=new ArrayList<HandInput>();
        for(int seat=0;seat<3;seat++) {
            var held=new ArrayList<Integer>(); for(int draw=0;draw<13;draw++) held.add(order.get(draw*3+seat));
            hands.add(new HandInput(held.stream().sorted().toList(),List.of()));
        }
        return new Deal(hands,new ThreePlayerWall(order,39,94,0,0));
    }
    public int liveCount() { return tail-head; }
    public int replacementDraws() { return kans+kitas; }
    public int establishedKans() { return kans; }
    public int establishedKitas() { return kitas; }
    public boolean canDrawReplacement() { return liveCount()>0 && replacementDraws()<8; }
    public List<Integer> liveTiles() { return order.subList(head,tail); }
    public List<Integer> deadTiles() {
        var result=new ArrayList<Integer>();
        for(int index=tail;index<108;index++) if(index<100 || index>=100+replacementDraws()) result.add(order.get(index));
        return List.copyOf(result);
    }
    public Draw drawLive() {
        if(liveCount()==0) throw new IllegalStateException("No live tile remains");
        return new Draw(order.get(head),liveCount()==1?Source.LAST_LIVE:Source.LIVE,
                new ThreePlayerWall(order,head+1,tail,kans,kitas));
    }
    /** Call only for a survived kan/kita; live tail moves into the fourteen-tile reserve. */
    public Draw drawReplacement(Source source) {
        if(source!=Source.KAN && source!=Source.KITA) throw new IllegalArgumentException("Not a replacement source");
        if(!canDrawReplacement() || source==Source.KAN && kans==4 || source==Source.KITA && kitas==4)
            throw new IllegalStateException("Replacement limit reached");
        int tile=order.get(100+replacementDraws());
        return new Draw(tile,source,new ThreePlayerWall(order,head,tail-1,kans+(source==Source.KAN?1:0),kitas+(source==Source.KITA?1:0)));
    }
    public List<Integer> doraIndicators(int count) { return indicators(count,98); }
    public List<Integer> uraIndicators(int count) { return indicators(count,99); }
    private List<Integer> indicators(int count,int offset) {
        if(count<1 || count>kans+1) throw new IllegalArgumentException("Indicators exceed established kan count");
        var result=new ArrayList<Integer>();
        for(int index=0;index<count;index++) {
            int position=offset-index*2;
            if(position<tail) throw new IllegalStateException("Indicator is still in live wall");
            result.add(order.get(position));
        }
        return List.copyOf(result);
    }
}
