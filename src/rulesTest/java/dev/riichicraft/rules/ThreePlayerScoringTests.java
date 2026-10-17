package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.riichicraft.rules.ThreePlayerWinContext.NorthEvent.*;
import static dev.riichicraft.rules.WinContext.*;

/** Independently specified sanma examples plus physical-indicator and four-player regression checks. */
public final class ThreePlayerScoringTests {
    private static int assertions;

    private ThreePlayerScoringTests() { }

    public static void main(String[] args) {
        run("basic hands, north in hand, and actual two-payer selection", ThreePlayerScoringTests::basicHands);
        run("kita supplement and robbery are different scoring events", ThreePlayerScoringTests::northEvents);
        run("bonus stacking, one-yaku minimum, and physical indicator matrix", ThreePlayerScoringTests::bonuses);
        run("true/counting yakuman and first-turn optimisation", ThreePlayerScoringTests::yakuman);
        run("shared yaku/fu parity and physical copy symmetry", ThreePlayerScoringTests::parity);
        run("invalid inputs and defensive copies", ThreePlayerScoringTests::validation);
        System.out.printf("PASS three-player scoring: %d assertions%n", assertions);
    }

    private static void basicHands() {
        HandInput h = hand("123456p789s345s22s");
        ThreePlayerScoreResult ron = score(h, facts(h, "6p", WinMethod.RON));
        expect(ron, 1, 30, 0, 1_000);
        has(ron, Yaku.PINFU);
        ThreePlayerScoreResult tsumo = score(h, facts(h, "6p", WinMethod.TSUMO));
        expect(tsumo, 2, 20, 0, 1_100);
        check(tsumo.points().payment().fromDealer() == 700
                && tsumo.points().payment().fromNonDealer() == 400, "only two actual payers");
        ThreePlayerScoreResult dealer = score(h, facts(h, "6p", WinMethod.TSUMO, 27, 27,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of(), List.of(), NONE));
        expect(dealer, 2, 20, 0, 1_400);

        HandInput northPair = hand("123456p789s345s44z");
        ThreePlayerScoreResult pair = score(northPair, facts(northPair, "6p", WinMethod.RON));
        expect(pair, 1, 30, 0, 1_000);
        has(pair, Yaku.PINFU);
        check(pair.kita() == 0 && pair.fuResult().rawFu() == 30, "held north is valueless pair, not kita");
        HandInput northPon = hand("123p456s789s22p", meld(Meld.Kind.PON, "444z"));
        check(ThreePlayerHandScorer.score(northPon, facts(northPon, "6s", WinMethod.RON)).isEmpty(),
                "north pon is not a value-wind yaku");
        HandInput northTriplet = hand("444z123p456p789s22s");
        ThreePlayerScoreResult triplet = score(northTriplet, facts(northTriplet, "6p", WinMethod.TSUMO));
        expect(triplet, 1, 30, 0, 800);
        has(triplet, Yaku.MENZEN_TSUMO);
        check(triplet.fuResult().components().contains(new FuCalculator.FuComponent(
                FuCalculator.Source.CLOSED_TRIPLET, 8)), "north is an ordinary honor triplet for fu");

        HandInput multiple = hand("11223344556677p");
        List<ThreePlayerScoreResult> candidates = ThreePlayerHandScorer.scoreAll(multiple,
                facts(multiple, "7p", WinMethod.TSUMO));
        check(candidates.stream().anyMatch(s -> s.interpretation().decomposition().form()
                == HandDecomposition.Form.SEVEN_PAIRS), "keep seven-pairs alternative");
        ThreePlayerScoreResult best = candidates.getFirst();
        expect(best, 11, 20, 0, 18_000);
        has(best, Yaku.RYANPEIKOU, Yaku.CHINITSU, Yaku.PINFU, Yaku.MENZEN_TSUMO);
        for (ThreePlayerScoreResult candidate : candidates) {
            check(candidate.points().payment().totalPoints() <= best.points().payment().totalPoints(),
                    "maximise actual three-player payment across every interpretation");
        }
    }

