package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static dev.riichicraft.rules.WinContext.*;
import static dev.riichicraft.rules.WinEvaluator.Rejection.*;
import static dev.riichicraft.rules.WinEvaluator.Source.*;

/** Fixed furiten and supplied-opportunity examples; no round history is inferred from a score. */
public final class FuritenTests {
    private static int assertions;

    private FuritenTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        run("immutable history and reset events", FuritenTests::history);
        run("all structural waits and changing discard furiten", FuritenTests::waits);
        run("supplied ron and tsumo opportunities", FuritenTests::opportunities);
        run("kan sources and the four physical copies", FuritenTests::kanSources);
        run("invalid inputs and immutable decisions", FuritenTests::validation);
        System.out.printf("PASS furiten: %d assertions; %.3f s%n",
                assertions, (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static void history() {
        FuritenState initial = FuritenState.initial();
        check(initial.discardedTypes().isEmpty() && !initial.temporary() && !initial.riichiPass(),
                "new hand has no furiten history");
        FuritenState discards = initial.afterDiscard(0).afterDiscard(3).afterDiscard(16).afterDiscard(17);
        check(discards.discardedTypes().equals(Set.of(0, 4)),
                "physical copies and red/normal five collapse to their discard types");
        check(initial.discardedTypes().isEmpty(), "recording a discard preserves its previous state");

        // The caller records an established missed-ron fact, not an automatically inferred offer.
        FuritenState temporary = discards.afterPassedRon(Riichi.NONE);
        check(temporary.temporary() && !temporary.riichiPass(), "ordinary missed ron sets temporary flag");
        FuritenState afterCallDiscard = temporary.afterDiscard(12);
        check(afterCallDiscard.temporary(), "discard after a call cannot clear temporary furiten");
        check(afterCallDiscard.discardedTypes().equals(Set.of(0, 3, 4)),
                "new discard does not remove old river facts, including tiles called away");
        FuritenState drawn = afterCallDiscard.afterDraw();
        check(!drawn.temporary() && !drawn.riichiPass(), "own actual draw clears temporary flag");
        check(drawn.discardedTypes().equals(afterCallDiscard.discardedTypes()),
                "own draw cannot remove historical discard types");
        check(temporary.temporary(), "draw creates a new state instead of mutating old state");

        for (Riichi declared : List.of(Riichi.RIICHI, Riichi.DOUBLE_RIICHI)) {
            FuritenState permanent = temporary.afterPassedRon(declared);
            check(permanent.riichiPass(), "established " + declared + " missed ron sets hand-long flag");
            check(permanent.afterDraw().riichiPass(), "own draw never clears riichi missed-ron flag");
            check(!permanent.afterDraw().temporary(), "own draw still clears any older temporary flag");
            check(permanent.afterDiscard(8).riichiPass(), "discard never clears riichi missed-ron flag");
            check(permanent.afterPassedRon(Riichi.NONE).riichiPass(),
                    "later primitive calls cannot erase a previous riichi missed ron");
        }
        check(FuritenState.initial().discardedTypes().isEmpty(), "next hand starts with a fresh history");
    }

    private static void waits() {
        HandInput waiting = hand("123456m789p34s22p");
        FuritenState first = FuritenState.initial().afterDiscard(tile("2s"));
        FuritenAnalyzer.Status one = FuritenAnalyzer.evaluate(waiting, first);
        check(one.waitTypes().equals(Set.of(type("2s"), type("5s"))), "34s is a two-sided 2s/5s wait");
        check(one.discardedWaitTypes().equals(Set.of(type("2s"))), "only discarded current waits are reported");
        check(one.discardFuriten() && one.blocksRon(), "one discarded side blocks all ron sides");
        FuritenAnalyzer.Status both = FuritenAnalyzer.evaluate(waiting, first.afterDiscard(tile("5s")));
        check(both.discardedWaitTypes().equals(both.waitTypes()), "all historical matching sides are retained");

        HandInput changed = hand("123456m789p45s22p");
        FuritenAnalyzer.Status changedStatus = FuritenAnalyzer.evaluate(changed, first);
        check(changedStatus.waitTypes().equals(Set.of(type("3s"), type("6s"))), "changed 45s waits on 3s/6s");
        check(!changedStatus.discardFuriten() && !changedStatus.blocksRon(),
                "discard furiten is recomputed instead of latched when the wait changes");
        check(FuritenAnalyzer.evaluate(waiting, first).discardFuriten(),
                "returning to an old wait rediscovers the retained historical discard");
        FuritenState redDiscard = FuritenState.initial().afterDiscard(TileCatalog.RED_SOU_FIVE);
        check(FuritenAnalyzer.evaluate(waiting, redDiscard).discardedWaitTypes().equals(Set.of(type("5s"))),
                "red five discard blocks the ordinary-five side too");
        check(FuritenAnalyzer.evaluate(changed, first.afterPassedRon(Riichi.NONE)).blocksRon(),
                "a different wait does not clear temporary missed-ron history");
        check(FuritenAnalyzer.evaluate(changed, first.afterPassedRon(Riichi.RIICHI)).riichiPass(),
                "a different supplied structure cannot clear hand-long missed-ron history");

        HandInput notTenpai = hand("123m456p789s1234z");
        FuritenAnalyzer.Status empty = FuritenAnalyzer.evaluate(notTenpai, first);
        check(empty.waitTypes().isEmpty() && !empty.discardFuriten(), "a non-tenpai structure has no discard overlap");
        expectThrows(IllegalArgumentException.class,
                () -> FuritenAnalyzer.evaluate(hand("123456m789p345s22p"), first),
                "furiten wait analysis rejects a completed 14-tile state");
    }

    private static void opportunities() {
        HandInput closed = hand("123456m789p345s22p");
        WinContext ron = context(closed, "6m", WinMethod.RON, Riichi.NONE, WinEvent.NORMAL);
        WinEvaluator.Decision allowed = WinEvaluator.evaluate(closed, ron, FuritenState.initial(), DISCARD);
        check(allowed.allowed() && allowed.handValue().isPresent() && allowed.rejections().isEmpty(),
                "ordinary pinfu ron is allowed for a supplied discard opportunity");
        check(allowed.handValue().orElseThrow().yaku().stream().anyMatch(award -> award.yaku() == Yaku.PINFU),
                "decision retains its concrete scoring explanation");

        FuritenState discardedOtherSide = FuritenState.initial().afterDiscard(tile("3m"));
        WinEvaluator.Decision discarded = WinEvaluator.evaluate(closed, ron, discardedOtherSide, DISCARD);
        rejected(discarded, DISCARD_FURITEN);
        check(discarded.handValue().isPresent(), "furiten rejects permission without hiding hand value");
        WinEvaluator.Decision temporary = WinEvaluator.evaluate(closed, ron,
                FuritenState.initial().afterPassedRon(Riichi.NONE), DISCARD);
        rejected(temporary, TEMPORARY_FURITEN);
        check(WinEvaluator.evaluate(closed, ron,
                FuritenState.initial().afterPassedRon(Riichi.NONE).afterDraw(), DISCARD).allowed(),
                "own next draw clears the temporary restriction for a later supplied opportunity");

        WinContext riichiRon = context(closed, "6m", WinMethod.RON, Riichi.RIICHI, WinEvent.NORMAL);
        FuritenState threeReasons = discardedOtherSide.afterPassedRon(Riichi.NONE).afterPassedRon(Riichi.RIICHI);
        rejected(WinEvaluator.evaluate(closed, riichiRon, threeReasons, DISCARD),
                DISCARD_FURITEN, TEMPORARY_FURITEN, RIICHI_FURITEN);
        rejected(WinEvaluator.evaluate(closed, riichiRon, threeReasons.afterDraw(), DISCARD),
                DISCARD_FURITEN, RIICHI_FURITEN);
        WinContext tsumo = context(closed, "6m", WinMethod.TSUMO, Riichi.RIICHI, WinEvent.NORMAL);
        check(WinEvaluator.evaluate(closed, tsumo, threeReasons, SELF_DRAW).allowed(),
                "discard, temporary and riichi furiten never prohibit a legal self draw");

        // 456p 789s EE WW + open 123m: both E/W complete the structure, but only E has round-wind yaku.
        HandInput asymmetricWait = hand("456p789s1133z", Meld.Kind.CHI, "123m");
        check(FuritenAnalyzer.evaluate(asymmetricWait, FuritenState.initial()).waitTypes()
                .equals(Set.of(type("1z"), type("3z"))), "east/west pair wait has two structural sides");
        HandInput eastComplete = append(asymmetricWait, type("1z"));
        HandInput westComplete = append(asymmetricWait, type("3z"));
        WinContext east = context(eastComplete, "1z", WinMethod.RON, Riichi.NONE, WinEvent.NORMAL);
        WinContext west = context(westComplete, "3z", WinMethod.RON, Riichi.NONE, WinEvent.NORMAL);
        check(WinEvaluator.evaluate(eastComplete, east, FuritenState.initial(), DISCARD).allowed(),
                "east-side ron has the east-round yaku");
        rejected(WinEvaluator.evaluate(westComplete, west, FuritenState.initial(), DISCARD), NO_YAKU);
        WinContext openSelfDraw = context(westComplete, "3z", WinMethod.TSUMO, Riichi.NONE, WinEvent.NORMAL);
        rejected(WinEvaluator.evaluate(westComplete, openSelfDraw,
                FuritenState.initial().afterDiscard(tile("3z")).afterPassedRon(Riichi.NONE), SELF_DRAW), NO_YAKU);
        // Ignoring furiten for tsumo cannot bypass the ordinary shape and yaku requirements.
        rejected(WinEvaluator.evaluate(eastComplete, east,
                FuritenState.initial().afterDiscard(tile("3z")), DISCARD), DISCARD_FURITEN);
        // This is NOT an assertion about whether passing the no-yaku west offer creates temporary furiten.

        HandInput noShape = hand("123m456p789s12345z");
        WinContext incompleteShape = context(noShape, "5z", WinMethod.RON, Riichi.NONE, WinEvent.NORMAL);
        rejected(WinEvaluator.evaluate(noShape, incompleteShape, FuritenState.initial(), DISCARD), NO_WINNING_SHAPE);
        rejected(WinEvaluator.evaluate(noShape,
                context(noShape, "5z", WinMethod.TSUMO, Riichi.NONE, WinEvent.NORMAL),
                FuritenState.initial().afterPassedRon(Riichi.NONE), SELF_DRAW), NO_WINNING_SHAPE);
        WinContext houtei = context(westComplete, "3z", WinMethod.RON, Riichi.NONE, WinEvent.HOUTEI);
        check(WinEvaluator.evaluate(westComplete, houtei, FuritenState.initial(), DISCARD).allowed(),
                "a supplied true last-discard event can provide the required yaku");
    }

    private static void kanSources() {
        HandInput sequence = hand("123456m789p345s22p");
        WinContext added = context(sequence, "6m", WinMethod.RON, Riichi.NONE, WinEvent.CHANKAN);
        check(WinEvaluator.evaluate(sequence, added, FuritenState.initial(), ADDED_KAN).allowed(),
                "a supplied added-kan chance permits a normal structure and provides chankan");
        rejected(WinEvaluator.evaluate(sequence, added, FuritenState.initial(), CLOSED_KAN),
                CLOSED_KAN_REQUIRES_KOKUSHI);
        rejected(WinEvaluator.evaluate(sequence, added,
                FuritenState.initial().afterPassedRon(Riichi.NONE), ADDED_KAN), TEMPORARY_FURITEN);
        rejected(WinEvaluator.evaluate(sequence, added,
                FuritenState.initial().afterDiscard(tile("3m")), ADDED_KAN), DISCARD_FURITEN);

        HandInput kokushi = hand("119m19p19s1234567z");
        WinContext missingNine = context(kokushi, "9m", WinMethod.RON, Riichi.NONE, WinEvent.CHANKAN);
        WinEvaluator.Decision closedKokushi = WinEvaluator.evaluate(kokushi, missingNine, FuritenState.initial(), CLOSED_KAN);
        check(closedKokushi.allowed(), "ordinary missing-tile kokushi may rob a supplied closed kan");
        check(closedKokushi.handValue().orElseThrow().yakumanUnits() == 1,
                "missing-nine closed-kan robbery is ordinary single kokushi");
        rejected(WinEvaluator.evaluate(kokushi,
                context(kokushi, "9m", WinMethod.RON, Riichi.RIICHI, WinEvent.CHANKAN),
                FuritenState.initial().afterPassedRon(Riichi.RIICHI), CLOSED_KAN), RIICHI_FURITEN);

        // A kan declarer owns all four copies before the robbed tile is transferred.
        for (WinEvaluator.Source source : List.of(ADDED_KAN, CLOSED_KAN)) {
            expectThrows(IllegalArgumentException.class,
                    () -> WinEvaluator.evaluate(kokushi,
                            context(kokushi, "1m", WinMethod.RON, Riichi.NONE, WinEvent.CHANKAN),
                            FuritenState.initial(), source),
                    source + " cannot supply a fifth physical copy for a thirteen-sided kokushi completion");
            expectThrows(IllegalArgumentException.class,
                    () -> WinEvaluator.evaluate(sequence,
                            context(sequence, "2p", WinMethod.RON, Riichi.NONE, WinEvent.CHANKAN),
                            FuritenState.initial(), source),
                    source + " cannot complete a pair already containing the same type");
        }
        HandInput sameTypeDeclared = hand("123m456p789s22z", Meld.Kind.PON, "111m");
        expectThrows(IllegalArgumentException.class,
                () -> WinEvaluator.evaluate(sameTypeDeclared,
                        context(sameTypeDeclared, "1m", WinMethod.RON, Riichi.NONE, WinEvent.CHANKAN),
                        FuritenState.initial(), ADDED_KAN),
                "kan-source copy ownership includes the winner's previously declared groups");

        WinContext ordinaryKokushi = context(kokushi, "1m", WinMethod.RON, Riichi.NONE, WinEvent.NORMAL);
        check(WinEvaluator.evaluate(kokushi, ordinaryKokushi, FuritenState.initial(), DISCARD)
                        .handValue().orElseThrow().yakumanUnits() == 2,
                "thirteen-sided kokushi remains legal on an ordinary discard");
        int otherSix = freeTileOfType(sequence, type("6m"));
        WinContext indicatorConflict = indicators(added, List.of(otherSix), List.of());
        expectThrows(IllegalArgumentException.class,
                () -> WinEvaluator.evaluate(sequence, indicatorConflict, FuritenState.initial(), ADDED_KAN),
                "a dora indicator cannot be another copy of a supplied four-copy kan type");
        WinContext riichiAdded = context(sequence, "6m", WinMethod.RON, Riichi.RIICHI, WinEvent.CHANKAN);
        WinContext uraConflict = indicators(riichiAdded, List.of(tile("9m")), List.of(otherSix));
        expectThrows(IllegalArgumentException.class,
                () -> WinEvaluator.evaluate(sequence, uraConflict, FuritenState.initial(), ADDED_KAN),
                "ura indicator ownership also excludes a supplied four-copy kan type");
        WinContext ordinaryWithSameTypeIndicator = indicators(
                context(sequence, "6m", WinMethod.RON, Riichi.NONE, WinEvent.NORMAL), List.of(otherSix), List.of());
        check(WinEvaluator.evaluate(sequence, ordinaryWithSameTypeIndicator, FuritenState.initial(), DISCARD).allowed(),
                "ordinary discards do not incorrectly reserve all four copies of their type");

        HandInput redFive = hand("123456m789p234s22p");
        WinContext redAdded = context(redFive, "5m", WinMethod.RON, Riichi.NONE, WinEvent.CHANKAN);
        WinEvaluator.Decision redKanWin = WinEvaluator.evaluate(redFive, redAdded, FuritenState.initial(), ADDED_KAN);
        check(redKanWin.allowed() && redAdded.winningTileId() == TileCatalog.RED_MAN_FIVE,
                "the actual red-five physical copy can be robbed from an added kan");
        ScoreResult redValue = redKanWin.handValue().orElseThrow();
        check(redValue.aka() == 1 && redValue.han() == 2 && redValue.fu() == 40
                        && redValue.points().payment().ronPoints() == 2_600,
                "kan-source validation preserves the red winning tile's value");
        expectThrows(IllegalArgumentException.class,
                () -> WinEvaluator.evaluate(redFive,
                        indicators(redAdded, List.of(freeTileOfType(redFive, type("5m"))), List.of()),
                        FuritenState.initial(), ADDED_KAN),
                "an ordinary-five indicator still conflicts with a robbed red-five kan");

        HandInput rinshanHand = hand("234m456p789s22p", Meld.Kind.CLOSED_KAN, "1111z");
        WinContext rinshan = context(rinshanHand, "4m", WinMethod.TSUMO, Riichi.RIICHI, WinEvent.RINSHAN);
        FuritenState blockedRon = FuritenState.initial().afterPassedRon(Riichi.NONE).afterPassedRon(Riichi.RIICHI);
        check(WinEvaluator.evaluate(rinshanHand, rinshan, blockedRon, SELF_DRAW).allowed(),
                "a supplied rinshan self-draw also ignores both missed-ron flags");
    }

    private static void validation() {
        expectThrows(IllegalArgumentException.class, () -> new FuritenState(Set.of(-1), false, false), "negative discard type");
        expectThrows(IllegalArgumentException.class, () -> new FuritenState(Set.of(34), false, false), "out-of-range discard type");
        expectThrows(IllegalArgumentException.class, () -> FuritenState.initial().afterDiscard(-1), "negative physical discard");
        expectThrows(IllegalArgumentException.class, () -> FuritenState.initial().afterDiscard(136), "out-of-range physical discard");
        expectThrows(NullPointerException.class, () -> new FuritenState(null, false, false), "null discard collection");
        expectThrows(NullPointerException.class, () -> FuritenState.initial().afterPassedRon(null), "null established-riichi fact");
        Set<Integer> mutable = new HashSet<>(Set.of(0));
        FuritenState copied = new FuritenState(mutable, false, false);
        mutable.add(1);
        check(copied.discardedTypes().equals(Set.of(0)), "history takes a defensive copy");
        expectThrows(UnsupportedOperationException.class, () -> copied.discardedTypes().add(1), "history cannot be mutated through getter");
        FuritenAnalyzer.Status status = FuritenAnalyzer.evaluate(hand("123456m789p34s22p"), copied);
        expectThrows(UnsupportedOperationException.class, () -> status.waitTypes().clear(), "wait results are immutable");
        expectThrows(UnsupportedOperationException.class, () -> status.discardedWaitTypes().add(0), "overlap results are immutable");
        expectThrows(IllegalArgumentException.class,
                () -> new FuritenAnalyzer.Status(Set.of(0), Set.of(1), false, false), "overlap must belong to current waits");

        HandInput h = hand("123456m789p345s22p");
        WinContext ron = context(h, "6m", WinMethod.RON, Riichi.NONE, WinEvent.NORMAL);
        WinContext tsumo = context(h, "6m", WinMethod.TSUMO, Riichi.NONE, WinEvent.NORMAL);
        WinContext kan = context(h, "6m", WinMethod.RON, Riichi.NONE, WinEvent.CHANKAN);
        expectThrows(IllegalArgumentException.class, () -> WinEvaluator.evaluate(h, ron, FuritenState.initial(), SELF_DRAW), "ron cannot be labelled self draw");
        expectThrows(IllegalArgumentException.class, () -> WinEvaluator.evaluate(h, tsumo, FuritenState.initial(), DISCARD), "self draw cannot be labelled discard");
        expectThrows(IllegalArgumentException.class, () -> WinEvaluator.evaluate(h, ron, FuritenState.initial(), ADDED_KAN), "added kan requires a chankan event");
        expectThrows(IllegalArgumentException.class, () -> WinEvaluator.evaluate(h, ron, FuritenState.initial(), CLOSED_KAN), "closed kan requires a chankan event");
        expectThrows(IllegalArgumentException.class, () -> WinEvaluator.evaluate(h, kan, FuritenState.initial(), DISCARD), "chankan cannot be labelled ordinary discard");
        expectThrows(IllegalArgumentException.class,
                () -> WinEvaluator.evaluate(hand("123456m789p34s22p"), ron, FuritenState.initial(), DISCARD), "evaluation requires a complete candidate hand");

        WinContext absentPhysicalCopy = new WinContext(WinMethod.RON, freeTileOfType(h, type("6m")),
                28, 27, Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
        expectThrows(IllegalArgumentException.class,
                () -> WinEvaluator.evaluate(h, absentPhysicalCopy, FuritenState.initial(), DISCARD),
                "matching winning type is insufficient when the exact physical winning ID is absent");
        HandInput declaredOnly = hand("456p789s11133z", Meld.Kind.CHI, "123m");
        WinContext declaredWinner = new WinContext(WinMethod.RON, declaredOnly.melds().getFirst().tiles().getFirst(),
                28, 27, Riichi.NONE, false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
        expectThrows(IllegalArgumentException.class,
                () -> WinEvaluator.evaluate(declaredOnly, declaredWinner, FuritenState.initial(), DISCARD),
                "a fixed declared tile cannot be presented as the new winning tile");
        expectThrows(IllegalArgumentException.class,
                () -> new WinEvaluator.Decision(Optional.empty(), Set.of()), "an allowed decision requires its score");
        ScoreResult value = WinEvaluator.evaluate(h, ron, FuritenState.initial(), DISCARD).handValue().orElseThrow();
        Set<WinEvaluator.Rejection> reasons = EnumSet.of(TEMPORARY_FURITEN);
        WinEvaluator.Decision decision = new WinEvaluator.Decision(Optional.of(value), reasons);
        reasons.clear();
        check(!decision.allowed() && decision.rejections().equals(Set.of(TEMPORARY_FURITEN)), "decision copies its rejection set");
        expectThrows(UnsupportedOperationException.class, () -> decision.rejections().clear(), "decision rejections are immutable");
    }

    private static HandInput hand(String concealed) { return hand(concealed, null, null); }

    private static HandInput hand(String concealed, Meld.Kind kind, String declared) {
        int[] copies = new int[TileCatalog.TYPE_COUNT];
        List<Meld> melds = kind == null ? List.of() : List.of(new Meld(kind, physical(declared, copies)));
        return new HandInput(physical(concealed, copies), melds);
    }

    private static HandInput append(HandInput before, int type) {
        List<Integer> tiles = new ArrayList<>(before.concealedTiles());
        tiles.add(freeTileOfType(before, type));
        return new HandInput(tiles, before.melds());
    }

    private static int freeTileOfType(HandInput hand, int type) {
        Set<Integer> held = new HashSet<>(hand.concealedTiles());
        hand.melds().forEach(meld -> held.addAll(meld.tiles()));
        for (int copy = 0; copy < 4; copy++) if (!held.contains(type * 4 + copy)) return type * 4 + copy;
        throw new IllegalArgumentException("Fixture has no free copy");
    }

    private static List<Integer> physical(String notation, int[] copies) {
        List<Integer> result = new ArrayList<>();
        StringBuilder ranks = new StringBuilder();
        for (char ch : notation.toCharArray()) {
            if (Character.isDigit(ch)) { ranks.append(ch); continue; }
            int offset = switch (ch) { case 'm' -> 0; case 'p' -> 9; case 's' -> 18; case 'z' -> 27; default -> throw new IllegalArgumentException("Bad fixture suit"); };
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
    private static int tile(String notation) { return type(notation) * 4; }

    private static WinContext context(HandInput hand, String winner, WinMethod method, Riichi riichi, WinEvent event) {
        int winningType = type(winner);
        int winningTile = hand.concealedTiles().stream().filter(id -> id / 4 == winningType).findFirst().orElseThrow();
        return new WinContext(method, winningTile, 28, 27, riichi, false, event, FirstTurn.NONE, List.of(), List.of());
    }

    private static WinContext indicators(WinContext c, List<Integer> dora, List<Integer> ura) {
        return new WinContext(c.method(), c.winningTileId(), c.seatWind(), c.roundWind(), c.riichi(), c.ippatsu(),
                c.event(), c.firstTurn(), dora, ura);
    }

    private static void rejected(WinEvaluator.Decision decision, WinEvaluator.Rejection... expected) {
        check(!decision.allowed(), "rejected decision cannot allow a win");
        check(decision.rejections().equals(Set.of(expected)), "expected rejection reasons " + Set.of(expected) + " but got " + decision.rejections());
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
        catch (Throwable failure) { throw new AssertionError("Furiten group failed: " + name, failure); }
    }
}
