package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Independent Mahjong Soul four-player hand-value analysis. A result is not permission to win:
 * the caller must establish action history, a live winning window, and absence of ron furiten.
 * No game, player, wall or network state is read or changed here.
 */
public final class HandScorer {
    private static final Comparator<ScoreResult> BEST_FIRST = Comparator
            .comparingInt((ScoreResult result) -> result.points().payment().totalPoints()).reversed()
            .thenComparing(Comparator.comparingInt(ScoreResult::yakumanUnits).reversed())
            .thenComparing(Comparator.comparingInt(ScoreResult::han).reversed())
            .thenComparing(Comparator.comparingInt(ScoreResult::fu).reversed());

    private HandScorer() { }

    /** Highest actual basic payment, not the first structural form or largest han in isolation. */
    public static Optional<ScoreResult> score(HandInput hand, WinContext context) {
        return scoreAll(hand, context).stream().findFirst();
    }

    /** Every scoring explanation, sorted by actual payment; a no-yaku structure is excluded. */
    public static List<ScoreResult> scoreAll(HandInput hand, WinContext context) {
        WinInterpreter.requireValidContext(hand, context);
        List<ScoreResult> results = new ArrayList<>();
        // The official tenhou rule allows the highest-valued choice of "drawn" tile.
        List<Integer> winningTiles = context.firstTurn() == WinContext.FirstTurn.TENHOU
                ? hand.concealedTiles() : List.of(context.winningTileId());
        for (int winningTile : winningTiles) {
            WinContext selectedContext = winningTile == context.winningTileId() ? context
                    : withWinningTile(context, winningTile);
            for (WinningInterpretation interpretation : WinInterpreter.interpret(hand, winningTile)) {
                ScoreResult result = scoreInterpretation(hand, selectedContext, interpretation);
                if (result != null) results.add(result);
            }
        }
        results.sort(BEST_FIRST);
        return List.copyOf(results);
    }

    /** Public so callers can explain the numbered/honor indicator cycles without duplicating them. */
    public static int doraType(int indicatorTileId) {
        int type = TileCatalog.typeOf(indicatorTileId);
        if (type < 27) return type / 9 * 9 + (type % 9 + 1) % 9;
        if (type < 31) return 27 + (type - 27 + 1) % 4;
        return 31 + (type - 31 + 1) % 3;
    }

    private static ScoreResult scoreInterpretation(HandInput hand, WinContext context,
                                                   WinningInterpretation interpretation) {
        List<YakuAward> awards = yakuman(hand, context, interpretation);
        int units = awards.stream().mapToInt(YakuAward::yakumanUnits).sum();
        if (units > 0) {
            FuCalculator.FuResult noFu = new FuCalculator.FuResult(0, 0, List.of());
            return new ScoreResult(interpretation, context.winningTileId(), awards, 0, 0, units,
                    0, 0, 0, PointCalculator.calculate(0, 0, units, context.dealer(), context.tsumo()), noFu);
        }

        awards = ordinaryYaku(hand, context, interpretation);
        int yakuHan = awards.stream().mapToInt(YakuAward::han).sum();
        if (yakuHan == 0) return null; // Dora, aka and ura never satisfy the one-yaku requirement.
        int[] counts = hand.tileCounts();
        int dora = bonusCount(counts, context.doraIndicators());
        int ura = context.riichi() == WinContext.Riichi.NONE ? 0 : bonusCount(counts, context.uraIndicators());
        int aka = (int) hand.concealedTiles().stream().filter(TileCatalog::isRed).count();
        aka += hand.melds().stream().mapToInt(meld -> (int) meld.tiles().stream().filter(TileCatalog::isRed).count()).sum();
        int han = yakuHan + dora + aka + ura;
        FuCalculator.FuResult fu = FuCalculator.calculate(hand, context, interpretation);
        return new ScoreResult(interpretation, context.winningTileId(), awards, han, fu.fu(), 0,
                dora, aka, ura, PointCalculator.calculate(han, fu.fu(), 0, context.dealer(), context.tsumo()), fu);
    }

