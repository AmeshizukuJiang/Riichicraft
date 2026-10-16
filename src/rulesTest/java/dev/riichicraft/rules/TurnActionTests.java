package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static dev.riichicraft.rules.WinContext.Riichi;

/** Discard masks, exact red-copy locks and composition with ordinary calls and replacement draws. */
public final class TurnActionTests {
    private static int assertions;
    private TurnActionTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        run("draw discards conserve every exact physical tile", TurnActionTests::draws);
        run("riichi locks the actual drawn copy including red fives", TurnActionTests::riichiLocks);
        run("chi/pon kuikae exits and temporary furiten", TurnActionTests::calls);
        run("open kan requires replacement before discard", TurnActionTests::kan);
        run("invalid input and immutable results", TurnActionTests::invalid);
        System.out.printf("PASS turn discards: %d assertions; %.3f s%n", assertions, (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static void draws() {
        for (int seed = 0; seed < 160; seed++) {
            var deal = RoundWall.shuffled(seed);
            var draw = deal.wall().drawLive();
            var held = new ArrayList<>(deal.hands().getFirst().concealedTiles()); held.add(draw.tileId());
            HandInput hand = new HandInput(held, List.of());
            var history = FuritenState.initial().afterPassedRon(Riichi.NONE).afterDiscard(132);
            var mask = DiscardActions.afterDrawOptions(hand, draw.tileId(), Riichi.NONE);
            check(mask.equals(Set.copyOf(held)) && mask.size() == 14, "normal drawn turn offers all exact held tiles");
            for (int discarded : mask) {
                var result = DiscardActions.afterDraw(hand, draw.tileId(), Riichi.NONE, history, discarded);
                var identities = new HashSet<>(result.hand().concealedTiles()); identities.add(discarded);
                check(identities.equals(Set.copyOf(held)) && result.hand().concealedTiles().size() == 13, "exact discard and remaining hand conserve fourteen tiles");
                check(result.tsumogiri() == (discarded == draw.tileId()), "tsumogiri is exact physical equality");
                check(result.history().temporary() && result.history().discardedTypes().contains(discarded / 4), "discard records history without clearing temporary furiten");
                check(history.discardedTypes().equals(Set.of(33)) && hand.concealedTiles().size() == 14, "inputs remain unchanged");
            }
        }
    }

    private static void riichiLocks() {
        for (int red : List.of(16, 52, 88)) {
            var held = new ArrayList<Integer>();
            held.add(red); held.add(red + 1);
            for (int tile = 132; held.size() < 14; tile--) if (!held.contains(tile)) held.add(tile);
            HandInput hand = new HandInput(held, List.of());
            for (Riichi riichi : List.of(Riichi.RIICHI, Riichi.DOUBLE_RIICHI)) {
                for (int drawn : List.of(red, red + 1)) {
                    check(DiscardActions.afterDrawOptions(hand, drawn, riichi).equals(Set.of(drawn)), "only the drawn red/normal copy is selectable in riichi");
                    int other = drawn == red ? red + 1 : red;
                    expectInvalid(() -> DiscardActions.afterDraw(hand, drawn, riichi, FuritenState.initial(), other));
                    var history = FuritenState.initial().afterPassedRon(riichi).afterDraw();
                    var result = DiscardActions.afterDraw(hand, drawn, riichi, history, drawn);
                    check(result.tsumogiri() && result.history().riichiPass(), "riichi tsumogiri cannot clear permanent furiten");
                    check(result.hand().concealedTiles().contains(other), "undrawn same-kind copy stays held");
                }
            }
        }
    }

    private static void calls() {
        HandInput hand = hand(List.of(4, 8, 1, 12));
        var c = new CallActions.Context(0, 1, 9, 0, Riichi.NONE);
        var chi = CallActions.apply(hand, 0, c, new DiscardResponseWindow.Call(Meld.Kind.CHI, List.of(4, 8)));
        var history = FuritenState.initial().afterPassedRon(Riichi.NONE);
        expectInvalid(() -> DiscardActions.afterCall(chi, history, 1));
        expectInvalid(() -> DiscardActions.afterCall(chi, history, 12));
        for (int tile : chi.legalDiscardTiles()) {
            var result = DiscardActions.afterCall(chi, history, tile);
            check(result.hand().isWaiting() && !result.tsumogiri(), "chi exits directly to structural thirteen without a normal draw");
            check(result.hand().melds().equals(chi.hand().melds()) && result.history().temporary(), "call discard preserves group and temporary furiten");
        }
        HandInput ponHand = hand(List.of(16, 17, 18));
        var pon = CallActions.apply(ponHand, 19, c, new DiscardResponseWindow.Call(Meld.Kind.PON, List.of(16, 17)));
        expectInvalid(() -> DiscardActions.afterCall(pon, history, 18));
        int legal = pon.legalDiscardTiles().iterator().next();
        var result = DiscardActions.afterCall(pon, history, legal);
        check(result.hand().concealedTiles().contains(18), "same-kind unselected five survives pon's required other-kind discard");
        check(result.history().afterDraw().temporary() == false, "only later actual draw clears temporary furiten");
    }

    private static void kan() {
        HandInput hand = hand(List.of(16, 17, 18));
        var kan = CallActions.apply(hand, 19, new CallActions.Context(0, 1, 8, 0, Riichi.NONE),
                new DiscardResponseWindow.Call(Meld.Kind.OPEN_KAN, List.of(16, 17, 18)));
        expectInvalid(() -> DiscardActions.afterCall(kan, FuritenState.initial(), kan.hand().concealedTiles().getFirst()));
        expectInvalid(() -> DiscardActions.afterDrawOptions(kan.hand(), 16, Riichi.NONE));
        var held = new ArrayList<>(kan.hand().concealedTiles()); held.add(20);
        var complete = new HandInput(held, kan.hand().melds());
        var result = DiscardActions.afterDraw(complete, 20, Riichi.NONE, FuritenState.initial(), 20);
        check(result.hand().equals(kan.hand()) && result.tsumogiri(), "after actual replacement, tsumogiri returns to exact post-kan hand");
        check(result.hand().tileCounts()[4] == 4 && result.history().discardedTypes().equals(Set.of(5)), "kan tiles never become discard history");
    }

    private static void invalid() {
        HandInput thirteen = hand(List.of(0, 1));
        expectInvalid(() -> DiscardActions.afterDrawOptions(thirteen, 0, Riichi.NONE));
        var held = new ArrayList<>(thirteen.concealedTiles()); held.add(2);
        HandInput complete = new HandInput(held, List.of());
        expectInvalid(() -> DiscardActions.afterDrawOptions(complete, 3, Riichi.NONE));
        expectInvalid(() -> DiscardActions.afterDrawOptions(complete, -1, Riichi.NONE));
        expectInvalid(() -> DiscardActions.afterDraw(complete, 2, Riichi.NONE, FuritenState.initial(), 3));
        List<Meld> groups = List.of(new Meld(Meld.Kind.PON, List.of(108, 109, 110)));
        var small = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        HandInput open = new HandInput(small, groups);
        expectInvalid(() -> DiscardActions.afterDrawOptions(open, 10, Riichi.RIICHI));
        var mask = DiscardActions.afterDrawOptions(complete, 2, Riichi.NONE);
        expectUnsupported(mask::clear);
        var result = DiscardActions.afterDraw(complete, 2, Riichi.NONE, FuritenState.initial(), 2);
        expectUnsupported(() -> result.hand().concealedTiles().clear());
    }

    private static HandInput hand(List<Integer> required) {
        var held = new ArrayList<>(required);
        for (int tile = 135; held.size() < 13; tile--) if (!held.contains(tile)) held.add(tile);
        return new HandInput(held, List.of());
    }
    private static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private static void expectInvalid(Runnable action) { assertions++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejected discard"); }
    private static void expectUnsupported(Runnable action) { assertions++; try { action.run(); } catch (UnsupportedOperationException expected) { return; } throw new AssertionError("Expected immutable result"); }
    private static void run(String label, Runnable test) { try { test.run(); } catch (Throwable failure) { throw new AssertionError(label, failure); } }
}
