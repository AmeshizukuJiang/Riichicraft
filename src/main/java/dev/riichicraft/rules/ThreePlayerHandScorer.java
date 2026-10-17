package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Independent three-player hand scoring; the caller establishes history and legal ron/tsumo. */
public final class ThreePlayerHandScorer {
    private static final Comparator<ThreePlayerScoreResult> BEST_FIRST = Comparator
            .comparingInt((ThreePlayerScoreResult result) -> result.points().payment().totalPoints()).reversed()
            .thenComparing(Comparator.comparingInt(ThreePlayerScoreResult::yakumanUnits).reversed())
            .thenComparing(Comparator.comparingInt(ThreePlayerScoreResult::han).reversed())
            .thenComparing(Comparator.comparingInt(ThreePlayerScoreResult::fu).reversed());

    private ThreePlayerHandScorer() { }

    public static Optional<ThreePlayerScoreResult> score(HandInput hand, ThreePlayerWinContext context) {
        return scoreAll(hand, context).stream().findFirst();
    }

    /** All eligible explanations, sorted by the actual two-payer three-player payment. */
    public static List<ThreePlayerScoreResult> scoreAll(HandInput hand, ThreePlayerWinContext facts) {
        requireValid(hand, facts);
        WinContext context = facts.context();
        List<ThreePlayerScoreResult> results = new ArrayList<>();
        List<Integer> winningTiles = context.firstTurn() == WinContext.FirstTurn.TENHOU
                ? hand.concealedTiles() : List.of(context.winningTileId());
        int[] counts = hand.tileCounts();
        counts[30] += facts.extractedNorths().size();
        for (int winningTile : winningTiles) {
            WinContext selected = winningTile == context.winningTileId() ? context
                    : new WinContext(context.method(), winningTile, context.seatWind(), context.roundWind(),
                            context.riichi(), context.ippatsu(), context.event(), context.firstTurn(),
                            context.doraIndicators(), context.uraIndicators());
            for (WinningInterpretation interpretation : WinInterpreter.interpret(hand, winningTile)) {
                ThreePlayerScoreResult result = scoreInterpretation(hand, facts, selected, interpretation, counts);
                if (result != null) results.add(result);
            }
        }
        results.sort(BEST_FIRST);
        return List.copyOf(results);
    }

    private static ThreePlayerScoreResult scoreInterpretation(HandInput hand, ThreePlayerWinContext facts,
            WinContext context, WinningInterpretation interpretation, int[] counts) {
        HandScorer.HandValue value = HandScorer.explainValue(hand, context, interpretation);
        int units = value.yakumanUnits();
        if (units > 0) {
            return new ThreePlayerScoreResult(interpretation, context.winningTileId(), value.yaku(),
                    0, 0, units, 0, 0, 0, 0,
                    ThreePlayerPointCalculator.calculate(0, 0, units, context.dealer(), context.tsumo()), value.fu());
        }
        List<YakuAward> awards = new ArrayList<>(value.yaku());
        if (facts.northEvent() == ThreePlayerWinContext.NorthEvent.SUPPLEMENT) {
            awards.add(new YakuAward(Yaku.RINSHAN_KAIHOU, 1, 0));
        }
        int yakuHan = awards.stream().mapToInt(YakuAward::han).sum();
        if (yakuHan == 0) return null;
        int dora = bonusCount(counts, context.doraIndicators());
        int ura = context.riichi() == WinContext.Riichi.NONE ? 0 : bonusCount(counts, context.uraIndicators());
        int aka = (int) hand.concealedTiles().stream().filter(ThreePlayerTiles::isRed).count();
        aka += hand.melds().stream().mapToInt(meld ->
                (int) meld.tiles().stream().filter(ThreePlayerTiles::isRed).count()).sum();
        int kita = facts.extractedNorths().size();
        int han = yakuHan + dora + aka + ura + kita;
        return new ThreePlayerScoreResult(interpretation, context.winningTileId(), awards,
                han, value.fu().fu(), 0, dora, aka, ura, kita,
                ThreePlayerPointCalculator.calculate(han, value.fu().fu(), 0, context.dealer(), context.tsumo()),
                value.fu());
    }

    private static int bonusCount(int[] counts, List<Integer> indicators) {
        return indicators.stream().mapToInt(tile -> counts[ThreePlayerTiles.doraType(tile)]).sum();
    }

    private static void requireValid(HandInput hand, ThreePlayerWinContext facts) {
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(facts, "facts");
        boolean[] held = new boolean[136];
        for (int tile : hand.concealedTiles()) {
            ThreePlayerTiles.typeOf(tile);
            held[tile] = true;
        }
        for (Meld meld : hand.melds()) {
            if (meld.kind() == Meld.Kind.CHI) throw new IllegalArgumentException("Three-player mahjong has no chi");
            for (int tile : meld.tiles()) {
                ThreePlayerTiles.typeOf(tile);
                held[tile] = true;
            }
        }
        for (int tile : facts.extractedNorths()) {
            if (held[tile]) throw new IllegalArgumentException("An extracted north cannot also belong to the hand");
        }
        WinInterpreter.requireValidContext(hand, facts.context());
    }
}