    private static List<YakuAward> yakuman(HandInput hand, WinContext context,
                                         WinningInterpretation interpretation) {
        List<YakuAward> awards = new ArrayList<>();
        HandDecomposition shape = interpretation.decomposition();
        int[] counts = hand.tileCounts();
        if (context.firstTurn() == WinContext.FirstTurn.TENHOU) yakuman(awards, Yaku.TENHOU, 1);
        if (context.firstTurn() == WinContext.FirstTurn.CHIIHOU) yakuman(awards, Yaku.CHIIHOU, 1);
        if (shape.form() == HandDecomposition.Form.THIRTEEN_ORPHANS) {
            int[] before = counts.clone();
            before[TileCatalog.typeOf(context.winningTileId())]--;
            boolean thirteenWait = true;
            for (int type = 0; type < counts.length; type++) {
                if (before[type] != (HandDecomposition.isOrphan(type) ? 1 : 0)) thirteenWait = false;
            }
            yakuman(awards, thirteenWait ? Yaku.KOKUSHI_THIRTEEN_WAIT : Yaku.KOKUSHI_MUSOU,
                    thirteenWait ? FourPlayerRules.SPECIAL_YAKUMAN_UNITS : 1);
        }
        if (allTypesMatch(counts, type -> type >= 27)) yakuman(awards, Yaku.TSUUIISOU, 1);
        if (allTypesMatch(counts, HandScorer::isTerminal)) yakuman(awards, Yaku.CHINROUTOU, 1);
        if (allTypesMatch(counts, type -> type == 19 || type == 20 || type == 21
                || type == 23 || type == 25 || type == 32)) yakuman(awards, Yaku.RYUUIISOU, 1);

        if (shape.form() == HandDecomposition.Form.STANDARD) {
            List<HandDecomposition.Group> groups = shape.groups();
            if (concealedTriplets(context, interpretation) == 4) {
                boolean tanki = interpretation.waitType() == WinningInterpretation.Wait.PAIR;
                yakuman(awards, tanki ? Yaku.SUUANKOU_TANKI : Yaku.SUUANKOU,
                        tanki ? FourPlayerRules.SPECIAL_YAKUMAN_UNITS : 1);
            }
            if (tripletTypes(groups, 31, 34) == 3) yakuman(awards, Yaku.DAISANGEN, 1);
            int winds = tripletTypes(groups, 27, 31);
            if (winds == 4) yakuman(awards, Yaku.DAISUUSHII, FourPlayerRules.SPECIAL_YAKUMAN_UNITS);
            else if (winds == 3 && shape.pairType() >= 27 && shape.pairType() < 31) {
                yakuman(awards, Yaku.SHOUSUUSHII, 1);
            }
            if (groups.stream().filter(group -> group.kind() == HandDecomposition.Kind.QUAD).count() == 4) {
                yakuman(awards, Yaku.SUUKANTSU, 1);
            }
        }
        if (hand.isClosed() && hand.melds().isEmpty()) {
            int suit = numberedSuit(counts);
            if (suit >= 0 && counts[27] + counts[28] + counts[29] + counts[30]
                    + counts[31] + counts[32] + counts[33] == 0) {
                int start = suit * 9;
                boolean nineGates = true;
                boolean pure = true;
                int winningType = TileCatalog.typeOf(context.winningTileId());
                for (int rank = 0; rank < 9; rank++) {
                    int minimum = rank == 0 || rank == 8 ? 3 : 1;
                    if (counts[start + rank] < minimum) nineGates = false;
                    if (counts[start + rank] - (winningType == start + rank ? 1 : 0) != minimum) pure = false;
                }
                if (nineGates) yakuman(awards, pure ? Yaku.JUNSEI_CHUUREN : Yaku.CHUUREN_POUTOU,
                        pure ? FourPlayerRules.SPECIAL_YAKUMAN_UNITS : 1);
            }
        }
        return List.copyOf(awards);
    }

