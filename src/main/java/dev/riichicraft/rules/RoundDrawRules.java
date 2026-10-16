package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Draw eligibility and provenance-derived responsibility; actual phase belongs to FourPlayerRound. */
public final class RoundDrawRules {
    private RoundDrawRules() { }

    /** External visible copies, yaku and furiten do not change formal tenpai. Own fifth copies cannot win. */
    public static boolean tenpai(HandInput hand) {
        if (!hand.isWaiting()) throw new IllegalArgumentException("Formal tenpai needs structural thirteen");
        return !HandAnalyzer.waits(hand).isEmpty();
    }

    public static boolean nineTerminals(HandInput hand, int precedingDiscards, boolean anyCallOccurred) {
        if (precedingDiscards<0) throw new IllegalArgumentException("Discard count cannot be negative");
        if (!hand.isComplete()) throw new IllegalArgumentException("Nine terminals needs a drawn hand");
        return precedingDiscards==0 && !anyCallOccurred && hand.melds().isEmpty()
                && hand.concealedTiles().stream().map(TileCatalog::typeOf).filter(RoundDrawRules::terminalOrHonor).distinct().count()>=9;
    }

    /** Menzen is an explicit user ruling; declared closed kans retain menzen. Empty rivers never qualify. */
    public static boolean nagashi(HandInput hand, List<Integer> riverTiles, boolean anyDiscardClaimed) {
        return hand.isClosed() && !riverTiles.isEmpty() && !anyDiscardClaimed
                && riverTiles.stream().map(TileCatalog::typeOf).allMatch(RoundDrawRules::terminalOrHonor);
    }

    public static boolean fourWinds(List<List<Integer>> rivers, boolean anyCallOccurred) {
        if (rivers.size()!=4) throw new IllegalArgumentException("Four rivers required");
        if (anyCallOccurred || rivers.stream().anyMatch(r->r.size()!=1)) return false;
        int type=TileCatalog.typeOf(rivers.getFirst().getFirst());
        return type>=27 && type<=30 && rivers.stream().allMatch(r->TileCatalog.typeOf(r.getFirst())==type);
    }

    /** Called final dragon/wind only; prior DECLARED closed kans count, concealed undeclared sets do not. */
    public static Optional<RoundLedger.Pao> responsibility(HandInput before, CallActions.Application call) {
        if (call.meld().kind()!=Meld.Kind.PON && call.meld().kind()!=Meld.Kind.OPEN_KAN) return Optional.empty();
        int type=TileCatalog.typeOf(call.claimedTileId());
        int low, high; Yaku component;
        if (type>=31) { low=31; high=33; component=Yaku.DAISANGEN; }
        else if (type>=27 && type<=30) { low=27; high=30; component=Yaku.DAISUUSHII; }
        else return Optional.empty();
        Set<Integer> prior=new HashSet<>();
        for (Meld meld : before.melds()) {
            int held=TileCatalog.typeOf(meld.tiles().getFirst());
            if (meld.kind()!=Meld.Kind.CHI && held>=low && held<=high) prior.add(held);
        }
        return !prior.contains(type) && prior.size()==high-low
                ? Optional.of(new RoundLedger.Pao(call.providerSeat(),component)) : Optional.empty();
    }

    private static boolean terminalOrHonor(int type) { return type>=27 || type%9==0 || type%9==8; }
}
