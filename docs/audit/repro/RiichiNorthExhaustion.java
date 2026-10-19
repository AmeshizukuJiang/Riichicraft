package dev.riichicraft.rules;

import java.util.*;
import static dev.riichicraft.rules.DiscardResponseWindow.*;

/** Audit-only real accepted-action trajectory. No reflection or injected state. */
public final class RiichiNorthExhaustion {
    private static final List<UUID> PLAYERS = List.of(new UUID(801,0), new UUID(801,1), new UUID(801,2));
    public static void main(String[] args) {
        var r = ThreePlayerRound.ordered(new UUID(802,0), PLAYERS, 0, 27, 0,
                List.of(35000,35000,35000), 0, order());
        var east = r.view(PLAYERS.getFirst());
        require(r.discard(PLAYERS.getFirst(), east.command(), east.ownDraw().orElseThrow(), true));
        pass(r);
        for (int turn=0; turn<2; turn++) {
            var v = r.view(null);
            var player = PLAYERS.get(v.actorSeat());
            var own = r.view(player);
            require(r.discard(player,own.command(),own.ownDraw().orElseThrow(),false));
            pass(r);
        }
        for (int extracted=0; extracted<3; extracted++) {
            east = r.view(PLAYERS.getFirst());
            System.out.println("before extraction " + (extracted+1) + ": offered=" + east.turnOptions().kitas());
            require(r.kita(PLAYERS.getFirst(),east.command(),east.ownDraw().orElseThrow()));
            System.out.println("accepted extraction " + (extracted+1) + ": held norths="+r.serverState().norths().getFirst());
            r.assertConservation();
            if(extracted==2) {
                var archived=ThreePlayerArchiveCodec.encodeRound(r);
                r=ThreePlayerArchiveCodec.decodeRound(archived);
                System.out.println("restored accepted state, public view="+r.view(null).phase());
            }
            pass(r);
        }
        System.out.println("Established norths="+r.serverState().norths().getFirst());
        r.assertConservation();
        System.out.println("After third extraction, own action view:");
        System.out.println(r.view(PLAYERS.getFirst()));
    }
    private static void require(ThreePlayerRound.Status status) {
        if(status != ThreePlayerRound.Status.ACCEPTED) throw new AssertionError(status);
    }
    private static void pass(ThreePlayerRound r) {
        for(UUID player:PLAYERS) {
            var view=r.view(player);
            if(!view.responses().isEmpty()) require(r.respond(player,view.opportunity().orElseThrow().id(),Simple.PASS));
        }
    }
    private static List<Integer> order() {
        var used=new boolean[136]; var out=new Integer[108];
        String[] hands={"123456p789s345s4z","999m111p222s345s5z","111s999p555z666z2p"};
        for(int seat=0;seat<3;seat++) {
            var held=tiles(hands[seat],used);
            for(int i=0;i<13;i++) out[i*3+seat]=held.get(i);
        }
        var live=tiles("1m8p8s4z",used);
        for(int i=0;i<live.size();i++) out[39+i]=live.get(i);
        var replacement=tiles("44z9s",used);
        for(int i=0;i<replacement.size();i++) out[100+i]=replacement.get(i);
        out[98]=tiles("1z",used).getFirst();
        var remaining=ThreePlayerTiles.allTiles().stream().filter(id->!used[id]).iterator();
        for(int i=0;i<108;i++) if(out[i]==null) out[i]=remaining.next();
        return List.of(out);
    }
    private static List<Integer> tiles(String notation,boolean[] used) {
        var out=new ArrayList<Integer>(); var digits=new StringBuilder();
        for(char c:notation.toCharArray()) {
            if(Character.isDigit(c)) { digits.append(c); continue; }
            int offset=switch(c) {case 'm'->0;case 'p'->9;case 's'->18;case 'z'->27;default->throw new AssertionError(c);};
            for(char digit:digits.toString().toCharArray()) {
                int type=offset+digit-'1', selected=-1;
                for(int copy=0;copy<4;copy++) if(!used[type*4+copy]) {selected=type*4+copy;break;}
                if(selected<0) throw new AssertionError("Too many "+digit+c);
                used[selected]=true; out.add(selected);
            }
            digits.setLength(0);
        }
        return out;
    }
}
