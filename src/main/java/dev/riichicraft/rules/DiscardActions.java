package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/** Immutable discard operations, after a real draw or an already selected ordinary-discard call. */
public final class DiscardActions {
    public record Result(HandInput hand, int discardedTileId, boolean tsumogiri, FuritenState history) {
        public Result {
            Objects.requireNonNull(hand, "hand");
            Objects.requireNonNull(history, "history");
            TileCatalog.typeOf(discardedTileId);
            if (!hand.isWaiting()) throw new IllegalArgumentException("A discard result needs structural thirteen");
        }
    }

    private DiscardActions() { }

    /** The owner supplies the exact last drawn copy; riichi locks every other physical tile. */
    public static Set<Integer> afterDrawOptions(HandInput hand, int drawnTileId, WinContext.Riichi riichi) {
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(riichi, "riichi");
        TileCatalog.typeOf(drawnTileId);
        if (!hand.isComplete() || !hand.concealedTiles().contains(drawnTileId)) {
            throw new IllegalArgumentException("A drawn turn needs structural fourteen and its exact concealed drawn tile");
        }
        if (riichi != WinContext.Riichi.NONE && !hand.isClosed()) {
            throw new IllegalArgumentException("An open hand cannot already be in riichi");
        }
        return riichi == WinContext.Riichi.NONE ? Set.copyOf(hand.concealedTiles()) : Set.of(drawnTileId);
    }

    /** Discarding never clears temporary furiten; the owner records the preceding real draw separately. */
    public static Result afterDraw(HandInput hand, int drawnTileId, WinContext.Riichi riichi,
                                   FuritenState history, int discardedTileId) {
        if (!afterDrawOptions(hand, drawnTileId, riichi).contains(discardedTileId)) {
            throw new IllegalArgumentException("The selected physical tile cannot be discarded on this drawn turn");
        }
        return execute(hand, history, discardedTileId, discardedTileId == drawnTileId);
    }

    /** Only the immediate discard following a validated chi/pon application, with its kuikae mask. */
    public static Result afterCall(CallActions.Application call, FuritenState history, int discardedTileId) {
        Objects.requireNonNull(call, "call");
        if (call.needsReplacementDraw() || !call.hand().isComplete()
                || !call.legalDiscardTiles().contains(discardedTileId)
                || call.forbiddenDiscardTypes().contains(TileCatalog.typeOf(discardedTileId))) {
            throw new IllegalArgumentException("Chi/pon must discard a permitted tile; open kan must draw replacement first");
        }
        return execute(call.hand(), history, discardedTileId, false);
    }

    private static Result execute(HandInput hand, FuritenState history, int tile, boolean tsumogiri) {
        Objects.requireNonNull(history, "history");
        var concealed = new ArrayList<>(hand.concealedTiles());
        if (!concealed.remove(Integer.valueOf(tile))) throw new IllegalArgumentException("Discard must be concealed");
        return new Result(new HandInput(concealed, hand.melds()), tile, tsumogiri, history.afterDiscard(tile));
    }
}
