package dev.riichicraft.test;
import dev.riichicraft.rules.*;
import java.util.*;
/** Fixed complete legal wall for a genuine north declaration with two chanta ron responders. */
public final class ThreePlayerTestWalls {
    private ThreePlayerTestWalls() { }
    public static ThreePlayerRound robbery(UUID id,List<UUID> players) {
        return ThreePlayerRound.ordered(id,players,0,27,0,List.of(35000,35000,35000),0,
            order(new String[]{"123p456p789s11m4z7s","123p789p123s789s4z","123p789p123s789s4z"},"4z",""));
    }
    private static List<Integer> order(String[] hands,String firstDraws,String replacements) {
        var used=new boolean[136]; Integer[] out=new Integer[108];
        for(int seat=0;seat<3;seat++) { var held=tiles(hands[seat],used); if(held.size()!=13)throw new AssertionError("Need thirteen"); for(int i=0;i<13;i++)out[i*3+seat]=held.get(i); }
        var draws=tiles(firstDraws,used); for(int i=0;i<draws.size();i++)out[39+i]=draws.get(i);
        var supplemental=tiles(replacements,used); for(int i=0;i<supplemental.size();i++)out[100+i]=supplemental.get(i);
        out[98]=tiles("1z",used).getFirst();
        var remaining=ThreePlayerTiles.allTiles().stream().filter(id->!used[id]).iterator();
        for(int i=0;i<108;i++) if(out[i]==null)out[i]=remaining.next();
        return List.of(out);
    }
    private static List<Integer> tiles(String notation,boolean[] used) {
        var out=new ArrayList<Integer>(); var ranks=new StringBuilder();
        for(char c:notation.toCharArray()) {
            if(Character.isDigit(c)) { ranks.append(c); continue; }
            int offset=switch(c) {case 'm'->0;case 'p'->9;case 's'->18;case 'z'->27;default->throw new AssertionError("Bad notation");};
            for(char digit:ranks.toString().toCharArray()) {
                int type=offset+(digit=='0'?5:digit-'0')-1,selected=-1;
                int[] copies=digit=='0'?new int[]{0}:type<27 && type%9==4?new int[]{1,2,3,0}:new int[]{0,1,2,3};
                for(int copy:copies)if(!used[type*4+copy]) { selected=type*4+copy;break; }
                if(selected<0)throw new AssertionError("Too many "+digit+c);used[selected]=true;out.add(selected);
            }
            ranks.setLength(0);
        }
        return out;
    }
}
