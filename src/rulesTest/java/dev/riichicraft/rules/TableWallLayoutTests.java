package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import dev.riichicraft.table.TableWallLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/** Compares the public positional projection against the independent private wall implementations. */
public final class TableWallLayoutTests {
    private static int assertions;
    public static void main(String[] args) {
        for (int players : List.of(3,4)) {
            int total = players == 4 ? 136 : 108;
            var seen = new HashSet<Integer>();
            for (int i=0;i<total;i++) check(seen.add(TableWallLayout.physicalIndex(players,i)), "Position mapping is bijective");
            for (int front=0;front<4;front++) for (int empty=0;empty<(players==3?4:1);empty++) {
                int missing=empty;
                var sides=IntStream.range(0,4).filter(s->players==4 || s!=missing).boxed().toList();
                var slots=TableWallLayout.slots(players,total-14,0,1,front,sides);
                check(slots.size()==total,"Complete pre-deal geometry contains exactly one tile per position");
                for(int side:sides) check(slots.stream().filter(s->s.side()==side).count()==(players==4?34:36),"Each occupied side has 17/18 two-high stacks");
                check(slots.stream().allMatch(s->sides.contains(s.side())),"Empty chair side has no sanma wall");
            }
        }
        var four=RoundWall.deal(TileCatalog.allTiles()).wall();
        for(int r=0;r<=4;r++) {
            var suffix=four;
            for(int live=suffix.liveCount();live>=0;live--) {
                verify(4,TileCatalog.allTiles(),suffix.liveTiles(),suffix.deadTiles(),r,suffix.doraIndicators(r+1));
                if(live>0)suffix=suffix.drawLive().wall();
            }
            if(r<4) {
                var next=four.drawReplacement();
                delta(4,four.liveCount(),r,next.wall().liveCount(),r+1,TableWallLayout.physicalIndex(4,122+r));
                four=next.wall();
            }
        }
        threeSequences(ThreePlayerWall.deal(ThreePlayerTiles.allTiles()).wall());
        boolean rejected=false;
        try { TableWallLayout.slots(3,54,0,5,0,List.of(0,1,3)); } catch(IllegalArgumentException expected) {rejected=true;}
        check(rejected,"A later indicator cannot be shown while still live");
        System.out.println("Table wall layout: "+assertions+" assertions passed");
    }

    private static void threeSequences(ThreePlayerWall wall) {
        var suffix=wall;
        for(int live=suffix.liveCount();live>=0;live--) {
            verify(3,ThreePlayerTiles.allTiles(),suffix.liveTiles(),suffix.deadTiles(),suffix.replacementDraws(),
                    suffix.doraIndicators(suffix.establishedKans()+1));
            if(live>0) {
                var next=suffix.drawLive();
                int head=108-14-suffix.replacementDraws()-suffix.liveCount();
                delta(3,suffix.liveCount(),suffix.replacementDraws(),next.wall().liveCount(),suffix.replacementDraws(),head);
                suffix=next.wall();
            }
        }
        for(var source:List.of(ThreePlayerWall.Source.KAN,ThreePlayerWall.Source.KITA)) {
            if(source==ThreePlayerWall.Source.KAN && wall.establishedKans()==4
                    || source==ThreePlayerWall.Source.KITA && wall.establishedKitas()==4)continue;
            var next=wall.drawReplacement(source);
            delta(3,wall.liveCount(),wall.replacementDraws(),next.wall().liveCount(),next.wall().replacementDraws(),
                    TableWallLayout.physicalIndex(3,100+wall.replacementDraws()));
            threeSequences(next.wall());
        }
    }

    private static void verify(int players,List<Integer> order,List<Integer> live,List<Integer> dead,int r,List<Integer> indicators) {
        Map<Integer,Integer> at=new HashMap<>();
        for(int i=0;i<order.size();i++)at.put(TableWallLayout.physicalIndex(players,i),order.get(i));
        var expected=new HashSet<>(live); expected.addAll(dead);
        // All shorter reveal prefixes are possible while open/added kan dora remains delayed.
        for(int shown=1;shown<=indicators.size();shown++) {
            var slots=TableWallLayout.slots(players,live.size(),r,shown,0,players==4?List.of(0,1,2,3):List.of(0,1,3));
            Set<Integer> actual=new HashSet<>(), reserved=new HashSet<>();
            var positions=slots.stream().map(TableWallLayout.Slot::index).collect(java.util.stream.Collectors.toSet());
            for(var slot:slots) {
                int tile=at.get(slot.index()); check(actual.add(tile),"No duplicate wall tile");
                if(slot.reserve())reserved.add(tile);
                if(slot.layer()==1)check(positions.contains(slot.index()+1),"Every upper tile retains its physical lower support");
                if(slot.indicator()>=0)check(slot.layer()==1 && slot.reserve() && tile==indicators.get(slot.indicator()),"Visible dora is the exact core tile in its upper reserve position");
            }
            check(actual.equals(expected) && actual.size()==live.size()+14,"Wall identities and counts match the independent core");
            check(reserved.equals(new HashSet<>(dead)) && reserved.size()==14,"Live tail stays at its original position when incorporated into the fourteen-tile reserve");
        }
    }

    private static void delta(int players,int before,int r,int after,int nextR,int removed) {
        var sides=players==4?List.of(0,1,2,3):List.of(0,1,3);
        var a=TableWallLayout.slots(players,before,r,0,0,sides).stream().map(TableWallLayout.Slot::index).collect(java.util.stream.Collectors.toSet());
        var b=TableWallLayout.slots(players,after,nextR,0,0,sides).stream().map(TableWallLayout.Slot::index).collect(java.util.stream.Collectors.toSet());
        check(a.containsAll(b) && a.size()==b.size()+1,"A draw removes exactly one position and never repositions another");
        a.removeAll(b); check(a.equals(Set.of(removed)),"Ordinary draw and replacement remove their distinct exact ends");
    }
    private static void check(boolean value,String message) {assertions++;if(!value)throw new AssertionError(message);}
}
