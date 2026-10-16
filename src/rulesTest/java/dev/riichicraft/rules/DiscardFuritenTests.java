package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static dev.riichicraft.rules.WinContext.*;
import static dev.riichicraft.rules.WinEvaluator.Rejection.*;
import static dev.riichicraft.rules.WinEvaluator.Source.*;

/** Opponent-discard event examples, including the user's explicit no-yaku furiten ruling. */
public final class DiscardFuritenTests {
    private static int assertions;

    private DiscardFuritenTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        run("a no-yaku structural completion blocks the other side", DiscardFuritenTests::noYaku);
        run("a call uses the pre-call waiting hand", DiscardFuritenTests::call);
        run("riichi, prior furiten and non-completions", DiscardFuritenTests::history);
        run("seven pairs, kokushi and ambiguous standard shapes", DiscardFuritenTests::specialShapes);
        run("red fives and exact physical copies", DiscardFuritenTests::physicalCopies);
        run("invalid events and immutable inputs", DiscardFuritenTests::validation);
        System.out.printf("PASS discard furiten: %d assertions; %.3f s%n",
                assertions, (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static void noYaku() {
        // 456p 789s EE WW with declared 123m: east is round wind, west has no yaku.
        HandInput waiting = hand("456p789s1133z", Meld.Kind.CHI, "123m");
        int eastTile = free(waiting, type("1z"));
        int westTile = free(waiting, type("3z"));
        HandInput east = append(waiting, eastTile);
        HandInput west = append(waiting, westTile);
        check(FuritenAnalyzer.evaluate(waiting, FuritenState.initial()).waitTypes()
                .equals(Set.of(type("1z"), type("3z"))), "both E and W are structural waits");
        check(evaluate(east, eastTile, Riichi.NONE, FuritenState.initial(), DISCARD).allowed(),
                "east initially has a legal round-wind ron");
        rejected(evaluate(west, westTile, Riichi.NONE, FuritenState.initial(), DISCARD), NO_YAKU);

        FuritenState initial = FuritenState.initial();
        FuritenState missedWest = observe(waiting, westTile, initial, Riichi.NONE);
        check(missedWest.temporary() && !missedWest.riichiPass(),
                "not ronning a no-yaku west automatically creates temporary furiten");
        check(missedWest.discardedTypes().isEmpty(), "an opponent's west is not added to our own discards");
        check(!initial.temporary(), "the previous history is not mutated by the event");
        rejected(evaluate(east, eastTile, Riichi.NONE, missedWest, DISCARD), TEMPORARY_FURITEN);

        // This player next actually draws an unrelated 8m and discards that same physical tile.
        int drawnEight = free(waiting, type("8m"));
        HandInput afterDraw = append(waiting, drawnEight);
        check(afterDraw.isComplete(), "the intervening actual draw is a structural 14-tile state");
        FuritenState afterDrawDiscard = missedWest.afterDraw().afterDiscard(drawnEight);
        check(!afterDrawDiscard.temporary() && !afterDrawDiscard.riichiPass(),
                "the actual draw clears temporary furiten before the later discard");
        check(afterDrawDiscard.discardedTypes().equals(Set.of(type("8m"))),
                "only this player's actual discard enters discard history");
        int laterEastTile = free(waiting, type("1z"), eastTile);
        HandInput laterEast = append(waiting, laterEastTile);
        check(evaluate(laterEast, laterEastTile, Riichi.NONE, afterDrawDiscard, DISCARD).allowed(),
                "a subsequent east is legal again after the actual draw and unrelated discard");

        // A separate possible history: this player discarded a third west earlier.
        int ownWest = free(waiting, type("3z"));
        FuritenState ownNoYakuDiscard = FuritenState.initial().afterDiscard(ownWest);
        check(FuritenAnalyzer.evaluate(waiting, ownNoYakuDiscard).discardFuriten(),
                "an own no-yaku west discard still causes discard furiten");
        rejected(evaluate(east, eastTile, Riichi.NONE, ownNoYakuDiscard, DISCARD), DISCARD_FURITEN);
        check(ownNoYakuDiscard.afterDraw().discardedTypes().contains(type("3z")),
                "drawing cannot erase the no-yaku west's historical discard type");
        rejected(evaluate(east, eastTile, Riichi.NONE, ownNoYakuDiscard.afterDraw(), DISCARD), DISCARD_FURITEN);

        // The fourth west can now be discarded by an opponent. Existing furiten cannot bypass tracking.
        int opponentFourthWest = free(waiting, type("3z"), ownWest);
        FuritenState both = observe(waiting, opponentFourthWest, ownNoYakuDiscard, Riichi.NONE);
        check(both.temporary(), "an already discard-furiten player still records the missed completion");
        rejected(evaluate(east, eastTile, Riichi.NONE, both, DISCARD), DISCARD_FURITEN, TEMPORARY_FURITEN);
        check(evaluate(laterEast, laterEastTile, Riichi.NONE, both, SELF_DRAW).allowed(),
                "the other free east copy as an actual self-draw is not prohibited by either furiten");
    }

    private static void call() {
        HandInput beforeCall = hand("456p789s1133z", Meld.Kind.CHI, "123m");
        int discardedWest = free(beforeCall, type("3z"));
        FuritenState passed = observe(beforeCall, discardedWest, FuritenState.initial(), Riichi.NONE);
        check(passed.temporary(), "choosing pon instead of ron still records the pre-call completion");

        List<Integer> westPon = new ArrayList<>();
        List<Integer> concealed = new ArrayList<>(beforeCall.concealedTiles());
        for (int tile : beforeCall.concealedTiles()) {
            if (TileCatalog.typeOf(tile) == type("3z")) {
                concealed.remove(Integer.valueOf(tile));
                westPon.add(tile);
            }
        }
        westPon.add(discardedWest);
        List<Meld> declared = new ArrayList<>(beforeCall.melds());
        declared.add(new Meld(Meld.Kind.PON, westPon));
        HandInput afterPon = new HandInput(concealed, declared);
        check(afterPon.isComplete(), "pon consumes the two exact held west copies and the opponent's copy");
        check(afterPon.melds().getLast().tiles().contains(discardedWest),
                "the actual opponent discard becomes a member of the declared pon");

        int nineSou = concealed.stream().filter(tile -> TileCatalog.typeOf(tile) == type("9s"))
                .findFirst().orElseThrow();
        concealed.remove(Integer.valueOf(nineSou));
        HandInput afterCallDiscard = new HandInput(concealed, declared);
        FuritenState afterDiscard = passed.afterDiscard(nineSou);
        check(afterCallDiscard.isWaiting(), "the call's following discard restores the structural 13-tile state");
        check(FuritenAnalyzer.evaluate(afterCallDiscard, afterDiscard).waitTypes()
                .equals(Set.of(type("6s"), type("9s"))), "the pon and following discard change the waits");
        check(FuritenAnalyzer.evaluate(afterCallDiscard, afterDiscard).temporary(),
                "pon and its following discard do not count as an actual draw or clear temporary furiten");
        check(beforeCall.concealedTiles().size() == 10 && beforeCall.melds().size() == 1,
                "constructing the later call does not mutate the pre-call input");
    }

    private static void history() {
        HandInput waiting = hand("123456m789p34s22p");
        int winningFive = free(waiting, type("5s"));
        int winningTwo = free(waiting, type("2s"));
        int nonWinningNine = free(waiting, type("9m"));
        for (Riichi riichi : List.of(Riichi.RIICHI, Riichi.DOUBLE_RIICHI)) {
            for (int winningTile : List.of(winningFive, winningTwo)) {
                FuritenState passed = observe(waiting, winningTile, FuritenState.initial(), riichi);
                check(passed.riichiPass() && !passed.temporary(),
                        riichi + " passing either completion creates the hand-long flag");
                FuritenState afterDraw = passed.afterDraw();
                check(afterDraw.riichiPass(), riichi + " missed ron persists after an actual draw");
                HandInput complete = append(waiting, winningTile);
                rejected(evaluate(complete, winningTile, riichi, afterDraw, DISCARD), RIICHI_FURITEN);
                check(evaluate(complete, winningTile, riichi, afterDraw, SELF_DRAW).allowed(),
                        riichi + " hand-long furiten still permits actual self draw");
                check(observe(waiting, nonWinningNine, afterDraw, riichi).equals(afterDraw),
                        "an unrelated opponent discard cannot clear " + riichi + " missed ron");
            }
            FuritenState olderTemporary = FuritenState.initial().afterPassedRon(Riichi.NONE);
            FuritenState both = observe(waiting, winningFive, olderTemporary, riichi);
            check(both.temporary() && both.riichiPass(), "a new " + riichi + " pass preserves an older temporary flag");
            check(!both.afterDraw().temporary() && both.afterDraw().riichiPass(),
                    "an actual draw clears only the temporary member of the two flags");
        }

        for (Riichi riichi : Riichi.values()) {
            check(observe(waiting, nonWinningNine, FuritenState.initial(), riichi).equals(FuritenState.initial()),
                    "a non-completing discard cannot invent furiten for " + riichi);
        }
        for (FuritenState prior : List.of(FuritenState.initial().afterPassedRon(Riichi.NONE),
                FuritenState.initial().afterPassedRon(Riichi.RIICHI),
                FuritenState.initial().afterDiscard(winningTwo).afterPassedRon(Riichi.NONE))) {
            check(observe(waiting, nonWinningNine, prior, Riichi.NONE).equals(prior),
                    "non-completions preserve every pre-existing history field");
        }

        HandInput nonTenpai = hand("123m456p789s1234z");
        check(observe(nonTenpai, free(nonTenpai, type("5z")), FuritenState.initial(), Riichi.NONE)
                .equals(FuritenState.initial()), "a 13-tile state need not be tenpai and produces no flag without completion");

        HandInput withClosedKan = hand("234m456p78s22p", Meld.Kind.CLOSED_KAN, "1111z");
        check(observe(withClosedKan, free(withClosedKan, type("6s")), FuritenState.initial(), Riichi.RIICHI)
                .riichiPass(), "a declared concealed kan remains menzen for established riichi");
    }

    private static void specialShapes() {
        HandInput sevenPairs = hand("1122334455667z");
        int seventhPair = free(sevenPairs, type("7z"));
        check(HandAnalyzer.analyze(append(sevenPairs, seventhPair)).stream()
                .allMatch(shape -> shape.form() == HandDecomposition.Form.SEVEN_PAIRS),
                "the honors fixture completes only as seven distinct pairs");
        check(observe(sevenPairs, seventhPair, FuritenState.initial(), Riichi.NONE).temporary(),
                "a seven-pairs completion automatically triggers missed-discard furiten");

        HandInput kokushi = hand("19m19p19s1234567z");
        for (int waitType : List.of(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33)) {
            int terminalOrHonor = free(kokushi, waitType);
            check(observe(kokushi, terminalOrHonor, FuritenState.initial(), Riichi.NONE).temporary(),
                    "each of the thirteen kokushi completion types automatically triggers temporary furiten");
            check(observe(kokushi, terminalOrHonor, FuritenState.initial(), Riichi.RIICHI).riichiPass(),
                    "each of the thirteen kokushi completion types triggers hand-long furiten after riichi");
        }
        check(!observe(kokushi, free(kokushi, type("2m")), FuritenState.initial(), Riichi.NONE).temporary(),
                "an ordinary middle tile cannot complete kokushi");

        HandInput missingRed = hand("119m19p19s123456z");
        check(observe(missingRed, free(missingRed, type("7z")), FuritenState.initial(), Riichi.NONE).temporary(),
                "a missing-type kokushi completion triggers temporary furiten too");
        check(observe(missingRed, free(missingRed, type("7z")), FuritenState.initial(), Riichi.RIICHI).riichiPass(),
                "a missing-type kokushi completion also triggers hand-long furiten after riichi");
        check(!observe(missingRed, free(missingRed, type("9m")), FuritenState.initial(), Riichi.NONE).temporary(),
                "a second pair cannot complete kokushi while another required type is absent");

        HandInput ambiguous = hand("1122334455667m");
        int seventhMan = free(ambiguous, type("7m"));
        Set<HandDecomposition.Form> forms = new HashSet<>();
        HandAnalyzer.analyze(append(ambiguous, seventhMan)).forEach(shape -> forms.add(shape.form()));
        check(forms.equals(Set.of(HandDecomposition.Form.STANDARD, HandDecomposition.Form.SEVEN_PAIRS)),
                "the ambiguity fixture has both standard and seven-pairs completions");
        check(observe(ambiguous, seventhMan, FuritenState.initial(), Riichi.NONE).temporary(),
                "multiple interpretations of a completion still trigger the event");

        HandInput severalStandard = hand("1112223334445m");
        int fifthMan = free(severalStandard, type("5m"));
        check(HandAnalyzer.analyze(append(severalStandard, fifthMan)).stream()
                .filter(shape -> shape.form() == HandDecomposition.Form.STANDARD).count() > 1,
                "the triplets fixture has multiple standard decompositions");
        check(observe(severalStandard, fifthMan, FuritenState.initial(), Riichi.NONE).temporary(),
                "a completion with several standard decompositions triggers without choosing a scorer interpretation");
    }

    private static void physicalCopies() {
        HandInput fiveWait = hand("123456m789p34s22p");
        for (int fiveSou = TileCatalog.RED_SOU_FIVE; fiveSou < TileCatalog.RED_SOU_FIVE + 4; fiveSou++) {
            check(observe(fiveWait, fiveSou, FuritenState.initial(), Riichi.NONE).temporary(),
                    "red and each ordinary physical five complete the same structural wait");
        }
        FuritenState ownRedFive = FuritenState.initial().afterDiscard(TileCatalog.RED_SOU_FIVE);
        int ordinaryFive = TileCatalog.RED_SOU_FIVE + 1;
        rejected(evaluate(append(fiveWait, ordinaryFive), ordinaryFive, Riichi.NONE, ownRedFive, DISCARD),
                DISCARD_FURITEN);

        HandInput heldPair = hand("123456m789p55s22z");
        int freeThirdFive = free(heldPair, type("5s"));
        check(observe(heldPair, freeThirdFive, FuritenState.initial(), Riichi.NONE).temporary(),
                "another unheld physical copy of an already-held tile type is legal and completes a triplet");
        int heldFive = heldPair.concealedTiles().stream()
                .filter(tile -> TileCatalog.typeOf(tile) == type("5s")).findFirst().orElseThrow();
        expectThrows(IllegalArgumentException.class,
                () -> observe(heldPair, heldFive, FuritenState.initial(), Riichi.NONE),
                "a matching type cannot excuse reusing the exact concealed physical ID");
    }

    private static void validation() {
        HandInput waiting = hand("123456m789p34s22p");
        int winningTile = free(waiting, type("5s"));
        FuritenState empty = FuritenState.initial();
        expectThrows(IllegalArgumentException.class, () -> observe(waiting, -1, empty, Riichi.NONE), "negative discard ID");
        expectThrows(IllegalArgumentException.class, () -> observe(waiting, 136, empty, Riichi.NONE), "out-of-range discard ID");
        expectThrows(IllegalArgumentException.class,
                () -> observe(append(waiting, winningTile), free(waiting, type("9m")), empty, Riichi.NONE),
                "the input must precede the opponent discard, not already contain a fourteenth structural tile");
        expectThrows(IllegalArgumentException.class,
                () -> observe(waiting, waiting.concealedTiles().getFirst(), empty, Riichi.NONE),
                "duplicate exact held identity is rejected even for a non-completing discard");

        HandInput open = hand("456p789s1133z", Meld.Kind.CHI, "123m");
        for (Riichi riichi : List.of(Riichi.RIICHI, Riichi.DOUBLE_RIICHI)) {
            expectThrows(IllegalArgumentException.class,
                    () -> observe(open, free(open, type("3z")), empty, riichi),
                    "an open hand cannot be marked established " + riichi);
        }
        expectThrows(IllegalArgumentException.class,
                () -> observe(open, open.melds().getFirst().tiles().getFirst(), empty, Riichi.NONE),
                "a discard cannot reuse an exact physical ID from a declared group");
        expectThrows(NullPointerException.class, () -> observe(null, winningTile, empty, Riichi.NONE), "null waiting hand");
        expectThrows(NullPointerException.class, () -> observe(waiting, winningTile, null, Riichi.NONE), "null history");
        expectThrows(NullPointerException.class, () -> observe(waiting, winningTile, empty, null), "null established-riichi fact");

        List<Integer> suppliedTiles = new ArrayList<>(open.concealedTiles());
        List<Meld> suppliedMelds = new ArrayList<>(open.melds());
        HandInput snapshot = new HandInput(suppliedTiles, suppliedMelds);
        List<Integer> beforeTiles = List.copyOf(snapshot.concealedTiles());
        List<Meld> beforeMelds = List.copyOf(snapshot.melds());
        Set<Integer> suppliedDiscards = new HashSet<>(Set.of(type("9m")));
        FuritenState prior = new FuritenState(suppliedDiscards, false, false);
        FuritenState result = observe(snapshot, free(snapshot, type("3z")), prior, Riichi.NONE);
        suppliedTiles.clear();
        suppliedMelds.clear();
        suppliedDiscards.add(type("3z"));
        check(snapshot.concealedTiles().equals(beforeTiles) && snapshot.melds().equals(beforeMelds),
                "event processing and later caller changes cannot mutate the waiting hand");
        check(prior.discardedTypes().equals(Set.of(type("9m"))) && !prior.temporary(),
                "the supplied previous history is unchanged and retains its defensive copy");
        check(result.discardedTypes().equals(Set.of(type("9m"))) && result.temporary(),
                "the resulting history has its own immutable collection and the new flag");
        expectThrows(UnsupportedOperationException.class, () -> result.discardedTypes().clear(),
                "the event result's discarded types cannot be mutated");
    }

    private static FuritenState observe(HandInput waiting, int discard, FuritenState history, Riichi riichi) {
        return FuritenTracker.afterDiscardWithoutRon(waiting, discard, history, riichi);
    }

    private static WinEvaluator.Decision evaluate(HandInput completed, int winner, Riichi riichi,
                                                 FuritenState history, WinEvaluator.Source source) {
        WinContext context = new WinContext(source == SELF_DRAW ? WinMethod.TSUMO : WinMethod.RON,
                winner, 28, 27, riichi, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
        return WinEvaluator.evaluate(completed, context, history, source);
    }

    private static HandInput hand(String concealed) { return hand(concealed, null, null); }

    private static HandInput hand(String concealed, Meld.Kind kind, String declared) {
        int[] copies = new int[TileCatalog.TYPE_COUNT];
        List<Meld> melds = kind == null ? List.of() : List.of(new Meld(kind, physical(declared, copies)));
        return new HandInput(physical(concealed, copies), melds);
    }

    private static List<Integer> physical(String notation, int[] copies) {
        List<Integer> result = new ArrayList<>();
        StringBuilder ranks = new StringBuilder();
        for (char ch : notation.toCharArray()) {
            if (Character.isDigit(ch)) { ranks.append(ch); continue; }
            int offset = switch (ch) {
                case 'm' -> 0; case 'p' -> 9; case 's' -> 18; case 'z' -> 27;
                default -> throw new IllegalArgumentException("Bad fixture suit");
            };
            for (char digit : ranks.toString().toCharArray()) {
                int type = offset + digit - '1';
                if (copies[type] == 4) throw new IllegalArgumentException("Fixture uses a fifth copy");
                result.add(type * 4 + copies[type]++);
            }
            ranks.setLength(0);
        }
        if (!ranks.isEmpty()) throw new IllegalArgumentException("Fixture is missing a suit");
        return result;
    }

    private static int type(String notation) { return physical(notation, new int[34]).getFirst() / 4; }

    private static int free(HandInput hand, int type, int... unavailable) {
        Set<Integer> held = new HashSet<>(hand.concealedTiles());
        hand.melds().forEach(meld -> held.addAll(meld.tiles()));
        for (int tile : unavailable) held.add(tile);
        for (int copy = 0; copy < 4; copy++) if (!held.contains(type * 4 + copy)) return type * 4 + copy;
        throw new IllegalArgumentException("Fixture has no available physical copy");
    }

    private static HandInput append(HandInput waiting, int winningTile) {
        List<Integer> completed = new ArrayList<>(waiting.concealedTiles());
        completed.add(winningTile);
        return new HandInput(completed, waiting.melds());
    }

    private static void rejected(WinEvaluator.Decision decision, WinEvaluator.Rejection... expected) {
        check(!decision.allowed(), "rejected decision cannot allow a win");
        check(decision.rejections().equals(Set.of(expected)),
                "expected rejection reasons " + Set.of(expected) + " but got " + decision.rejections());
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expectThrows(Class<? extends Throwable> expected, Runnable action, String message) {
        assertions++;
        try { action.run(); }
        catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError(message + "; expected " + expected.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError(message + "; expected " + expected.getSimpleName());
    }

    private static void run(String name, Runnable test) {
        try { test.run(); }
        catch (Throwable failure) { throw new AssertionError("Discard furiten group failed: " + name, failure); }
    }
}
