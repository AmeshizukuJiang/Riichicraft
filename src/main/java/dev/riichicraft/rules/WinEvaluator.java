package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a hand against a supplied winning opportunity, including yaku, furiten and kan source.
 * It does not establish the opportunity, action history, actor, or live round/window identity.
 * Future authoritative round code must establish those facts before calling this evaluator.
 */
public final class WinEvaluator {
    public enum Source { SELF_DRAW, DISCARD, ADDED_KAN, CLOSED_KAN }
    public enum Rejection {
        NO_WINNING_SHAPE, NO_YAKU, DISCARD_FURITEN, TEMPORARY_FURITEN,
        RIICHI_FURITEN, CLOSED_KAN_REQUIRES_KOKUSHI
    }

    /** handValue may be present even when furiten blocks ron. No payments are applied here. */
    public record Decision(Optional<ScoreResult> handValue, Set<Rejection> rejections) {
        public Decision {
            Objects.requireNonNull(handValue, "handValue");
            rejections = Set.copyOf(Objects.requireNonNull(rejections, "rejections"));
            if (handValue.isEmpty() && rejections.isEmpty()) {
                throw new IllegalArgumentException("An allowed win must have a hand value");
            }
        }

        public boolean allowed() { return rejections.isEmpty() && handValue.isPresent(); }
    }

    private WinEvaluator() { }

    public static Decision evaluate(HandInput completedHand, WinContext context,
                                    FuritenState history, Source source) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(source, "source");
        WinInterpreter.requireValidContext(completedHand, context);
        requireMatchingSource(context, source);
        if (source == Source.ADDED_KAN || source == Source.CLOSED_KAN) requirePossibleKan(completedHand, context);

        Set<Rejection> reasons = EnumSet.noneOf(Rejection.class);
        var shapes = HandAnalyzer.analyze(completedHand);
        Optional<ScoreResult> handValue = HandScorer.score(completedHand, context);
        if (shapes.isEmpty()) reasons.add(Rejection.NO_WINNING_SHAPE);
        else if (handValue.isEmpty()) reasons.add(Rejection.NO_YAKU);

        if (!context.tsumo()) {
            // Remove this physical tile, not another copy or the scorer's preferred explanation.
            var beforeTiles = new ArrayList<>(completedHand.concealedTiles());
            beforeTiles.remove(Integer.valueOf(context.winningTileId()));
            HandInput before = new HandInput(beforeTiles, completedHand.melds());
            FuritenAnalyzer.Status furiten = FuritenAnalyzer.evaluate(before, history);
            if (furiten.discardFuriten()) reasons.add(Rejection.DISCARD_FURITEN);
            if (furiten.temporary()) reasons.add(Rejection.TEMPORARY_FURITEN);
            if (furiten.riichiPass()) reasons.add(Rejection.RIICHI_FURITEN);
        }
        if (source == Source.CLOSED_KAN && !shapes.isEmpty()
                && shapes.stream().noneMatch(shape -> shape.form() == HandDecomposition.Form.THIRTEEN_ORPHANS)) {
            reasons.add(Rejection.CLOSED_KAN_REQUIRES_KOKUSHI);
        }
        return new Decision(handValue, reasons);
    }

    private static void requireMatchingSource(WinContext context, Source source) {
        boolean matches = switch (source) {
            case SELF_DRAW -> context.tsumo();
            case DISCARD -> !context.tsumo() && context.event() != WinContext.WinEvent.CHANKAN;
            case ADDED_KAN, CLOSED_KAN -> !context.tsumo() && context.event() == WinContext.WinEvent.CHANKAN;
        };
        if (!matches) throw new IllegalArgumentException("Winning source does not match the context");
    }

    private static void requirePossibleKan(HandInput hand, WinContext context) {
        int type = TileCatalog.typeOf(context.winningTileId());
        // The declarer owns the other three copies. Any further held or indicator copy needs a fifth.
        if (hand.tileCounts()[type] != 1
                || context.doraIndicators().stream().anyMatch(tile -> TileCatalog.typeOf(tile) == type)
                || context.uraIndicators().stream().anyMatch(tile -> TileCatalog.typeOf(tile) == type)) {
            throw new IllegalArgumentException("The kan tile's four copies cannot also occur elsewhere");
        }
    }
}
