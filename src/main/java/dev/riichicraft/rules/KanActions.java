package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Self-turn ankan/kakan proposals. No kan is established until its robbery window finishes. */
public final class KanActions {
    public enum Kind { CLOSED, ADDED }
    public record Choice(Kind kind, int meldIndex, List<Integer> handTiles) {
        public Choice {
            Objects.requireNonNull(kind,"kind");
            handTiles=Objects.requireNonNull(handTiles,"handTiles").stream().sorted().toList();
            if (kind==Kind.CLOSED) {
                if (meldIndex!=-1) throw new IllegalArgumentException("Ankan does not replace a declared group");
                new Meld(Meld.Kind.CLOSED_KAN,handTiles);
            } else {
                if (meldIndex<0 || meldIndex>3 || handTiles.size()!=1) throw new IllegalArgumentException("Kakan needs a pon index and one exact held tile");
                handTiles.forEach(TileCatalog::typeOf);
            }
        }
    }
    public record Context(int seat, int liveTilesRemaining, int establishedKans, WinContext.Riichi riichi) {
        public Context {
            if (seat<0 || seat>3 || liveTilesRemaining<0 || liveTilesRemaining>70 || establishedKans<0 || establishedKans>4) {
                throw new IllegalArgumentException("Invalid kan seat, live-wall or established-kan count");
            }
            Objects.requireNonNull(riichi,"riichi");
        }
    }

    /** All private hands here are server-only counterfactual snapshots, not client payloads. */
    public static final class Proposal {
        private final Context context;
        private final Choice choice;
        private final HandInput before;
        private final HandInput established;
        private final HandInput robbed;
        private final Meld meld;
        private final int claimableTileId;

        private Proposal(HandInput hand,int drawn,Context context,Choice choice) {
            this.context=context; this.choice=choice; this.before=hand;
            var concealed=new ArrayList<>(hand.concealedTiles());
            var groups=new ArrayList<>(hand.melds());
            concealed.removeAll(choice.handTiles());
            List<Integer> groupTiles=new ArrayList<>(choice.handTiles());
            if (choice.kind()==Kind.CLOSED) {
                meld=new Meld(Meld.Kind.CLOSED_KAN,groupTiles);
                groups.add(meld);
                // No value distinction is possible for kokushi robbery; keep an exact deterministic copy.
                claimableTileId=choice.handTiles().contains(drawn)?drawn:choice.handTiles().getLast();
            } else {
                groupTiles.addAll(groups.get(choice.meldIndex()).tiles());
                meld=new Meld(Meld.Kind.OPEN_KAN,groupTiles.stream().sorted().toList());
                groups.set(choice.meldIndex(),meld);
                claimableTileId=choice.handTiles().getFirst();
            }
            established=new HandInput(concealed,groups);
            var afterRobbery=new ArrayList<>(hand.concealedTiles());
            afterRobbery.remove(Integer.valueOf(claimableTileId));
            robbed=new HandInput(afterRobbery,hand.melds());
        }

        public int declarerSeat() { return context.seat(); }
        public Context context() { return context; }
        public Choice choice() { return choice; }
        public HandInput handBefore() { return before; }
        public HandInput handIfEstablished() { return established; }
        public HandInput handIfRobbed() { return robbed; }
        public Meld meldIfEstablished() { return meld; }
        public int claimableTileId() { return claimableTileId; }
        public WinEvaluator.Source source() { return choice.kind()==Kind.CLOSED?WinEvaluator.Source.CLOSED_KAN:WinEvaluator.Source.ADDED_KAN; }
    }

    private KanActions() { }

    public static Set<Choice> options(HandInput hand,int drawnTileId,Context context) {
        Objects.requireNonNull(context,"context");
        DiscardActions.afterDrawOptions(hand,drawnTileId,context.riichi());
        long ownKans=hand.melds().stream().filter(m->m.tiles().size()==4).count();
        if (ownKans>context.establishedKans()) throw new IllegalArgumentException("Global kan count omits this player's kans");
        HandInput preceding=null;
        Set<Integer> precedingWaits=Set.of();
        if (context.riichi()!=WinContext.Riichi.NONE) {
            var held=new ArrayList<>(hand.concealedTiles()); held.remove(Integer.valueOf(drawnTileId));
            preceding=new HandInput(held,hand.melds()); precedingWaits=HandAnalyzer.waits(preceding);
            if (precedingWaits.isEmpty()) throw new IllegalArgumentException("An established riichi hand must have a preceding structural wait");
        }
        if (context.liveTilesRemaining()==0 || context.establishedKans()==4) return Set.of();
        Set<Choice> result=new HashSet<>();
        if (hand.melds().size()<4) for (int type=0;type<34;type++) {
            int target=type;
            List<Integer> held=hand.concealedTiles().stream().filter(tile->tile/4==target).toList();
            if (held.size()!=4) continue;
            Choice choice=new Choice(Kind.CLOSED,-1,held);
            if (context.riichi()!=WinContext.Riichi.NONE) {
                if (!held.contains(drawnTileId)) continue; // No okurikan of an old concealed four.
                HandInput after=new Proposal(hand,drawnTileId,context,choice).handIfEstablished();
                Set<Integer> afterWaits=HandAnalyzer.waits(after);
                if (!precedingWaits.equals(afterWaits)) continue;
                int[] oldCounts=preceding.tileCounts(),newCounts=after.tileCounts();
                if (precedingWaits.stream().anyMatch(wait->4-oldCounts[wait]!=4-newCounts[wait])) continue;
            }
            result.add(choice);
        }
        if (context.riichi()==WinContext.Riichi.NONE) for (int index=0;index<hand.melds().size();index++) {
            Meld old=hand.melds().get(index);
            if (old.kind()!=Meld.Kind.PON) continue;
            int type=old.tiles().getFirst()/4;
            for (int tile:hand.concealedTiles()) if (tile/4==type) result.add(new Choice(Kind.ADDED,index,List.of(tile)));
        }
        return Set.copyOf(result);
    }

    public static Proposal propose(HandInput hand,int drawnTileId,Context context,Choice choice) {
        Objects.requireNonNull(choice,"choice");
        if (!options(hand,drawnTileId,context).contains(choice)) throw new IllegalArgumentException("Kan is not legal on this drawn-turn snapshot");
        return new Proposal(hand,drawnTileId,context,choice);
    }
}
