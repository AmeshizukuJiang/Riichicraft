package dev.riichicraft.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sanma structural inventory: extracted north is owned, but never a group or discard. */
public final class ThreePlayerHands {
    private ThreePlayerHands() { }
    public static void validate(HandInput hand,List<Integer> norths) {
        var occupied=new HashSet<>(hand.concealedTiles());
        hand.concealedTiles().forEach(ThreePlayerTiles::typeOf);
        for(Meld meld:hand.melds()) {
            if(meld.kind()==Meld.Kind.CHI) throw new IllegalArgumentException("Sanma has no chi");
            meld.tiles().forEach(ThreePlayerTiles::typeOf); occupied.addAll(meld.tiles());
        }
        for(int north:norths) if(ThreePlayerTiles.typeOf(north)!=30 || !occupied.add(north))
            throw new IllegalArgumentException("Invalid or multiply owned extracted north");
    }
    public static Set<Integer> waits(HandInput hand,List<Integer> norths) {
        validate(hand,norths);
        int[] counts=hand.tileCounts(); counts[30]+=norths.size();
        var result=new HashSet<>(HandAnalyzer.waits(hand));
        result.removeIf(type->!ThreePlayerTiles.containsType(type) || counts[type]==4);
        return Set.copyOf(result);
    }
    public static FuritenAnalyzer.Status furiten(HandInput hand,List<Integer> norths,FuritenState history) {
        var waits=waits(hand,norths); var discarded=new HashSet<>(waits); discarded.retainAll(history.discardedTypes());
        return new FuritenAnalyzer.Status(waits,discarded,history.temporary(),history.riichiPass());
    }
    public static FuritenState missed(HandInput hand,List<Integer> norths,int tile,FuritenState history,
                                      WinContext.Riichi riichi,boolean kokushiOnly) {
        ThreePlayerTiles.typeOf(tile); validate(hand,norths);
        if(!hand.isWaiting()) throw new IllegalArgumentException("A responder needs a waiting hand");
        var tiles=new java.util.ArrayList<>(hand.concealedTiles()); tiles.add(tile);
        var complete=new HandInput(tiles,hand.melds()); validate(complete,norths);
        if(!waits(hand,norths).contains(tile/4)) return history;
        boolean eligible=!kokushiOnly || HandAnalyzer.analyze(complete).stream()
                .anyMatch(shape->shape.form()==HandDecomposition.Form.THIRTEEN_ORPHANS);
        return eligible?history.afterPassedRon(riichi):history;
    }
}
