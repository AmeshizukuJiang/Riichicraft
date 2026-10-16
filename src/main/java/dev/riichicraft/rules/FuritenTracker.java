package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.Objects;

/** Updates furiten after an established opponent discard or kan declaration was not won by ron. */
public final class FuritenTracker {
    private FuritenTracker() { }

    /**
     * Observe another player's discard after this player did not ron it, whether by passing,
     * having no yaku, already being furiten, or choosing a call. Use the waiting hand before
     * any chi/pon/kan changes. The caller establishes actor, response resolution and riichi.
     * A structural completion triggers furiten even without yaku (user ruling 2026-10-16).
     * This method is not for self-discard, self-draw, or a kan-robbery event.
     */
    public static FuritenState afterDiscardWithoutRon(HandInput waitingHand, int discardedTileId,
                                                      FuritenState history,
                                                      WinContext.Riichi establishedRiichi) {
        Objects.requireNonNull(waitingHand, "waitingHand");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(establishedRiichi, "establishedRiichi");
        TileCatalog.typeOf(discardedTileId);
        if (!waitingHand.isWaiting()) {
            throw new IllegalArgumentException("A discard response requires the preceding 13-tile structural hand");
        }
        if (establishedRiichi != WinContext.Riichi.NONE && !waitingHand.isClosed()) {
            throw new IllegalArgumentException("Established riichi requires a closed hand");
        }

        // Exact physical identity and the four-copy limit are checked by HandInput, even for
        // a non-winning discard. Do not ask HandScorer or WinEvaluator: no-yaku and furiten
        // rejections do not prevent a missed structural completion from setting the flag.
        var candidateTiles = new ArrayList<>(waitingHand.concealedTiles());
        candidateTiles.add(discardedTileId);
        HandInput candidate = new HandInput(candidateTiles, waitingHand.melds());
        return HandAnalyzer.analyze(candidate).isEmpty()
                ? history : history.afterPassedRon(establishedRiichi);
    }

    /** User ruling: kakan uses every completion; ankan uses kokushi completions only. */
    public static FuritenState afterKanWithoutRon(HandInput waitingHand, int claimedTileId,
                                                  FuritenState history, WinContext.Riichi establishedRiichi,
                                                  WinEvaluator.Source source) {
        Objects.requireNonNull(waitingHand,"waitingHand");
        Objects.requireNonNull(history,"history");
        Objects.requireNonNull(establishedRiichi,"establishedRiichi");
        if (source!=WinEvaluator.Source.ADDED_KAN && source!=WinEvaluator.Source.CLOSED_KAN) {
            throw new IllegalArgumentException("A kan miss needs an added/closed-kan source");
        }
        if (!waitingHand.isWaiting() || establishedRiichi!=WinContext.Riichi.NONE && !waitingHand.isClosed()) {
            throw new IllegalArgumentException("A kan responder needs structural thirteen and valid established-riichi syntax");
        }
        int type=TileCatalog.typeOf(claimedTileId);
        var tiles=new ArrayList<>(waitingHand.concealedTiles()); tiles.add(claimedTileId);
        HandInput completed=new HandInput(tiles,waitingHand.melds());
        if (completed.tileCounts()[type]!=1) throw new IllegalArgumentException("The declarer owns the other three kan copies");
        var forms=HandAnalyzer.analyze(completed);
        boolean eligible=source==WinEvaluator.Source.ADDED_KAN?!forms.isEmpty():forms.stream()
                .anyMatch(form->form.form()==HandDecomposition.Form.THIRTEEN_ORPHANS);
        return eligible?history.afterPassedRon(establishedRiichi):history;
    }
}
