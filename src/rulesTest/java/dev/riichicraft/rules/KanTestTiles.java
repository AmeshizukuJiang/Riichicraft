package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared physical pool: fixture hands, declared groups and indicators never silently reuse copies. */
final class KanTestTiles {
    final Set<Integer> used=new HashSet<>();
    int take(int type) { for(int tile=type*4;tile<type*4+4;tile++) if(used.add(tile)) return tile; throw new AssertionError("Exhausted fixture type "+type); }
    List<Integer> fill(int count) { var result=new ArrayList<Integer>(); for(int tile=135;result.size()<count;tile--) if(used.add(tile)) result.add(tile); return result; }
    List<Integer> singles(int count) { var result=new ArrayList<Integer>(); for(int type=33;result.size()<count;type--) if(!used.contains(type*4)) result.add(take(type)); return result; }
    List<Integer> tiles(String notation) {
        var result=new ArrayList<Integer>(); String digits="";
        for(char ch:notation.toCharArray()) {
            if(Character.isDigit(ch)) { digits+=ch; continue; }
            int base=switch(ch) { case 'm'->0;case 'p'->9;case 's'->18;default->-1; };
            if(base>=0) { for(char digit:digits.toCharArray()) result.add(take(base+digit-'1')); digits=""; }
            else result.add(take(switch(ch) { case 'E'->27;case 'S'->28;case 'W'->29;case 'N'->30;case 'P'->31;case 'F'->32;case 'C'->33;default->throw new AssertionError("notation"); }));
        }
        return result;
    }
    HandInput hand(String notation) { return new HandInput(tiles(notation),List.of()); }
    static HandInput plus(HandInput hand,int tile) { var held=new ArrayList<>(hand.concealedTiles()); held.add(tile); return new HandInput(held,hand.melds()); }
    static Set<Integer> all(HandInput hand) { var result=new HashSet<>(hand.concealedTiles()); hand.melds().forEach(m->result.addAll(m.tiles())); return result; }
}