    private static void northEvents() {
        HandInput open = hand("123p456s789s22p", meld(Meld.Kind.PON, "999p"));
        List<Integer> north = List.of(120);
        check(ThreePlayerHandScorer.score(open, withNorth(facts(open, "6s", WinMethod.TSUMO), north, NONE)).isEmpty(),
                "normal open tsumo with kita alone has no yaku");
        ThreePlayerScoreResult supplemental = score(open,
                withNorth(facts(open, "6s", WinMethod.TSUMO), north, SUPPLEMENT));
        expect(supplemental, 2, 30, 0, 1_500);
        has(supplemental, Yaku.RINSHAN_KAIHOU);
        lacks(supplemental, Yaku.MENZEN_TSUMO, Yaku.CHANKAN, Yaku.IPPATSU);
        check(supplemental.kita() == 1 && supplemental.fuResult().components().contains(
                new FuCalculator.FuComponent(FuCalculator.Source.TSUMO, 2)), "no fictitious kan or kan fu");

        HandInput closed = hand("123456p789s345s22s");
        ThreePlayerScoreResult kita = score(closed,
                withNorth(facts(closed, "6p", WinMethod.TSUMO), north, SUPPLEMENT));
        expect(kita, 4, 20, 0, 3_900);
        has(kita, Yaku.MENZEN_TSUMO, Yaku.PINFU, Yaku.RINSHAN_KAIHOU);
        ThreePlayerScoreResult doubleRiichi = score(closed, facts(closed, "6p", WinMethod.TSUMO,
                28, 27, Riichi.DOUBLE_RIICHI, false, WinEvent.NORMAL, FirstTurn.NONE,
                List.of(), List.of(), north, SUPPLEMENT));
        has(doubleRiichi, Yaku.DOUBLE_RIICHI);
        lacks(doubleRiichi, Yaku.IPPATSU);

        HandInput robbery = hand("123456p789s345s44z");
        check(ThreePlayerHandScorer.score(robbery, withNorth(facts(robbery, "4z", WinMethod.RON),
                List.of(), ROBBERY)).isEmpty(), "rob north alone supplies no chankan yaku");
        ThreePlayerScoreResult ippatsu = score(robbery, facts(robbery, "4z", WinMethod.RON,
                28, 27, Riichi.RIICHI, true, WinEvent.NORMAL, FirstTurn.NONE,
                List.of(), List.of(), List.of(), ROBBERY));
        expect(ippatsu, 2, 40, 0, 2_600);
        has(ippatsu, Yaku.RIICHI, Yaku.IPPATSU);
        lacks(ippatsu, Yaku.CHANKAN, Yaku.RINSHAN_KAIHOU);
        check(ippatsu.kita() == 0, "robbed north belongs to completed hand, not established kita");
        HandInput pairs = hand("11m22p33p44s55s66z44z");
        ThreePlayerScoreResult seven = score(pairs,
                withNorth(facts(pairs, "4z", WinMethod.RON), List.of(), ROBBERY));
        expect(seven, 2, 25, 0, 1_600);
        has(seven, Yaku.CHIITOITSU);
        lacks(seven, Yaku.CHANKAN);

        HandInput kan = hand("123p456s789s22p", meld(Meld.Kind.CLOSED_KAN, "1111m"));
        ThreePlayerScoreResult normalKan = score(kan, facts(kan, "6s", WinMethod.TSUMO,
                28, 27, Riichi.NONE, false, WinEvent.RINSHAN, FirstTurn.NONE,
                List.of(), List.of(), List.of(), NONE));
        has(normalKan, Yaku.RINSHAN_KAIHOU);
        check(normalKan.kita() == 0 && normalKan.fuResult().components().contains(
                new FuCalculator.FuComponent(FuCalculator.Source.CLOSED_QUAD, 32)), "real kan keeps quad fu");
        ThreePlayerScoreResult northAfterKan = score(kan,
                withNorth(facts(kan, "6s", WinMethod.TSUMO), List.of(120, 121), SUPPLEMENT));
        check(northAfterKan.yaku().stream().filter(a -> a.yaku() == Yaku.RINSHAN_KAIHOU).count() == 1,
                "a prior kan does not duplicate rinshan yaku for kita");
    }

