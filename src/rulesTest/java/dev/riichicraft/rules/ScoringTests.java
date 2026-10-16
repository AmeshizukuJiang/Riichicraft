package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import static dev.riichicraft.rules.WinContext.*;

/** Fixed, independently specified scoring examples; no game runtime or external rule engine. */
public final class ScoringTests {
    private static int assertions;
    private static final Set<Yaku> covered = EnumSet.noneOf(Yaku.class);

    private ScoringTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        run("winning-tile interpretation and pinfu", ScoringTests::interpretations);
        run("ordinary yaku and open reductions", ScoringTests::ordinaryYaku);
        run("context yaku, bonuses and one-yaku minimum", ScoringTests::contextAndBonuses);
        run("true yakuman, doubles and first-turn optimisation", ScoringTests::yakuman);
        run("fu and independent payment tables", ScoringTests::fuAndPayments);
        run("complete fu source matrix and scoring symmetries", ScoringTests::fuMatrixAndSymmetries);
        run("context validation and defensive copies", ScoringTests::validation);
        check(covered.equals(EnumSet.allOf(Yaku.class)), "every implemented yaku has a positive example; missing="
                + difference(EnumSet.allOf(Yaku.class), covered));
        System.out.printf("PASS scoring: %d assertions; %d yaku covered; %.3f s%n",
                assertions, covered.size(), (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static void interpretations() {
        HandInput h = hand("123456m789p345s22p");
        ScoreResult ron = score(h, normal(h, "6m", WinMethod.RON));
        expect(ron, 1, 30, 0, 1_000);
        has(ron, Yaku.PINFU);
        ScoreResult tsumo = score(h, normal(h, "6m", WinMethod.TSUMO));
        expect(tsumo, 2, 20, 0, 1_500);
        has(tsumo, Yaku.PINFU, Yaku.MENZEN_TSUMO);
        check(tsumo.points().payment().fromDealer() == 700
                && tsumo.points().payment().fromNonDealer() == 400, "20fu2han child tsumo rounds each payer");
        ScoreResult edge = score(h, context(h, "3m", WinMethod.RON, 28, 27, Riichi.RIICHI,
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of()));
        expect(edge, 1, 40, 0, 1_300);
        lacks(edge, Yaku.PINFU);
        check(edge.interpretation().waitType() == WinningInterpretation.Wait.PENCHAN, "123 completed by 3 is an edge");

        HandInput multiple = hand("11223344556677m");
        WinContext c = normal(multiple, "7m", WinMethod.TSUMO);
        List<ScoreResult> alternatives = HandScorer.scoreAll(multiple, c);
        check(alternatives.stream().anyMatch(s -> s.interpretation().decomposition().form()
                == HandDecomposition.Form.SEVEN_PAIRS), "seven-pairs alternative remains available");
        ScoreResult best = score(multiple, c);
        expect(best, 11, 20, 0, 24_000);
        has(best, Yaku.RYANPEIKOU, Yaku.CHINITSU, Yaku.PINFU, Yaku.MENZEN_TSUMO);
        lacks(best, Yaku.CHIITOITSU, Yaku.IIPEIKOU, Yaku.HONITSU);
        check(alternatives.stream().allMatch(s -> s.points().payment().totalPoints()
                <= best.points().payment().totalPoints()), "highest actual payment wins over earlier decompositions");

        HandInput ronTriplet = hand("222m444p777s55z", meld(Meld.Kind.PON, "333m"));
        ScoreResult completedTriplet = score(ronTriplet, normal(ronTriplet, "7s", WinMethod.RON));
        expect(completedTriplet, 2, 40, 0, 2_600);
        has(completedTriplet, Yaku.TOITOI);
        lacks(completedTriplet, Yaku.SANANKOU);
        ScoreResult completedPair = score(ronTriplet, normal(ronTriplet, "5z", WinMethod.RON));
        expect(completedPair, 4, 40, 0, 8_000);
        has(completedPair, Yaku.SANANKOU);
        check(completedPair.interpretation().waitType() == WinningInterpretation.Wait.PAIR,
                "winning on the pair leaves all three concealed triplets concealed");
    }

    private static void ordinaryYaku() {
        HandInput tanyao = hand("345m456p678s22p", meld(Meld.Kind.CHI, "234s"));
        ScoreResult open = score(tanyao, normal(tanyao, "6p", WinMethod.RON));
        expect(open, 1, 30, 0, 1_000);
        has(open, Yaku.TANYAO);
        lacks(open, Yaku.PINFU, Yaku.MENZEN_TSUMO);

        for (int dragon = 5; dragon <= 7; dragon++) {
            HandInput value = hand("234m567p789s22p", meld(Meld.Kind.PON, "" + dragon + dragon + dragon + "z"));
            ScoreResult result = score(value, normal(value, "4m", WinMethod.RON));
            expect(result, 1, 30, 0, 1_000);
            has(result, switch (dragon) {
                case 5 -> Yaku.YAKUHAI_WHITE;
                case 6 -> Yaku.YAKUHAI_GREEN;
                default -> Yaku.YAKUHAI_RED;
            });
        }
        HandInput doubleWind = hand("234m567p789s22p", meld(Meld.Kind.PON, "111z"));
        ScoreResult east = score(doubleWind, context(doubleWind, "4m", WinMethod.RON, 27, 27,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of()));
        expect(east, 2, 30, 0, 2_900);
        has(east, Yaku.SEAT_WIND, Yaku.ROUND_WIND);

        HandInput iipei = hand("123123m456p789s22p");
        ScoreResult twin = score(iipei, normal(iipei, "2m", WinMethod.RON));
        expect(twin, 1, 40, 0, 1_300);
        has(twin, Yaku.IIPEIKOU);

        HandInput seven = hand("1122m3344p5566s77z");
        ScoreResult pairs = score(seven, normal(seven, "7z", WinMethod.RON));
        expect(pairs, 2, 25, 0, 1_600);
        has(pairs, Yaku.CHIITOITSU);
        check(pairs.fuResult().rawFu() == 25 && pairs.fuResult().components().size() == 1,
                "seven pairs does not add ordinary pair or wait fu");

        HandInput ittsuu = hand("456789m234p55s", meld(Meld.Kind.CHI, "123m"));
        ScoreResult straight = score(ittsuu, normal(ittsuu, "3p", WinMethod.RON));
        expect(straight, 1, 30, 0, 1_000);
        has(straight, Yaku.ITTSUU);
        HandInput sanshoku = hand("123p123s456m55p", meld(Meld.Kind.CHI, "123m"));
        ScoreResult mixed = score(sanshoku, normal(sanshoku, "2p", WinMethod.RON));
        expect(mixed, 1, 30, 0, 1_000);
        has(mixed, Yaku.SANSHOKU_DOUJUN);

        HandInput chanta = hand("123m789p111s55577z");
        ScoreResult commonEnds = score(chanta, normal(chanta, "2m", WinMethod.RON));
        expect(commonEnds, 3, 50, 0, 6_400);
        has(commonEnds, Yaku.CHANTA, Yaku.YAKUHAI_WHITE);
        lacks(commonEnds, Yaku.JUNCHAN, Yaku.HONROUTOU);
        HandInput junchan = hand("123m789p111s999m11p");
        ScoreResult pureEnds = score(junchan, normal(junchan, "2m", WinMethod.RON));
        expect(pureEnds, 3, 50, 0, 6_400);
        has(pureEnds, Yaku.JUNCHAN);
        lacks(pureEnds, Yaku.CHANTA);

        HandInput honrou = hand("111999m22255z", meld(Meld.Kind.PON, "111p"));
        ScoreResult terminals = score(honrou, context(honrou, "5z", WinMethod.RON, 29, 27,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of()));
        expect(terminals, 6, 60, 0, 12_000);
        has(terminals, Yaku.HONROUTOU, Yaku.TOITOI, Yaku.SANANKOU);
        lacks(terminals, Yaku.CHANTA);

        HandInput doukou = hand("222p222s789m55z", meld(Meld.Kind.PON, "222m"));
        ScoreResult triplets = score(doukou, normal(doukou, "5z", WinMethod.RON));
        expect(triplets, 2, 40, 0, 2_600);
        has(triplets, Yaku.SANSHOKU_DOUKOU);
        HandInput littleDragons = hand("66677z456m789p", meld(Meld.Kind.PON, "555z"));
        ScoreResult small = score(littleDragons, normal(littleDragons, "5m", WinMethod.RON));
        expect(small, 4, 40, 0, 8_000);
        has(small, Yaku.SHOUSANGEN, Yaku.YAKUHAI_WHITE, Yaku.YAKUHAI_GREEN);

        HandInput honitsu = hand("123456789m55z", meld(Meld.Kind.PON, "111z"));
        ScoreResult half = score(honitsu, context(honitsu, "2m", WinMethod.RON, 29, 28,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of()));
        expect(half, 3, 30, 0, 3_900);
        has(half, Yaku.HONITSU, Yaku.ITTSUU);
        HandInput chinitsu = hand("34567899922m", meld(Meld.Kind.CHI, "123m"));
        ScoreResult flush = score(chinitsu, normal(chinitsu, "4m", WinMethod.RON));
        expect(flush, 5, 30, 0, 8_000);
        has(flush, Yaku.CHINITSU);
        lacks(flush, Yaku.HONITSU);

        HandInput threeKans = hand("456p22z", meld(Meld.Kind.OPEN_KAN, "1111m"),
                meld(Meld.Kind.OPEN_KAN, "2222p"), meld(Meld.Kind.CLOSED_KAN, "3333s"));
        ScoreResult kans = score(threeKans, context(threeKans, "5p", WinMethod.RON, 29, 27,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of()));
        expect(kans, 2, 70, 0, 4_500);
        has(kans, Yaku.SANKANTSU);
    }

    private static void contextAndBonuses() {
        HandInput h = hand("123456m789p345s22p");
        ScoreResult riichi = score(h, context(h, "6m", WinMethod.RON, 28, 27, Riichi.RIICHI,
                true, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of()));
        expect(riichi, 3, 30, 0, 3_900);
        has(riichi, Yaku.RIICHI, Yaku.IPPATSU, Yaku.PINFU);
        ScoreResult doubleRiichi = score(h, context(h, "6m", WinMethod.RON, 28, 27, Riichi.DOUBLE_RIICHI,
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of()));
        expect(doubleRiichi, 3, 30, 0, 3_900);
        has(doubleRiichi, Yaku.DOUBLE_RIICHI);
        lacks(doubleRiichi, Yaku.RIICHI);
        for (WinEvent event : List.of(WinEvent.CHANKAN, WinEvent.HOUTEI, WinEvent.HAITEI)) {
            WinMethod method = event == WinEvent.HAITEI ? WinMethod.TSUMO : WinMethod.RON;
            ScoreResult result = score(h, context(h, "6m", method, 28, 27, Riichi.NONE,
                    false, event, FirstTurn.NONE, List.of(), List.of()));
            has(result, switch (event) {
                case CHANKAN -> Yaku.CHANKAN;
                case HOUTEI -> Yaku.HOUTEI;
                default -> Yaku.HAITEI;
            });
        }
        HandInput kan = hand("123m456p789s22p", meld(Meld.Kind.CLOSED_KAN, "1111z"));
        ScoreResult rinshan = score(kan, context(kan, "6p", WinMethod.TSUMO, 28, 27, Riichi.NONE,
                false, WinEvent.RINSHAN, FirstTurn.NONE, List.of(), List.of()));
        has(rinshan, Yaku.RINSHAN_KAIHOU, Yaku.MENZEN_TSUMO, Yaku.ROUND_WIND);
        lacks(rinshan, Yaku.HAITEI);

        HandInput noYaku = hand("123m456p789s23455m");
        int indicator = unused(noYaku, 3, Set.of()); // 4m -> 5m, present twice.
        check(HandScorer.score(noYaku, context(noYaku, "3m", WinMethod.RON, 28, 27, Riichi.NONE,
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(indicator), List.of())).isEmpty(),
                "two dora cannot turn a no-yaku ron into a legal win");

        HandInput red = hand("123406m789p345s22p");
        int dora = unused(red, 3, Set.of());
        int ura = unused(red, 3, Set.of(dora));
        ScoreResult bonuses = score(red, context(red, "6m", WinMethod.RON, 28, 27, Riichi.RIICHI,
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(dora), List.of(ura)));
        expect(bonuses, 5, 30, 0, 8_000);
        check(bonuses.dora() == 1 && bonuses.aka() == 1 && bonuses.ura() == 1,
                "red five simultaneously counts red, dora and eligible ura");
        ScoreResult noRiichiUra = score(red, context(red, "6m", WinMethod.RON, 28, 27, Riichi.NONE,
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(dora), List.of(ura)));
        check(noRiichiUra.ura() == 0 && noRiichiUra.han() == 3, "ura is ignored without riichi");
        int secondDora = unused(red, 3, Set.of(dora, ura));
        ScoreResult repeated = score(red, context(red, "6m", WinMethod.RON, 28, 27, Riichi.NONE,
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(dora, secondDora), List.of()));
        check(repeated.dora() == 2, "two distinct same-type indicators count twice");
    }

    private static void yakuman() {
        HandInput standard = hand("123456m789p345s22p");
        ScoreResult heaven = score(standard, context(standard, "6m", WinMethod.TSUMO, 27, 27, Riichi.NONE,
                false, WinEvent.NORMAL, FirstTurn.TENHOU, List.of(), List.of()));
        expect(heaven, 0, 0, 1, 48_000);
        has(heaven, Yaku.TENHOU);
        ScoreResult earth = score(standard, context(standard, "6m", WinMethod.TSUMO, 28, 27, Riichi.NONE,
                false, WinEvent.NORMAL, FirstTurn.CHIIHOU, List.of(), List.of()));
        expect(earth, 0, 0, 1, 32_000);
        has(earth, Yaku.CHIIHOU);

        HandInput kokushi = hand("119m19p19s1234567z");
        ScoreResult single = score(kokushi, normal(kokushi, "1p", WinMethod.RON));
        expect(single, 0, 0, 1, 32_000);
        has(single, Yaku.KOKUSHI_MUSOU);
        ScoreResult thirteen = score(kokushi, normal(kokushi, "1m", WinMethod.RON));
        expect(thirteen, 0, 0, 2, 64_000);
        has(thirteen, Yaku.KOKUSHI_THIRTEEN_WAIT);
        lacks(thirteen, Yaku.KOKUSHI_MUSOU);
        ScoreResult heavenlyOrphans = score(kokushi, context(kokushi, "1p", WinMethod.TSUMO, 27, 27, Riichi.NONE,
                false, WinEvent.NORMAL, FirstTurn.TENHOU, List.of(), List.of()));
        expect(heavenlyOrphans, 0, 0, 3, 144_000);
        has(heavenlyOrphans, Yaku.TENHOU, Yaku.KOKUSHI_THIRTEEN_WAIT);
        check(TileCatalog.typeOf(heavenlyOrphans.winningTileId()) == 0,
                "heavenly hand selects the highest-valued physical winning tile");

        HandInput concealed = hand("111m222p333s44455z");
        ScoreResult four = score(concealed, normal(concealed, "4z", WinMethod.TSUMO));
        expect(four, 0, 0, 1, 32_000);
        has(four, Yaku.SUUANKOU);
        ScoreResult tanki = score(concealed, normal(concealed, "5z", WinMethod.RON));
        expect(tanki, 0, 0, 2, 64_000);
        has(tanki, Yaku.SUUANKOU_TANKI);
        ScoreResult ronTriplet = score(concealed, normal(concealed, "4z", WinMethod.RON));
        check(ronTriplet.yakumanUnits() == 0, "ron completing the fourth triplet is not four concealed triplets");
        has(ronTriplet, Yaku.SANANKOU, Yaku.TOITOI);

        HandInput gates = hand("11123456789995m");
        ScoreResult ordinaryGates = score(gates, normal(gates, "1m", WinMethod.RON));
        expect(ordinaryGates, 0, 0, 1, 32_000);
        has(ordinaryGates, Yaku.CHUUREN_POUTOU);
        ScoreResult pureGates = score(gates, normal(gates, "5m", WinMethod.RON));
        expect(pureGates, 0, 0, 2, 64_000);
        has(pureGates, Yaku.JUNSEI_CHUUREN);
        ScoreResult heavenlyGates = score(gates, context(gates, "1m", WinMethod.TSUMO, 27, 27, Riichi.NONE,
                false, WinEvent.NORMAL, FirstTurn.TENHOU, List.of(), List.of()));
        expect(heavenlyGates, 0, 0, 3, 144_000);
        has(heavenlyGates, Yaku.TENHOU, Yaku.JUNSEI_CHUUREN);

        HandInput bigDragons = hand("123m22p", meld(Meld.Kind.PON, "555z"),
                meld(Meld.Kind.PON, "666z"), meld(Meld.Kind.PON, "777z"));
        ScoreResult dragons = score(bigDragons, normal(bigDragons, "2m", WinMethod.RON));
        expect(dragons, 0, 0, 1, 32_000);
        has(dragons, Yaku.DAISANGEN);
        HandInput smallWinds = hand("111m44z", meld(Meld.Kind.PON, "111z"),
                meld(Meld.Kind.PON, "222z"), meld(Meld.Kind.PON, "333z"));
        ScoreResult winds = score(smallWinds, normal(smallWinds, "4z", WinMethod.RON));
        expect(winds, 0, 0, 1, 32_000);
        has(winds, Yaku.SHOUSUUSHII);
        HandInput bigWinds = hand("55z", meld(Meld.Kind.PON, "111z"), meld(Meld.Kind.PON, "222z"),
                meld(Meld.Kind.PON, "333z"), meld(Meld.Kind.PON, "444z"));
        ScoreResult allWinds = score(bigWinds, normal(bigWinds, "5z", WinMethod.RON));
        expect(allWinds, 0, 0, 3, 96_000);
        has(allWinds, Yaku.DAISUUSHII, Yaku.TSUUIISOU);
        lacks(allWinds, Yaku.SHOUSUUSHII);
        HandInput honors = hand("11223344556677z");
        ScoreResult allHonors = score(honors, normal(honors, "7z", WinMethod.RON));
        expect(allHonors, 0, 0, 1, 32_000);
        has(allHonors, Yaku.TSUUIISOU);
        lacks(allHonors, Yaku.CHIITOITSU);
        HandInput terminals = hand("999m111999p11s", meld(Meld.Kind.PON, "111m"));
        ScoreResult allTerminals = score(terminals, normal(terminals, "9p", WinMethod.RON));
        expect(allTerminals, 0, 0, 1, 32_000);
        has(allTerminals, Yaku.CHINROUTOU);
        HandInput green = hand("33344466688s", meld(Meld.Kind.PON, "222s"));
        ScoreResult allGreen = score(green, normal(green, "3s", WinMethod.RON));
        expect(allGreen, 0, 0, 1, 32_000);
        has(allGreen, Yaku.RYUUIISOU);
        HandInput fourKans = hand("55z", meld(Meld.Kind.OPEN_KAN, "1111m"),
                meld(Meld.Kind.OPEN_KAN, "2222p"), meld(Meld.Kind.OPEN_KAN, "3333s"),
                meld(Meld.Kind.OPEN_KAN, "4444z"));
        ScoreResult kans = score(fourKans, normal(fourKans, "5z", WinMethod.RON));
        expect(kans, 0, 0, 1, 32_000);
        has(kans, Yaku.SUUKANTSU);
        HandInput sixfold = hand("55z", meld(Meld.Kind.CLOSED_KAN, "1111z"),
                meld(Meld.Kind.CLOSED_KAN, "2222z"), meld(Meld.Kind.CLOSED_KAN, "3333z"),
                meld(Meld.Kind.CLOSED_KAN, "4444z"));
        ScoreResult six = score(sixfold, normal(sixfold, "5z", WinMethod.RON));
        expect(six, 0, 0, 6, 192_000);
        has(six, Yaku.DAISUUSHII, Yaku.SUUANKOU_TANKI, Yaku.TSUUIISOU, Yaku.SUUKANTSU);
    }

    private static void fuAndPayments() {
        // Rows: han, fu, nondealer ron, dealer ron, child tsumo's other/dealer, dealer tsumo each.
        int[][] table = {
                {1,30,1000,1500,300,500,500}, {1,40,1300,2000,400,700,700},
                {1,50,1600,2400,400,800,800}, {1,60,2000,2900,500,1000,1000},
                {1,70,2300,3400,600,1200,1200}, {1,80,2600,3900,700,1300,1300},
                {1,90,2900,4400,800,1500,1500}, {1,100,3200,4800,800,1600,1600},
                {1,110,3600,5300,900,1800,1800}, {2,20,1300,2000,400,700,700},
                {2,25,1600,2400,400,800,800}, {2,30,2000,2900,500,1000,1000},
                {2,40,2600,3900,700,1300,1300}, {2,50,3200,4800,800,1600,1600},
                {2,60,3900,5800,1000,2000,2000}, {2,70,4500,6800,1200,2300,2300},
                {2,80,5200,7700,1300,2600,2600}, {2,90,5800,8700,1500,2900,2900},
                {2,100,6400,9600,1600,3200,3200}, {2,110,7100,10600,1800,3600,3600},
                {3,20,2600,3900,700,1300,1300}, {3,25,3200,4800,800,1600,1600},
                {3,30,3900,5800,1000,2000,2000}, {3,40,5200,7700,1300,2600,2600},
                {3,50,6400,9600,1600,3200,3200}, {3,60,7700,11600,2000,3900,3900},
                {3,70,8000,12000,2000,4000,4000}, {4,20,5200,7700,1300,2600,2600},
                {4,25,6400,9600,1600,3200,3200}, {4,30,7700,11600,2000,3900,3900},
                {4,40,8000,12000,2000,4000,4000}, {5,30,8000,12000,2000,4000,4000},
                {6,30,12000,18000,3000,6000,6000}, {7,30,12000,18000,3000,6000,6000},
                {8,30,16000,24000,4000,8000,8000}, {10,30,16000,24000,4000,8000,8000},
                {11,30,24000,36000,6000,12000,12000}, {12,30,24000,36000,6000,12000,12000},
                {13,30,32000,48000,8000,16000,16000}, {26,30,32000,48000,8000,16000,16000}
        };
        for (int[] row : table) {
            // The official table leaves ron at 20 fu and some low-han tsumo cells blank.
            // Do not present synthetic arithmetic combinations as reachable winning hands.
            if (row[1] != 20) {
                check(PointCalculator.calculate(row[0], row[1], 0, false, false).payment().ronPoints() == row[2], "child ron table " + Arrays.toString(row));
                check(PointCalculator.calculate(row[0], row[1], 0, true, false).payment().ronPoints() == row[3], "dealer ron table " + Arrays.toString(row));
            }
            if (row[1] == 25 && row[0] == 2 || row[1] == 110 && row[0] < 3) continue;
            PointCalculator.Payment child = PointCalculator.calculate(row[0], row[1], 0, false, true).payment();
            PointCalculator.Payment dealer = PointCalculator.calculate(row[0], row[1], 0, true, true).payment();
            check(child.fromNonDealer() == row[4] && child.fromDealer() == row[5]
                    && child.totalPoints() == 2 * row[4] + row[5], "child tsumo table " + Arrays.toString(row));
            check(dealer.fromDealer() == 0 && dealer.fromNonDealer() == row[6]
                    && dealer.totalPoints() == 3 * row[6], "dealer tsumo table " + Arrays.toString(row));
        }
        check(PointCalculator.calculate(4, 30, 0, false, false).basePoints() == 1920, "no kiriage mangan");
        check(PointCalculator.calculate(13, 30, 0, false, false).limit() == PointCalculator.Limit.KAZOE_YAKUMAN,
                "counted yakuman distinguished from real yakuman");
        for (int units = 1; units <= 6; units++) {
            check(PointCalculator.calculate(0, 0, units, false, false).payment().ronPoints() == units * 32_000,
                    "real yakuman stacks rather than counted-yakuman cap");
            check(PointCalculator.calculate(0, 0, units, true, true).payment().totalPoints() == units * 48_000,
                    "dealer real yakuman stack");
        }
        illegal(() -> PointCalculator.calculate(0, 30, 0, false, false), "zero-han ordinary score");
        illegal(() -> PointCalculator.calculate(-1, 30, 0, false, false), "negative han");
        illegal(() -> PointCalculator.calculate(1, 22, 0, false, false), "unrounded fu");
        illegal(() -> PointCalculator.calculate(0, 0, Integer.MAX_VALUE, true, true), "overflowing yakuman payment");

        HandInput windPair = hand("123m456p789s234m11z");
        WinContext c = context(windPair, "3m", WinMethod.RON, 27, 27, Riichi.RIICHI,
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
        ScoreResult doubleWindPair = score(windPair, c);
        check(doubleWindPair.fuResult().rawFu() == 36 && doubleWindPair.fu() == 40,
                "double-wind pair four fu plus edge/closed wait two fu");
        lacks(doubleWindPair, Yaku.PINFU, Yaku.SEAT_WIND, Yaku.ROUND_WIND);

        HandInput nonPinfuTsumo = hand("111789m234p567s55z");
        ScoreResult selfDraw = score(nonPinfuTsumo, normal(nonPinfuTsumo, "4p", WinMethod.TSUMO));
        expect(selfDraw, 1, 40, 0, 1_500);
        check(selfDraw.fuResult().rawFu() == 32
                        && selfDraw.points().payment().fromNonDealer() == 400
                        && selfDraw.points().payment().fromDealer() == 700,
                "non-pinfu tsumo adds two fu and crosses the thirty-fu boundary");
        lacks(selfDraw, Yaku.PINFU);
    }

    private static void fuMatrixAndSymmetries() {
        int[][] expectedGroupFu = {{2,4}, {4,8}, {8,16}, {16,32}};
        for (int kind = 0; kind < 4; kind++) {
            for (int terminal = 0; terminal < 2; terminal++) {
                String rank = terminal == 1 ? "1" : "2";
                HandInput h = kind == 1
                        ? hand(rank.repeat(3) + "m456p789234s22p")
                        : hand("456p789234s22p", meld(switch (kind) {
                            case 0 -> Meld.Kind.PON;
                            case 2 -> Meld.Kind.OPEN_KAN;
                            default -> Meld.Kind.CLOSED_KAN;
                        }, rank.repeat(kind < 2 ? 3 : 4) + "m"));
                WinContext c = normal(h, "5p", WinMethod.RON);
                WinningInterpretation i = WinInterpreter.interpret(h, c.winningTileId()).getFirst();
                FuCalculator.FuResult fu = FuCalculator.calculate(h, c, i);
                int expected = 20 + 2 + expectedGroupFu[kind][terminal] + (kind == 1 || kind == 3 ? 10 : 0);
                check(fu.rawFu() == expected && fu.fu() == (expected + 9) / 10 * 10,
                        "all eight open/closed triplet/quad terminal/simple fu values");
                check(fu.components().stream().mapToInt(FuCalculator.FuComponent::fu).sum() == fu.rawFu(),
                        "fu source breakdown adds to raw fu");
            }
        }
        HandInput closedKan = hand("222m333p44z", meld(Meld.Kind.CLOSED_KAN, "1111s"),
                meld(Meld.Kind.CHI, "789m"));
        ScoreResult threeConcealed = score(closedKan, normal(closedKan, "4z", WinMethod.RON));
        has(threeConcealed, Yaku.SANANKOU);
        lacks(threeConcealed, Yaku.SUUANKOU, Yaku.SUUANKOU_TANKI);
        HandInput heavenlyTriplets = hand("111m222p333s44455z");
        ScoreResult heaven = score(heavenlyTriplets, context(heavenlyTriplets, "4z", WinMethod.TSUMO,
                27, 27, Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.TENHOU, List.of(), List.of()));
        expect(heaven, 0, 0, 3, 144_000);
        has(heaven, Yaku.SUUANKOU_TANKI, Yaku.TENHOU);

        // A physical tile kind may appear in a pair and in sequences within the same hand.
        HandInput ambiguous = hand("11122233344455m");
        Set<WinningInterpretation.Wait> waitKinds = EnumSet.noneOf(WinningInterpretation.Wait.class);
        WinInterpreter.interpret(ambiguous, winningId(ambiguous, "2m")).forEach(i -> waitKinds.add(i.waitType()));
        check(waitKinds.contains(WinningInterpretation.Wait.PAIR)
                        && waitKinds.contains(WinningInterpretation.Wait.SHANPON), "winning kind may complete the pair or a triplet");
        check(waitKinds.contains(WinningInterpretation.Wait.KANCHAN), "the same winning kind can also complete a sequence");

        // Number-suit permutation leaves role values and fu unchanged, including red copy identity.
        String[] samples = {"123456m789p345s22p", "111222333m456p55s", "11223344556677m", "111m222p333s44455z"};
        for (String sample : samples) {
            HandInput h = hand(sample);
            for (int tile : h.concealedTiles()) {
                WinContext c = new WinContext(WinMethod.TSUMO, tile, 28, 27, Riichi.NONE, false,
                        WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
                ScoreResult original = score(h, c);
                List<Integer> changed = h.concealedTiles().stream().map(ScoringTests::rotateSuit).toList();
                HandInput moved = new HandInput(changed, List.of());
                WinContext mc = new WinContext(WinMethod.TSUMO, rotateSuit(tile), 28, 27, Riichi.NONE,
                        false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
                ScoreResult transformed = score(moved, mc);
                check(original.han() == transformed.han() && original.fu() == transformed.fu()
                                && original.yakumanUnits() == transformed.yakumanUnits()
                                && original.points().equals(transformed.points()), "number-suit scoring symmetry");
            }
        }
        for (int rank = 0; rank < 9; rank++) {
            for (int suit = 0; suit < 3; suit++) {
                check(HandScorer.doraType((suit * 9 + rank) * 4) == suit * 9 + (rank + 1) % 9,
                        "all numbered dora cycles including nine-to-one");
            }
        }
        int[] honors = {28,29,30,27,32,33,31};
        for (int index = 0; index < honors.length; index++)
            check(HandScorer.doraType((27 + index) * 4) == honors[index], "wind/dragon dora cycles");
    }

    private static int rotateSuit(int physicalId) {
        int type = TileCatalog.typeOf(physicalId);
        return type >= 27 ? physicalId : ((type / 9 + 1) % 3 * 9 + type % 9) * 4 + physicalId % 4;
    }

    private static void validation() {
        HandInput h = hand("123456m789p345s22p");
        int win = winningId(h, "6m");
        illegal(() -> new WinContext(WinMethod.RON, win, 28, 27, Riichi.NONE, true,
                WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of()), "ippatsu without riichi");
        illegal(() -> new WinContext(WinMethod.RON, win, 28, 27, Riichi.NONE, false,
                WinEvent.RINSHAN, FirstTurn.NONE, List.of(), List.of()), "ron cannot be rinshan");
        illegal(() -> new WinContext(WinMethod.TSUMO, win, 28, 27, Riichi.NONE, false,
                WinEvent.NORMAL, FirstTurn.TENHOU, List.of(), List.of()), "child cannot have tenhou");
        illegal(() -> new WinContext(WinMethod.RON, win, 31, 27, Riichi.NONE, false,
                WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of()), "dragon is not a seat wind");
        int d = unused(h, 3, Set.of());
        illegal(() -> new WinContext(WinMethod.RON, win, 28, 27, Riichi.NONE, false,
                WinEvent.NORMAL, FirstTurn.NONE, List.of(d, d), List.of()), "duplicate physical indicator");
        illegal(() -> HandScorer.score(h, context(h, "6m", WinMethod.RON, 28, 27, Riichi.NONE,
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(h.concealedTiles().getFirst()), List.of())),
                "indicator cannot also be in hand");
        HandInput open = hand("345m456p678s22p", meld(Meld.Kind.CHI, "234s"));
        illegal(() -> HandScorer.score(open, context(open, "6p", WinMethod.RON, 28, 27, Riichi.RIICHI,
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of())), "open riichi rejected");
        illegal(() -> HandScorer.score(h, context(h, "6m", WinMethod.TSUMO, 28, 27, Riichi.NONE,
                false, WinEvent.RINSHAN, FirstTurn.NONE, List.of(), List.of())), "rinshan without a kan");
        illegal(() -> WinInterpreter.interpret(h, unused(h, 5, Set.of())), "winning physical tile must be concealed");
        WinningInterpretation other = WinInterpreter.interpret(hand("111m222p333s44455z"), 0).getFirst();
        illegal(() -> FuCalculator.calculate(h, normal(h, "6m", WinMethod.RON), other), "forged interpretation rejected");

        List<Integer> indicators = new ArrayList<>(List.of(d));
        WinContext copied = context(h, "6m", WinMethod.RON, 28, 27, Riichi.NONE,
                false, WinEvent.NORMAL, FirstTurn.NONE, indicators, List.of());
        indicators.clear();
        check(copied.doraIndicators().size() == 1, "context copies indicator input");
        immutable(() -> copied.doraIndicators().clear(), "context indicator output immutable");
        ScoreResult result = score(h, normal(h, "6m", WinMethod.RON));
        immutable(() -> result.yaku().clear(), "yaku output immutable");
        immutable(() -> result.fuResult().components().clear(), "fu explanation immutable");
        immutable(() -> HandScorer.scoreAll(h, normal(h, "6m", WinMethod.RON)).clear(), "candidate scores immutable");
        immutable(() -> WinInterpreter.interpret(h, win).clear(), "interpretations immutable");
    }

    private record MeldSpec(Meld.Kind kind, String notation) { }
    private static MeldSpec meld(Meld.Kind kind, String notation) { return new MeldSpec(kind, notation); }

    private static HandInput hand(String concealed, MeldSpec... specs) {
        boolean[] used = new boolean[136];
        List<Meld> melds = new ArrayList<>();
        for (MeldSpec spec : specs) melds.add(new Meld(spec.kind(), tiles(spec.notation(), used)));
        return new HandInput(tiles(concealed, used), melds);
    }

    private static List<Integer> tiles(String notation, boolean[] used) {
        List<Integer> out = new ArrayList<>();
        StringBuilder ranks = new StringBuilder();
        for (char ch : notation.toCharArray()) {
            if (Character.isDigit(ch)) { ranks.append(ch); continue; }
            int offset = switch (ch) { case 'm' -> 0; case 'p' -> 9; case 's' -> 18; case 'z' -> 27;
                default -> throw new AssertionError("Bad notation: " + notation); };
            for (char digit : ranks.toString().toCharArray()) {
                int rank = digit == '0' ? 5 : digit - '0';
                if (rank < 1 || rank > (ch == 'z' ? 7 : 9) || digit == '0' && ch == 'z')
                    throw new AssertionError("Bad rank: " + notation);
                int type = offset + rank - 1;
                int selected = -1;
                int[] copies = digit == '0' ? new int[]{0} : type < 27 && type % 9 == 4
                        ? new int[]{1, 2, 3, 0} : new int[]{0, 1, 2, 3};
                for (int copy : copies) if (!used[type * 4 + copy]) { selected = type * 4 + copy; break; }
                if (selected < 0) throw new AssertionError("Too many copies: " + notation);
                used[selected] = true;
                out.add(selected);
            }
            ranks.setLength(0);
        }
        if (!ranks.isEmpty()) throw new AssertionError("Missing suit: " + notation);
        return out;
    }

    private static int winningId(HandInput h, String tile) {
        int type = TileCatalog.typeOf(tiles(tile, new boolean[136]).getFirst());
        return h.concealedTiles().stream().filter(id -> TileCatalog.typeOf(id) == type
                && (tile.charAt(0) != '0' || TileCatalog.isRed(id))).findFirst().orElseThrow();
    }

    private static WinContext normal(HandInput h, String tile, WinMethod method) {
        return context(h, tile, method, 28, 27, Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
    }

    private static WinContext context(HandInput h, String tile, WinMethod method, int seat, int round,
                                      Riichi riichi, boolean ippatsu, WinEvent event, FirstTurn first,
                                      List<Integer> dora, List<Integer> ura) {
        return new WinContext(method, winningId(h, tile), seat, round, riichi, ippatsu, event, first, dora, ura);
    }

    private static int unused(HandInput h, int type, Set<Integer> reserved) {
        Set<Integer> used = new java.util.HashSet<>(h.concealedTiles());
        h.melds().forEach(m -> used.addAll(m.tiles()));
        for (int copy = 0; copy < 4; copy++) {
            int id = type * 4 + copy;
            if (!used.contains(id) && !reserved.contains(id)) return id;
        }
        throw new AssertionError("No unused indicator of type " + type);
    }

    private static ScoreResult score(HandInput h, WinContext c) {
        ScoreResult result = HandScorer.score(h, c).orElseThrow(() -> new AssertionError("Expected scoring hand " + h + "; " + c));
        result.yaku().forEach(a -> covered.add(a.yaku()));
        if (result.yakumanUnits() > 0) {
            check(result.han() == 0 && result.fu() == 0 && result.dora() == 0 && result.aka() == 0 && result.ura() == 0,
                    "real yakuman excludes ordinary han/fu and all bonuses");
        } else check(result.yaku().stream().anyMatch(a -> a.han() > 0), "a scored ordinary hand has an actual yaku");
        return result;
    }

    private static void expect(ScoreResult r, int han, int fu, int units, int total) {
        check(r.han() == han && r.fu() == fu && r.yakumanUnits() == units && r.points().payment().totalPoints() == total,
                "expected " + han + "han " + fu + "fu " + units + "yakuman " + total + "; actual=" + r);
    }

    private static void has(ScoreResult r, Yaku... names) {
        for (Yaku name : names) check(r.yaku().stream().anyMatch(a -> a.yaku() == name), "missing " + name + ": " + r);
    }
    private static void lacks(ScoreResult r, Yaku... names) {
        for (Yaku name : names) check(r.yaku().stream().noneMatch(a -> a.yaku() == name), "unexpected " + name + ": " + r);
    }
    private static <T> Set<T> difference(Set<T> all, Set<T> have) {
        Set<T> result = new java.util.HashSet<>(all); result.removeAll(have); return result;
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void illegal(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected invalid input: " + message);
    }
    private static void immutable(Runnable action, String message) {
        try { action.run(); } catch (UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError("Expected immutable result: " + message);
    }
    private static void run(String label, Runnable test) {
        int before = assertions; test.run(); System.out.printf("  PASS %s (%d assertions)%n", label, assertions - before);
    }
}
