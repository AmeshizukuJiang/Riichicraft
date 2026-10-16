package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.Objects;

/** Updates furiten after an established opponent discard was not won by this player's ron. */
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
}