    private static void bonuses() {
        HandInput noYaku = hand("123p406s789s22p", meld(Meld.Kind.PON, "999p"));
        check(ThreePlayerHandScorer.score(noYaku, facts(noYaku, "6s", WinMethod.RON, 28, 27,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(85), List.of(86),
                List.of(120, 121, 122, 123), NONE)).isEmpty(), "dora, red, and four kita cannot supply a yaku");

        HandInput h = hand("123456p789s345s44z");
        List<Integer> north = List.of(122, 123);
        ThreePlayerScoreResult stacked = score(h, facts(h, "6p", WinMethod.RON, 28, 27,
                Riichi.RIICHI, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(116, 117), List.of(118, 119),
                north, NONE));
        check(stacked.dora() == 8 && stacked.ura() == 8 && stacked.kita() == 2 && stacked.han() == 20,
                "two held and two extracted norths each count once per west indicator, plus kita itself");
        check(stacked.yakumanUnits() == 0 && stacked.points().limit() == PointCalculator.Limit.KAZOE_YAKUMAN,
                "13+ ordinary han caps at one counted yakuman");
        ThreePlayerScoreResult noRiichi = score(h, facts(h, "6p", WinMethod.RON, 28, 27,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(116), List.of(117), north, NONE));
        check(noRiichi.dora() == 4 && noRiichi.ura() == 0 && noRiichi.kita() == 2 && noRiichi.han() == 7,
                "provided ura is ineffective without established riichi");

        HandInput red = hand("123406p789s340s22s");
        ThreePlayerScoreResult aka = score(red, facts(red, "6p", WinMethod.RON));
        check(aka.aka() == 2 && aka.han() == 3, "both legal red fives count");
        HandInput redPon = hand("123p456s789s22p", meld(Meld.Kind.PON, "055p"));
        ThreePlayerScoreResult openRed = score(redPon,
                withNorth(facts(redPon, "6s", WinMethod.TSUMO), List.of(120), SUPPLEMENT));
        check(openRed.aka() == 1 && openRed.kita() == 1, "red in declared pon counts separately");

        HandInput redKan = hand("123p456s789s22p", meld(Meld.Kind.CLOSED_KAN, "5555p"));
        ThreePlayerScoreResult quad = score(redKan, facts(redKan, "6s", WinMethod.TSUMO,
                28, 27, Riichi.NONE, false, WinEvent.RINSHAN, FirstTurn.NONE,
                List.of(48), List.of(), List.of(120), NONE));
        expect(quad, 8, 40, 0, 12_000);
        check(quad.dora() == 4 && quad.aka() == 1 && quad.kita() == 1,
                "all four physical tiles of a kan count as dora, with one red and separate kita");
        HandInput northKan = hand("123p456s789s22p", meld(Meld.Kind.CLOSED_KAN, "4444z"));
        ThreePlayerScoreResult northQuad = score(northKan, facts(northKan, "6s", WinMethod.TSUMO,
                28, 27, Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE,
                List.of(116), List.of(), List.of(), NONE));
        check(northQuad.dora() == 4 && northQuad.kita() == 0,
                "north kan is four ordinary dora tiles, never four kita bonuses");

        // Independent expected cycle, over every physically available sanma indicator.
        Set<Integer> held = held(h);
        held.addAll(north);
        int checked = 0;
        for (int indicator : ThreePlayerTiles.allTiles()) {
            if (held.contains(indicator)) continue;
            int type = indicator / 4;
            int target = type == 0 ? 8 : type == 8 ? 0 : type < 27
                    ? type / 9 * 9 + (type % 9 + 1) % 9 : type < 31 ? 27 + (type - 26) % 4
                    : 31 + (type - 30) % 3;
            int expected = (int) h.concealedTiles().stream().filter(id -> id / 4 == target).count()
                    + (target == 30 ? north.size() : 0);
            ThreePlayerScoreResult r = score(h, facts(h, "6p", WinMethod.RON, 28, 27,
                    Riichi.RIICHI, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(indicator), List.of(), north, NONE));
            check(r.dora() == expected && r.ura() == 0 && r.kita() == 2 && r.han() == 4 + expected,
                    "independent indicator mapping and kita stacking for physical id " + indicator);
            checked++;
        }
        check(checked == 92, "108 minus fourteen held minus two extracted physical indicators tested");

        HandInput terminals = hand("111999m123p456s22p");
        for (int indicator : List.of(3, 35)) {
            ThreePlayerScoreResult r = score(terminals, facts(terminals, "6s", WinMethod.TSUMO, 28, 27,
                    Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(indicator), List.of(), List.of(), NONE));
            check(r.dora() == 3, "1m and 9m indicators wrap directly to the other terminal");
        }
    }

