package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Enumerates every possible assignment of a particular physical winning tile to a structure. */
public final class WinInterpreter {
    private WinInterpreter() { }

    public static List<WinningInterpretation> interpret(HandInput hand, int winningTileId) {
        Objects.requireNonNull(hand, "hand");
        int type = TileCatalog.typeOf(winningTileId);
        if (!hand.isComplete() || !hand.concealedTiles().contains(winningTileId)) {
            throw new IllegalArgumentException("The physical winning tile must belong to the complete concealed hand");
        }
        List<WinningInterpretation> result = new ArrayList<>();
        for (HandDecomposition shape : HandAnalyzer.analyze(hand)) {
            switch (shape.form()) {
                case SEVEN_PAIRS -> result.add(new WinningInterpretation(shape, -1, WinningInterpretation.Wait.PAIR));
                case THIRTEEN_ORPHANS -> result.add(new WinningInterpretation(shape, -1, WinningInterpretation.Wait.SPECIAL));
                case STANDARD -> {
                    if (shape.pairType() == type) {
                        result.add(new WinningInterpretation(shape, -1, WinningInterpretation.Wait.PAIR));
                    }
                    for (int index = 0; index < shape.groups().size(); index++) {
                        HandDecomposition.Group group = shape.groups().get(index);
                        if (group.open() || group.kind() == HandDecomposition.Kind.QUAD) continue;
                        if (group.kind() == HandDecomposition.Kind.TRIPLET && group.tileType() == type) {
                            result.add(new WinningInterpretation(shape, index, WinningInterpretation.Wait.SHANPON));
                        } else if (group.kind() == HandDecomposition.Kind.SEQUENCE
                                && type >= group.tileType() && type <= group.tileType() + 2) {
                            WinningInterpretation.Wait wait;
                            if (type == group.tileType() + 1) wait = WinningInterpretation.Wait.KANCHAN;
                            else if (group.tileType() % 9 == 0 && type == group.tileType() + 2
                                    || group.tileType() % 9 == 6 && type == group.tileType()) {
                                wait = WinningInterpretation.Wait.PENCHAN;
                            } else wait = WinningInterpretation.Wait.RYANMEN;
                            result.add(new WinningInterpretation(shape, index, wait));
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /** Checks both the context's hand-dependent syntax and membership of an interpretation. */
    public static void requireValid(HandInput hand, WinContext context, WinningInterpretation interpretation) {
        requireValidContext(hand, context);
        if (!interpret(hand, context.winningTileId()).contains(Objects.requireNonNull(interpretation, "interpretation"))) {
            throw new IllegalArgumentException("Interpretation does not describe this hand and winning tile");
        }
    }

    static void requireValidContext(HandInput hand, WinContext context) {
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(context, "context");
        if (!hand.isComplete() || !hand.concealedTiles().contains(context.winningTileId())) {
            throw new IllegalArgumentException("The physical winning tile must belong to the complete concealed hand");
        }
        if (context.riichi() != WinContext.Riichi.NONE && !hand.isClosed()) {
            throw new IllegalArgumentException("Riichi requires a closed hand");
        }
        if (context.firstTurn() != WinContext.FirstTurn.NONE && !hand.melds().isEmpty()) {
            throw new IllegalArgumentException("A first-turn win cannot contain declared groups");
        }
        if (context.event() == WinContext.WinEvent.RINSHAN && hand.melds().stream()
                .noneMatch(meld -> meld.kind() == Meld.Kind.OPEN_KAN || meld.kind() == Meld.Kind.CLOSED_KAN)) {
            throw new IllegalArgumentException("Rinshan requires a declared kan");
        }
        boolean[] held = new boolean[TileCatalog.TILE_COUNT];
        hand.concealedTiles().forEach(tile -> held[tile] = true);
        hand.melds().forEach(meld -> meld.tiles().forEach(tile -> held[tile] = true));
        for (List<Integer> indicators : List.of(context.doraIndicators(), context.uraIndicators())) {
            for (int tile : indicators) {
                if (held[tile]) throw new IllegalArgumentException("An indicator cannot also belong to the hand");
            }
        }
    }
}
