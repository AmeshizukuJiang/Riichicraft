package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.WinContext.Riichi;

/** Exact physical combinations, kuikae, replacement obligations and response-window integration. */
public final class CallActionsTests {
    private static int assertions;
    private CallActionsTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        run("all suits, ranks, directions and physical chi alternatives", CallActionsTests::chiCombinations);
        run("pon/open-kan choices, reds and immutable execution", CallActionsTests::ponAndKan);
        run("same-tile and alternative-sequence kuikae", CallActionsTests::kuikae);
        run("last discard, riichi and kan/meld limits", CallActionsTests::restrictions);
        run("random calls conserve exact physical identities", CallActionsTests::randomConservation);
        run("generated calls through the authoritative response window", CallActionsTests::responseWindow);
        run("invalid hands, facts and submitted choices", CallActionsTests::invalidInputs);
        System.out.printf("PASS call actions: %d assertions; %.3f s%n", assertions,
                (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static void chiCombinations() {
        for (int suit = 0; suit < 3; suit++) {
            for (int start = 0; start <= 6; start++) {
                for (int position = 0; position < 3; position++) {
                    int target = suit * 9 + start + position;
                    List<Integer> otherTypes = new ArrayList<>();
                    for (int offset = 0; offset < 3; offset++) {
                        if (offset != position) otherTypes.add(suit * 9 + start + offset);
                    }
                    int a = otherTypes.get(0) * 4;
                    int b = otherTypes.get(1) * 4;
                    HandInput hand = fill(List.of(a, a + 1, b, b + 1, b + 2), Set.of(target * 4), List.of());
                    Set<Call> expected = new HashSet<>();
                    for (int ai = 0; ai < 2; ai++) for (int bi = 0; bi < 3; bi++) {
                        expected.add(new Call(Meld.Kind.CHI, List.of(a + ai, b + bi)));
                    }
                    for (int discarder = 0; discarder < 4; discarder++) {
                        for (int relative = 1; relative < 4; relative++) {
                            int caller = (discarder + relative) % 4;
                            var context = context(discarder, caller, 1, 0, Riichi.NONE);
                            Set<Call> actual = CallActions.options(hand, target * 4, context);
                            check(actual.equals(relative == 1 ? expected : Set.of()), "only the next seat can chi; all 2x3 exact choices remain");
                            for (Call choice : actual) verifyApplication(hand, target * 4, context, choice);
                        }
                    }
                }
            }
        }
    }

    private static void ponAndKan() {
        for (int type = 0; type < 34; type++) {
            for (int discardedCopy = 0; discardedCopy < 4; discardedCopy++) {
                int tile = type * 4 + discardedCopy;
                List<Integer> others = new ArrayList<>();
                for (int copy = 0; copy < 4; copy++) if (copy != discardedCopy) others.add(type * 4 + copy);
                HandInput hand = fill(others, Set.of(tile), List.of());
                var context = context(3, 1, 1, 3, Riichi.NONE);
                Set<Call> options = CallActions.options(hand, tile, context);
                check(options.size() == 4, "three pon pairs and one kan from the other three physical copies");
                for (int missing : others) {
                    List<Integer> pair = others.stream().filter(id -> id != missing).toList();
                    Call pon = new Call(Meld.Kind.PON, pair);
                    check(options.contains(pon), "every pon alternative including red retention is offered");
                    var applied = verifyApplication(hand, tile, context, pon);
                    check(applied.hand().concealedTiles().contains(missing), "unselected third copy stays concealed");
                    check(!applied.legalDiscardTiles().contains(missing), "same-kind remaining tile cannot be discarded immediately");
                }
                Call kan = new Call(Meld.Kind.OPEN_KAN, others);
                var applied = verifyApplication(hand, tile, context, kan);
                check(applied.needsReplacementDraw() && applied.hand().isWaiting(), "kan awaits replacement before any discard");
                check(applied.legalDiscardTiles().isEmpty() && applied.forbiddenDiscardTypes().isEmpty(), "no premature discard or inherited kuikae after kan");
                check(applied.meld().tiles().containsAll(others) && applied.meld().tiles().contains(tile), "all four exact copies form kan");
            }
        }
    }

    private static void kuikae() {
        for (int suit = 0; suit < 3; suit++) {
            int base = suit * 9;
            // 23 calls 1: both 1 and 4 are forbidden, by type including red alternatives.
            HandInput low = fill(List.of((base + 1) * 4, (base + 2) * 4, base * 4 + 1, (base + 3) * 4), Set.of(base * 4), List.of());
            var c = context(0, 1, 8, 0, Riichi.NONE);
            var a = CallActions.apply(low, base * 4, c, new Call(Meld.Kind.CHI, List.of((base + 1) * 4, (base + 2) * 4)));
            check(a.forbiddenDiscardTypes().equals(Set.of(base, base + 3)), "23 calls 1 forbids 1/4");
            check(!a.legalDiscardTiles().contains(base * 4 + 1) && !a.legalDiscardTiles().contains((base + 3) * 4), "both forbidden kinds excluded");
            // 78 calls 9: both 6 and 9 forbidden; the rule is symmetric.
            HandInput high = fill(List.of((base + 6) * 4, (base + 7) * 4, (base + 5) * 4, (base + 8) * 4 + 1), Set.of((base + 8) * 4), List.of());
            a = CallActions.apply(high, (base + 8) * 4, c, new Call(Meld.Kind.CHI, List.of((base + 6) * 4, (base + 7) * 4)));
            check(a.forbiddenDiscardTypes().equals(Set.of(base + 5, base + 8)), "78 calls 9 forbids 6/9");
            // 46 calls 5: only 5 is forbidden; any red/normal copy is the same type.
            int five = (base + 4) * 4;
            HandInput middle = fill(List.of((base + 3) * 4, (base + 5) * 4, five + 1, five + 2), Set.of(five), List.of());
            a = CallActions.apply(middle, five, c, new Call(Meld.Kind.CHI, List.of((base + 3) * 4, (base + 5) * 4)));
            check(a.forbiddenDiscardTypes().equals(Set.of(base + 4)), "middle chi forbids only the called kind");
            check(!a.legalDiscardTiles().contains(five + 1) && !a.legalDiscardTiles().contains(five + 2), "red and normal fives share kuikae");
            // 12 calls 3 cannot forbid a tile across the suit boundary.
            HandInput edge = fill(List.of(base * 4, (base + 1) * 4), Set.of((base + 2) * 4), List.of());
            a = CallActions.apply(edge, (base + 2) * 4, c, new Call(Meld.Kind.CHI, List.of(base * 4, (base + 1) * 4)));
            check(a.forbiddenDiscardTypes().equals(Set.of(base + 2)), "no imaginary rank zero");
        }
        List<Meld> groups = List.of(pon(27), pon(28), pon(29));
        HandInput stuck = new HandInput(List.of(4, 8, 1, 12), groups);
        Call chi = new Call(Meld.Kind.CHI, List.of(4, 8));
        check(!CallActions.options(stuck, 0, context(0, 1, 9, 0, Riichi.NONE)).contains(chi), "a chi leaving only kuikae discards is not offered");
        expectInvalid(() -> CallActions.apply(stuck, 0, context(0, 1, 9, 0, Riichi.NONE), chi));
        HandInput possible = new HandInput(List.of(4, 8, 1, 16), groups);
        var a = CallActions.apply(possible, 0, context(0, 1, 9, 0, Riichi.NONE), chi);
        check(a.legalDiscardTiles().equals(Set.of(16)), "only the legal fourth tile remains selectable");
        var afterDiscard = new ArrayList<>(a.hand().concealedTiles());
        afterDiscard.remove(Integer.valueOf(16));
        HandInput waiting = new HandInput(afterDiscard, a.hand().melds());
        check(waiting.isWaiting() && waiting.concealedTiles().equals(List.of(1)), "legal immediate discard returns structural 13");
    }

    private static void restrictions() {
        HandInput hand = fill(List.of(16, 17, 18), Set.of(19), List.of());
        for (Riichi riichi : Riichi.values()) {
            Set<Call> choices = CallActions.options(hand, 19, context(2, 3, 1, 0, riichi));
            check(choices.isEmpty() == (riichi != Riichi.NONE), "riichi/double-riichi forbid all calls");
        }
        check(CallActions.options(hand, 19, context(2, 3, 0, 0, Riichi.NONE)).isEmpty(), "last live discard permits no calls");
        Set<Call> fifth = CallActions.options(hand, 19, context(2, 3, 1, 4, Riichi.NONE));
        check(fifth.size() == 3 && fifth.stream().allMatch(c -> c.kind() == Meld.Kind.PON), "four established kans exclude only another kan");
        List<Meld> four = List.of(pon(27), pon(28), pon(29), pon(30));
        check(CallActions.options(new HandInput(List.of(0), four), 1, context(0, 1, 1, 0, Riichi.NONE)).isEmpty(), "no fifth meld");
        List<Meld> three = List.of(pon(27), pon(28), pon(29));
        HandInput small = new HandInput(List.of(16, 17, 18, 120), three);
        var applied = CallActions.apply(small, 19, context(0, 2, 1, 0, Riichi.NONE), new Call(Meld.Kind.OPEN_KAN, List.of(16, 17, 18)));
        check(applied.hand().concealedTiles().equals(List.of(120)) && applied.hand().melds().size() == 4, "fourth meld kan leaves one concealed tile before replacement");
        List<Meld> ownKan = List.of(new Meld(Meld.Kind.CLOSED_KAN, List.of(108, 109, 110, 111)));
        HandInput withKan = fill(List.of(16, 17), Set.of(19), ownKan);
        check(CallActions.options(withKan, 19, context(0, 2, 1, 1, Riichi.NONE)).size() == 1, "concealed kan leaves menzen but does not block subsequent pon");
        check(CallActions.options(withKan, 19, context(0, 2, 1, 1, Riichi.RIICHI)).isEmpty(), "riichi with closed kan still cannot call");
    }

    private static void randomConservation() {
        for (int seed = 0; seed < 350; seed++) {
            RoundWall.Deal deal = RoundWall.shuffled(seed);
            int tile = deal.wall().drawLive().tileId();
            int discarder = seed % 4;
            for (int relative = 1; relative < 4; relative++) {
                int seat = (discarder + relative) % 4;
                HandInput hand = deal.hands().get(seat);
                var context = context(discarder, seat, 69, 0, Riichi.NONE);
                Set<Call> options = CallActions.options(hand, tile, context);
                check(options.equals(referenceCandidates(hand, tile, context)), "brute-force physical subsets agree with generator");
                for (Call call : options) verifyApplication(hand, tile, context, call);
            }
        }
    }

    private static Set<Call> referenceCandidates(HandInput hand, int tile, CallActions.Context c) {
        // Independent enumeration of every 2/3-held-tile subset; Meld validates type patterns.
        Set<Call> expected = new HashSet<>();
        List<Integer> held = hand.concealedTiles();
        for (int a = 0; a < held.size(); a++) for (int b = a + 1; b < held.size(); b++) {
            for (Meld.Kind kind : List.of(Meld.Kind.CHI, Meld.Kind.PON)) {
                if (kind == Meld.Kind.CHI && c.callerSeat() != (c.discarderSeat() + 1) % 4) continue;
                try {
                    new Meld(kind, List.of(held.get(a), held.get(b), tile));
                    // In a closed 13-tile hand, at least one of the 11 remaining tiles is legal.
                    expected.add(new Call(kind, List.of(held.get(a), held.get(b))));
                } catch (IllegalArgumentException ignored) { }
            }
            for (int d = b + 1; d < held.size(); d++) {
                try {
                    new Meld(Meld.Kind.OPEN_KAN, List.of(held.get(a), held.get(b), held.get(d), tile));
                    expected.add(new Call(Meld.Kind.OPEN_KAN, List.of(held.get(a), held.get(b), held.get(d))));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return expected;
    }

    private static void responseWindow() {
        // Both next-seat chi and opposite-seat pon exist; generated choices flow unchanged through resolution.
        Set<Integer> used = new HashSet<>();
        int discard = 8;
        used.add(discard);
        HandInput chi = fillAndReserve(List.of(0, 4), used);
        HandInput pon = fillAndReserve(List.of(9, 10), used);
        HandInput none = fillAndReserve(List.of(), used);
        List<Participant> players = new ArrayList<>();
        for (int seat = 1; seat < 4; seat++) {
            HandInput hand = List.of(chi, pon, none).get(seat - 1);
            var win = new WinContext(WinContext.WinMethod.RON, discard, 27 + seat, 27,
                    Riichi.NONE, false, WinContext.WinEvent.NORMAL, WinContext.FirstTurn.NONE, List.of(), List.of());
            players.add(new Participant(seat, hand, win, FuritenState.initial(),
                    CallActions.options(hand, discard, context(0, seat, 12, 0, Riichi.NONE))));
        }
        var window = new DiscardResponseWindow(new Id(new java.util.UUID(0, 17), 3), 0, discard, players);
        for (int seat : List.of(1, 2, 3)) {
            if (!window.pendingSeats().contains(seat)) continue;
            Choice choice = seat == 1 ? new Call(Meld.Kind.CHI, List.of(0, 4))
                    : seat == 2 ? new Call(Meld.Kind.PON, List.of(9, 10)) : Simple.PASS;
            check(window.submit(window.id(), seat, choice) == Submission.ACCEPTED, "generated physical call accepted by collector");
        }
        var selected = window.resolution().orElseThrow().call().orElseThrow();
        check(selected.seat() == 2 && selected.choice().kind() == Meld.Kind.PON, "pon wins after collecting generated calls");
        var application = CallActions.apply(pon, discard, context(0, 2, 12, 0, Riichi.NONE), selected.choice());
        check(application.providerSeat() == 0 && application.callerSeat() == 2 && application.claimedTileId() == discard,
                "provider and exact discard preserved for river/pao owner");
    }

    private static void invalidInputs() {
        HandInput hand = fill(List.of(0, 1), Set.of(2), List.of());
        var c = context(0, 2, 10, 0, Riichi.NONE);
        expectInvalid(() -> CallActions.options(hand, 0, c));
        expectInvalid(() -> CallActions.options(hand, 136, c));
        var fourteen = new ArrayList<>(hand.concealedTiles()); fourteen.add(2);
        expectInvalid(() -> CallActions.options(new HandInput(fourteen, List.of()), 3, c));
        expectInvalid(() -> CallActions.apply(hand, 2, c, new Call(Meld.Kind.PON, List.of(0, 3))));
        expectInvalid(() -> context(0, 0, 10, 0, Riichi.NONE));
        expectInvalid(() -> context(-1, 2, 10, 0, Riichi.NONE));
        expectInvalid(() -> context(0, 4, 10, 0, Riichi.NONE));
        expectInvalid(() -> context(0, 2, -1, 0, Riichi.NONE));
        expectInvalid(() -> context(0, 2, 71, 0, Riichi.NONE));
        expectInvalid(() -> context(0, 2, 10, -1, Riichi.NONE));
        expectInvalid(() -> context(0, 2, 10, 5, Riichi.NONE));
        HandInput open = fill(List.of(0, 1), Set.of(2), List.of(pon(27)));
        expectInvalid(() -> CallActions.options(open, 2, context(0, 2, 10, 0, Riichi.RIICHI)));
        HandInput kan = fill(List.of(0, 1), Set.of(2), List.of(new Meld(Meld.Kind.CLOSED_KAN, List.of(108, 109, 110, 111))));
        expectInvalid(() -> CallActions.options(kan, 2, c));
        Set<Call> offered = CallActions.options(hand, 2, c);
        expectUnsupported(() -> offered.clear());
        var applied = CallActions.apply(hand, 2, c, new Call(Meld.Kind.PON, List.of(0, 1)));
        expectUnsupported(() -> applied.legalDiscardTiles().clear());
        expectUnsupported(() -> applied.forbiddenDiscardTypes().clear());
        expectUnsupported(() -> applied.hand().concealedTiles().clear());
    }

    private static CallActions.Application verifyApplication(HandInput before, int tile, CallActions.Context context, Call call) {
        var after = CallActions.apply(before, tile, context, call);
        Set<Integer> expected = allTiles(before); expected.add(tile);
        Set<Integer> actual = allTiles(after.hand());
        check(actual.equals(expected), "call conserves held identities plus the single claimed discard");
        check(after.hand().melds().subList(0, before.melds().size()).equals(before.melds()), "existing melds unchanged");
        check(after.hand().melds().size() == before.melds().size() + 1, "exactly one new meld");
        check(!after.hand().isClosed() && after.meld().isOpen(), "all ordinary discard calls break menzen");
        check(after.hand().isComplete() != after.needsReplacementDraw(), "chi/pon discard now; kan waits for replacement");
        check(after.claimedTileId() == tile && after.providerSeat() == context.discarderSeat()
                && after.callerSeat() == context.callerSeat(), "exact provenance retained");
        check(before.concealedTiles().containsAll(call.handTiles()), "original immutable hand still owns selected tiles");
        if (!after.needsReplacementDraw()) {
            check(!after.legalDiscardTiles().isEmpty(), "every offered chi/pon has an exit discard");
            for (int held : after.hand().concealedTiles()) {
                check(after.legalDiscardTiles().contains(held) == !after.forbiddenDiscardTypes().contains(TileCatalog.typeOf(held)),
                        "discard mask follows type-based kuikae for every physical tile");
            }
        }
        return after;
    }

    private static Set<Integer> allTiles(HandInput hand) {
        Set<Integer> result = new HashSet<>(hand.concealedTiles());
        hand.melds().forEach(m -> result.addAll(m.tiles())); return result;
    }

    private static HandInput fill(List<Integer> required, Set<Integer> excluded, List<Meld> groups) {
        Set<Integer> used = new HashSet<>(excluded); used.addAll(required); groups.forEach(m -> used.addAll(m.tiles()));
        List<Integer> held = new ArrayList<>(required);
        // Isolated high honors avoid manufacturing suited chi combinations in exact fixtures.
        for (int tile = 135; held.size() < 13 - 3 * groups.size() && tile >= 0; tile--) {
            if (used.add(tile)) held.add(tile);
        }
        return new HandInput(held, groups);
    }

    private static HandInput fillAndReserve(List<Integer> required, Set<Integer> used) {
        HandInput result = fill(required, used, List.of());
        check(result.concealedTiles().stream().noneMatch(used::contains), "fixture physical hands do not overlap");
        used.addAll(result.concealedTiles()); return result;
    }
    private static Meld pon(int type) { return new Meld(Meld.Kind.PON, List.of(type * 4, type * 4 + 1, type * 4 + 2)); }
    private static CallActions.Context context(int from, int to, int live, int kans, Riichi riichi) {
        return new CallActions.Context(from, to, live, kans, riichi);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void expectInvalid(Runnable action) {
        assertions++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid action/facts");
    }
    private static void expectUnsupported(Runnable action) {
        assertions++; try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected immutable result");
    }
    private static void run(String label, Runnable test) {
        try { test.run(); } catch (Throwable failure) { throw new AssertionError(label, failure); }
    }
}