    private static void yakuman() {
        HandInput kokushi = hand("19m19p19s12345677z");
        ThreePlayerScoreResult robbed = score(kokushi,
                withNorth(facts(kokushi, "4z", WinMethod.RON), List.of(121), ROBBERY));
        expect(robbed, 0, 0, 1, 32_000);
        has(robbed, Yaku.KOKUSHI_MUSOU);
        check(robbed.kita() == 0, "true yakuman ignores even established kita bonus");

        HandInput tanki = hand("111p222s333s55566z");
        ThreePlayerScoreResult yakuman = score(tanki, facts(tanki, "6z", WinMethod.TSUMO, 28, 27,
                Riichi.DOUBLE_RIICHI, false, WinEvent.NORMAL, FirstTurn.NONE,
                List.of(116), List.of(117), List.of(120, 121, 122, 123), SUPPLEMENT));
        expect(yakuman, 0, 0, 2, 48_000);
        has(yakuman, Yaku.SUUANKOU_TANKI);
        lacks(yakuman, Yaku.RINSHAN_KAIHOU, Yaku.DOUBLE_RIICHI, Yaku.MENZEN_TSUMO);
        check(yakuman.dora() == 0 && yakuman.ura() == 0 && yakuman.aka() == 0 && yakuman.kita() == 0,
                "all bonuses suppressed on genuine yakuman");

        HandInput first = hand("19m19p19s11234567z");
        ThreePlayerScoreResult tenhou = score(first, facts(first, "9s", WinMethod.TSUMO, 27, 27,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.TENHOU,
                List.of(), List.of(), List.of(), NONE));
        expect(tenhou, 0, 0, 3, 96_000);
        has(tenhou, Yaku.TENHOU, Yaku.KOKUSHI_THIRTEEN_WAIT);
        check(tenhou.winningTileId() / 4 == 27, "tenhou chooses pair tile for highest special-wait interpretation");
        ThreePlayerScoreResult chiihou = score(first, facts(first, "9s", WinMethod.TSUMO, 28, 27,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.CHIIHOU,
                List.of(), List.of(), List.of(), NONE));
        expect(chiihou, 0, 0, 2, 48_000);
        has(chiihou, Yaku.CHIIHOU, Yaku.KOKUSHI_MUSOU);
        lacks(chiihou, Yaku.KOKUSHI_THIRTEEN_WAIT);
        check(chiihou.winningTileId() == winningId(first, "9s"), "chiihou keeps actual drawn tile");
    }