    private static List<YakuAward> ordinaryYaku(HandInput hand, WinContext context,
                                               WinningInterpretation interpretation) {
        List<YakuAward> awards = new ArrayList<>();
        HandDecomposition shape = interpretation.decomposition();
        int[] counts = hand.tileCounts();
        boolean closed = hand.isClosed();
        if (context.riichi() == WinContext.Riichi.DOUBLE_RIICHI) han(awards, Yaku.DOUBLE_RIICHI, 2);
        else if (context.riichi() == WinContext.Riichi.RIICHI) han(awards, Yaku.RIICHI, 1);
        if (context.ippatsu()) han(awards, Yaku.IPPATSU, 1);
        if (closed && context.tsumo()) han(awards, Yaku.MENZEN_TSUMO, 1);
        switch (context.event()) {
            case RINSHAN -> han(awards, Yaku.RINSHAN_KAIHOU, 1);
            case CHANKAN -> han(awards, Yaku.CHANKAN, 1);
            case HAITEI -> han(awards, Yaku.HAITEI, 1);
            case HOUTEI -> han(awards, Yaku.HOUTEI, 1);
            default -> { }
        }
        if (allTypesMatch(counts, type -> !HandDecomposition.isOrphan(type))) han(awards, Yaku.TANYAO, 1);
        if (allTypesMatch(counts, HandDecomposition::isOrphan)) han(awards, Yaku.HONROUTOU, 2);

        int suit = numberedSuit(counts);
        if (suit >= 0) {
            boolean honors = false;
            for (int type = 27; type < counts.length; type++) honors |= counts[type] != 0;
            han(awards, honors ? Yaku.HONITSU : Yaku.CHINITSU, honors ? (closed ? 3 : 2) : (closed ? 6 : 5));
        }
        if (shape.form() == HandDecomposition.Form.SEVEN_PAIRS) {
            han(awards, Yaku.CHIITOITSU, 2);
            return List.copyOf(awards);
        }
        if (shape.form() != HandDecomposition.Form.STANDARD) return List.copyOf(awards);

        List<HandDecomposition.Group> groups = shape.groups();
        if (FuCalculator.isPinfu(hand, context, interpretation)) han(awards, Yaku.PINFU, 1);
        boolean[] triplets = new boolean[34];
        boolean[] sequences = new boolean[27];
        int[] sequenceCounts = new int[27];
        int sequenceCount = 0;
        int quads = 0;
        for (HandDecomposition.Group group : groups) {
            if (group.kind() == HandDecomposition.Kind.SEQUENCE) {
                sequences[group.tileType()] = true;
                sequenceCounts[group.tileType()]++;
                sequenceCount++;
            } else {
                triplets[group.tileType()] = true;
                if (group.kind() == HandDecomposition.Kind.QUAD) quads++;
            }
        }
        if (closed) {
            int sequencePairs = Arrays.stream(sequenceCounts).map(count -> count / 2).sum();
            if (sequencePairs == 2) han(awards, Yaku.RYANPEIKOU, 3);
            else if (sequencePairs == 1) han(awards, Yaku.IIPEIKOU, 1);
        }
        if (sequenceCount == 0) han(awards, Yaku.TOITOI, 2);
        if (concealedTriplets(context, interpretation) >= 3) han(awards, Yaku.SANANKOU, 2);
        if (quads >= 3) han(awards, Yaku.SANKANTSU, 2);
        if (triplets[31]) han(awards, Yaku.YAKUHAI_WHITE, 1);
        if (triplets[32]) han(awards, Yaku.YAKUHAI_GREEN, 1);
        if (triplets[33]) han(awards, Yaku.YAKUHAI_RED, 1);
        if (triplets[context.seatWind()]) han(awards, Yaku.SEAT_WIND, 1);
        if (triplets[context.roundWind()]) han(awards, Yaku.ROUND_WIND, 1);
        if (shape.pairType() >= 31 && tripletTypes(groups, 31, 34) == 2) han(awards, Yaku.SHOUSANGEN, 2);
        boolean sameSequences = false;
        boolean sameTriplets = false;
        for (int rank = 0; rank < 9; rank++) {
            if (rank < 7 && sequences[rank] && sequences[9 + rank] && sequences[18 + rank]) sameSequences = true;
            if (triplets[rank] && triplets[9 + rank] && triplets[18 + rank]) sameTriplets = true;
        }
        if (sameSequences) han(awards, Yaku.SANSHOKU_DOUJUN, closed ? 2 : 1);
        if (sameTriplets) han(awards, Yaku.SANSHOKU_DOUKOU, 2);
        boolean straight = false;
        for (int start = 0; start < 27; start += 9) {
            if (sequences[start] && sequences[start + 3] && sequences[start + 6]) straight = true;
        }
        if (straight) han(awards, Yaku.ITTSUU, closed ? 2 : 1);
        if (sequenceCount > 0 && HandDecomposition.isOrphan(shape.pairType())) {
            boolean outside = true;
            boolean honors = shape.pairType() >= 27;
            for (HandDecomposition.Group group : groups) {
                if (group.kind() == HandDecomposition.Kind.SEQUENCE) {
                    outside &= group.tileType() % 9 == 0 || group.tileType() % 9 == 6;
                } else outside &= HandDecomposition.isOrphan(group.tileType());
                honors |= group.tileType() >= 27;
            }
            if (outside) han(awards, honors ? Yaku.CHANTA : Yaku.JUNCHAN,
                    honors ? (closed ? 2 : 1) : (closed ? 3 : 2));
        }
        return List.copyOf(awards);
    }