    private static void parity() {
        List<HandInput> hands = List.of(hand("123456p789s345s22s"), hand("11223344556677p"),
                hand("111999m123p456s22p"), hand("11m99m22p33p44s55s77z"),
                hand("444z123p456p789s22s"), hand("111p222s333s55566z"),
                hand("123p456s789s22p", meld(Meld.Kind.PON, "555z")),
                hand("123p456s789s22p", meld(Meld.Kind.CLOSED_KAN, "1111m")));
        for (HandInput original : hands) {
            // Bijection of physical copies preserves shape and red status; no renumbered sanma IDs.
            for (int rotation = 0; rotation < 3; rotation++) {
                final int shift = rotation;
                HandInput h = new HandInput(original.concealedTiles().stream().map(id -> rotateCopy(id, shift)).toList(),
                        original.melds().stream().map(m -> new Meld(m.kind(),
                                m.tiles().stream().map(id -> rotateCopy(id, shift)).toList())).toList());
                for (int seat = 27; seat <= 29; seat++) for (int round = 27; round <= 29; round++) {
                    for (WinMethod method : WinMethod.values()) {
                        int winning = h.concealedTiles().getFirst();
                        WinContext c = new WinContext(method, winning, seat, round, Riichi.NONE, false,
                                WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
                        List<ScoreResult> four = HandScorer.scoreAll(h, c);
                        List<ThreePlayerScoreResult> three = ThreePlayerHandScorer.scoreAll(h,
                                new ThreePlayerWinContext(c, List.of(), NONE));
                        check(three.size() == four.size(), "no forms lost or invented by sanma adaptation");
                        for (ThreePlayerScoreResult r : three) {
                            check(four.stream().anyMatch(s -> s.interpretation().equals(r.interpretation())
                                    && s.yaku().equals(r.yaku()) && s.han() == r.han() && s.fuResult().equals(r.fuResult())
                                    && s.yakumanUnits() == r.yakumanUnits()), "shared yaku and fu stay identical");
                            int base = r.points().basePoints();
                            int expected = method == WinMethod.RON ? ceil100(base * (seat == 27 ? 6 : 4))
                                    : seat == 27 ? 2 * ceil100(base * 2) : ceil100(base * 2) + ceil100(base);
                            check(r.points().payment().totalPoints() == expected, "independent actual-payer recomputation");
                        }
                    }
                }
            }
        }
    }

    private static void validation() {
        HandInput h = hand("123456p789s345s44z");
        WinContext c = facts(h, "6p", WinMethod.TSUMO).context();
        illegal(() -> new ThreePlayerWinContext(c, List.of(108), NONE), "only north may be extracted");
        illegal(() -> new ThreePlayerWinContext(c, List.of(122, 122), NONE), "duplicate physical kita");
        illegal(() -> new ThreePlayerWinContext(c, List.of(-1), NONE), "negative physical kita");
        illegal(() -> new ThreePlayerWinContext(c, List.of(136), NONE), "out-of-range physical kita");
        illegal(() -> ThreePlayerHandScorer.score(h, new ThreePlayerWinContext(c, List.of(120), NONE)), "held north reused as kita");
        illegal(() -> facts(h, "6p", WinMethod.TSUMO, 30, 27, Riichi.NONE, false, WinEvent.NORMAL,
                FirstTurn.NONE, List.of(), List.of(), List.of(), NONE), "north seat absent");
        illegal(() -> facts(h, "6p", WinMethod.TSUMO, 28, 30, Riichi.NONE, false, WinEvent.NORMAL,
                FirstTurn.NONE, List.of(), List.of(), List.of(), NONE), "north round absent");
        illegal(() -> facts(h, "6p", WinMethod.TSUMO, 28, 27, Riichi.NONE, false, WinEvent.NORMAL,
                FirstTurn.NONE, List.of(4), List.of(), List.of(), NONE), "removed man indicator");
        illegal(() -> facts(h, "6p", WinMethod.TSUMO, 28, 27, Riichi.NONE, false, WinEvent.NORMAL,
                FirstTurn.NONE, List.of(122), List.of(), List.of(122), NONE), "indicator reused as kita");
        illegal(() -> facts(h, "6p", WinMethod.TSUMO, 28, 27, Riichi.NONE, false, WinEvent.NORMAL,
                FirstTurn.NONE, List.of(116), List.of(4), List.of(), NONE), "removed ura indicator");
        illegal(() -> facts(h, "6p", WinMethod.TSUMO, 28, 27, Riichi.NONE, false, WinEvent.NORMAL,
                FirstTurn.NONE, List.of(116), List.of(122), List.of(122), NONE), "ura indicator reused as kita");
        illegal(() -> ThreePlayerHandScorer.score(h, facts(h, "6p", WinMethod.TSUMO, 28, 27,
                Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(120), List.of(), List.of(), NONE)),
                "indicator reused in hand");
        HandInput removed = hand("123456m789p345s22p");
        illegal(() -> ThreePlayerHandScorer.score(removed, facts(removed, "9p", WinMethod.TSUMO)), "removed concealed man");
        illegal(() -> facts(removed, "2m", WinMethod.RON), "removed winning tile");
        HandInput chi = hand("123p456s789s22p", meld(Meld.Kind.CHI, "123p"));
        illegal(() -> ThreePlayerHandScorer.score(chi, facts(chi, "6s", WinMethod.TSUMO)), "sanma has no chi");
        HandInput removedMeld = hand("123p456s789s22p", meld(Meld.Kind.PON, "222m"));
        illegal(() -> ThreePlayerHandScorer.score(removedMeld, facts(removedMeld, "6s", WinMethod.TSUMO)), "removed meld tile");
        HandInput northMeld = hand("123p456s789s22p", meld(Meld.Kind.PON, "444z"));
        illegal(() -> ThreePlayerHandScorer.score(northMeld,
                withNorth(facts(northMeld, "6s", WinMethod.TSUMO), List.of(120), NONE)), "declared north reused as kita");
        HandInput waiting = new HandInput(h.concealedTiles().subList(0, 13), List.of());
        illegal(() -> ThreePlayerHandScorer.score(waiting, facts(waiting, "6p", WinMethod.TSUMO)), "waiting hand is not complete");
        WinContext foreignWinner = new WinContext(WinMethod.RON, 123, 28, 27, Riichi.NONE, false,
                WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
        illegal(() -> ThreePlayerHandScorer.score(h, new ThreePlayerWinContext(foreignWinner, List.of(), ROBBERY)),
                "winning north must be physically included in the completed concealed hand");
        illegal(() -> withNorth(facts(h, "6p", WinMethod.TSUMO), List.of(), SUPPLEMENT), "supplement without established kita");
        illegal(() -> withNorth(facts(h, "6p", WinMethod.RON), List.of(122), SUPPLEMENT), "supplement must be tsumo");
        illegal(() -> withNorth(facts(h, "6p", WinMethod.TSUMO), List.of(122), ROBBERY), "robbery must be north ron");
        illegal(() -> withNorth(facts(h, "6p", WinMethod.RON), List.of(), ROBBERY), "robbery must win on north");
        illegal(() -> facts(h, "6p", WinMethod.TSUMO, 28, 27, Riichi.RIICHI, true, WinEvent.NORMAL,
                FirstTurn.NONE, List.of(), List.of(), List.of(122), SUPPLEMENT), "established kita interrupts ippatsu");
        illegal(() -> facts(h, "6p", WinMethod.TSUMO, 28, 27, Riichi.NONE, false, WinEvent.NORMAL,
                FirstTurn.CHIIHOU, List.of(), List.of(), List.of(122), NONE), "established kita interrupts chiihou");
        illegal(() -> facts(h, "6p", WinMethod.TSUMO, 28, 27, Riichi.NONE, false, WinEvent.HAITEI,
                FirstTurn.NONE, List.of(), List.of(), List.of(122), SUPPLEMENT), "no simultaneous haitei/kita event");
        illegal(() -> facts(h, "4z", WinMethod.RON, 28, 27, Riichi.NONE, false, WinEvent.CHANKAN,
                FirstTurn.NONE, List.of(), List.of(), List.of(), ROBBERY), "robbery cannot smuggle chankan yaku");
        illegal(() -> ThreePlayerHandScorer.score(h, facts(h, "6p", WinMethod.TSUMO, 28, 27,
                Riichi.NONE, false, WinEvent.RINSHAN, FirstTurn.NONE, List.of(), List.of(), List.of(122), NONE)),
                "kita rinshan must use explicit supplement event, not bypass four-player kan validation");
        HandInput open = hand("123p456s789s22p", meld(Meld.Kind.PON, "999p"));
        illegal(() -> ThreePlayerHandScorer.score(open, facts(open, "6s", WinMethod.RON, 28, 27,
                Riichi.RIICHI, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of(), List.of(), NONE)), "open riichi");
        List<Integer> mutable = new ArrayList<>(List.of(122));
        ThreePlayerWinContext copied = new ThreePlayerWinContext(c, mutable, SUPPLEMENT);
        mutable.clear();
        check(copied.extractedNorths().equals(List.of(122)), "defensive input copy");
        immutable(() -> copied.extractedNorths().clear(), "immutable kita output");
        ThreePlayerScoreResult r = score(h, copied);
        immutable(() -> r.yaku().clear(), "immutable yaku output");
        immutable(() -> r.fuResult().components().clear(), "immutable fu output");
        immutable(() -> ThreePlayerHandScorer.scoreAll(h, copied).clear(), "immutable candidates");
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
        int type = tiles(tile, new boolean[136]).getFirst() / 4;
        return h.concealedTiles().stream().filter(id -> id / 4 == type).findFirst().orElseThrow();
    }

    private static ThreePlayerWinContext facts(HandInput h, String tile, WinMethod method) {
        return facts(h, tile, method, 28, 27, Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE,
                List.of(), List.of(), List.of(), NONE);
    }

    private static ThreePlayerWinContext facts(HandInput h, String tile, WinMethod method, int seat, int round,
            Riichi riichi, boolean ippatsu, WinEvent event, FirstTurn first, List<Integer> dora, List<Integer> ura,
            List<Integer> north, ThreePlayerWinContext.NorthEvent northEvent) {
        return new ThreePlayerWinContext(new WinContext(method, winningId(h, tile), seat, round,
                riichi, ippatsu, event, first, dora, ura), north, northEvent);
    }

    private static ThreePlayerWinContext withNorth(ThreePlayerWinContext c, List<Integer> north,
            ThreePlayerWinContext.NorthEvent event) {
        return new ThreePlayerWinContext(c.context(), north, event);
    }

    private static Set<Integer> held(HandInput h) {
        Set<Integer> held = new HashSet<>(h.concealedTiles());
        h.melds().forEach(m -> held.addAll(m.tiles()));
        return held;
    }

    private static int rotateCopy(int id, int shift) {
        int type = id / 4, copy = id % 4;
        int changed = type < 27 && type % 9 == 4 ? copy == 0 ? 0 : 1 + (copy - 1 + shift) % 3
                : (copy + shift) % 4;
        return type * 4 + changed;
    }

    private static int ceil100(int points) { return (points + 99) / 100 * 100; }
    private static ThreePlayerScoreResult score(HandInput h, ThreePlayerWinContext c) {
        return ThreePlayerHandScorer.score(h, c).orElseThrow(() -> new AssertionError("Expected scoring hand: " + h + "; " + c));
    }
    private static void expect(ThreePlayerScoreResult r, int han, int fu, int units, int total) {
        check(r.han() == han && r.fu() == fu && r.yakumanUnits() == units
                && r.points().payment().totalPoints() == total, "expected " + han + "han " + fu + "fu "
                + units + "yakuman " + total + "; actual=" + r);
    }
    private static void has(ThreePlayerScoreResult r, Yaku... names) {
        for (Yaku name : names) check(r.yaku().stream().anyMatch(a -> a.yaku() == name), "missing " + name);
    }
    private static void lacks(ThreePlayerScoreResult r, Yaku... names) {
        for (Yaku name : names) check(r.yaku().stream().noneMatch(a -> a.yaku() == name), "unexpected " + name);
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