    /** Concealed quads count; a ron-completed triplet is open only for fu and concealed-triplet yaku. */
    private static int concealedTriplets(WinContext context, WinningInterpretation interpretation) {
        int count = 0;
        List<HandDecomposition.Group> groups = interpretation.decomposition().groups();
        for (int index = 0; index < groups.size(); index++) {
            HandDecomposition.Group group = groups.get(index);
            if (group.kind() != HandDecomposition.Kind.SEQUENCE && !group.open()
                    && (context.tsumo() || interpretation.groupIndex() != index)) count++;
        }
        return count;
    }

    private static int tripletTypes(List<HandDecomposition.Group> groups, int first, int end) {
        return (int) groups.stream().filter(group -> group.kind() != HandDecomposition.Kind.SEQUENCE
                && group.tileType() >= first && group.tileType() < end).count();
    }

    /** Returns one numbered suit, or -1 for no numbered tiles or multiple suits. */
    private static int numberedSuit(int[] counts) {
        int suit = -1;
        for (int type = 0; type < 27; type++) {
            if (counts[type] != 0) {
                if (suit != -1 && suit != type / 9) return -1;
                suit = type / 9;
            }
        }
        return suit;
    }

    private static boolean allTypesMatch(int[] counts, java.util.function.IntPredicate predicate) {
        for (int type = 0; type < counts.length; type++) {
            if (counts[type] != 0 && !predicate.test(type)) return false;
        }
        return true;
    }

    private static boolean isTerminal(int type) { return type < 27 && (type % 9 == 0 || type % 9 == 8); }

    private static int bonusCount(int[] counts, List<Integer> indicators) {
        return indicators.stream().mapToInt(indicator -> counts[doraType(indicator)]).sum();
    }

    private static WinContext withWinningTile(WinContext context, int tile) {
        return new WinContext(context.method(), tile, context.seatWind(), context.roundWind(), context.riichi(),
                context.ippatsu(), context.event(), context.firstTurn(), context.doraIndicators(), context.uraIndicators());
    }

    private static void han(List<YakuAward> awards, Yaku yaku, int han) { awards.add(new YakuAward(yaku, han, 0)); }
    private static void yakuman(List<YakuAward> awards, Yaku yaku, int units) { awards.add(new YakuAward(yaku, 0, units)); }
}
