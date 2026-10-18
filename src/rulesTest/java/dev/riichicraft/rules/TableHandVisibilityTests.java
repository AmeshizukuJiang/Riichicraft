package dev.riichicraft.rules;

import dev.riichicraft.table.TableHandVisibility;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

public final class TableHandVisibilityTests {
    private static int assertions;

    public static void main(String[] args) {
        assertions = 0;
        for (int count : new int[] {3, 4}) {
            var players = IntStream.range(0, count).mapToObj(i -> new UUID(count, i + 1)).toList();
            var hands = sourceHands(count);
            var draws = hands.stream().map(List::getLast).toList();
            UUID spectator = new UUID(count, 99);
            for (int round = 0; round < 30; round++) {
                for (int seat = 0; seat < count; seat++) {
                    var view = TableHandVisibility.project(players.get(seat), players, hands, draws, false);
                    check(view.ownSeat() == seat, "Stored UUID selects its exact seat independently of position or mount");
                    for (int other = 0; other < count; other++) {
                        check(view.hands().get(other).equals(other == seat ? hands.get(seat) : List.of()),
                                "Participant sees only its own hand, even while away from the chair");
                        check(view.drawnTiles().get(other) == (other == seat ? draws.get(seat) : -1),
                                "Draw identity follows the same visibility policy");
                    }
                    var reveal = TableHandVisibility.project(players.get(seat), players, hands, draws, true);
                    check(reveal.hands().equals(hands) && reveal.drawnTiles().equals(draws), "Settlement lays down all hands");
                }
                var observer = TableHandVisibility.project(spectator, players, hands, draws, false);
                check(observer.ownSeat() == -1 && observer.hands().equals(hands), "Unrostered spectators see all hands");
            }
            var mutableHands = new ArrayList<List<Integer>>();
            hands.forEach(hand -> mutableHands.add(new ArrayList<>(hand)));
            var mutableDraws = new ArrayList<>(draws);
            var observer = TableHandVisibility.project(spectator, players, mutableHands, mutableDraws, false);
            mutableHands.getFirst().clear(); mutableHands.clear(); mutableDraws.clear();
            check(observer.hands().equals(hands) && observer.drawnTiles().equals(draws), "Projection owns deep immutable copies");
            immutable(() -> observer.hands().clear());
            immutable(() -> observer.hands().getFirst().clear());
            immutable(() -> observer.drawnTiles().clear());
            var duplicates = new ArrayList<>(players); duplicates.set(1, players.getFirst());
            invalid(() -> TableHandVisibility.project(spectator, duplicates, hands, draws, false));
            var duplicateHands = new ArrayList<>(hands); duplicateHands.set(1, List.of(hands.getFirst().getFirst()));
            invalid(() -> TableHandVisibility.project(players.getFirst(), players, duplicateHands, draws, false));
            var wrongDraws = new ArrayList<>(draws); wrongDraws.set(1, hands.getFirst().getFirst());
            invalid(() -> TableHandVisibility.project(players.getFirst(), players, hands, wrongDraws, false));
            var longHands = new ArrayList<>(hands); longHands.set(1, IntStream.range(70, 85).boxed().toList());
            invalid(() -> TableHandVisibility.project(spectator, players, longHands, draws, false));
            invalid(() -> TableHandVisibility.project(spectator, players.subList(1, count), hands, draws, false));
            var outside = new ArrayList<>(hands); outside.set(1, List.of(136));
            invalid(() -> TableHandVisibility.project(spectator, players, outside, draws, true));
        }
        System.out.println("PASS table hand visibility: " + assertions + " assertions");
    }

    private static List<List<Integer>> sourceHands(int count) {
        return IntStream.range(0, count).mapToObj(seat -> IntStream.range(seat * 28, seat * 28 + (seat == 0 ? 14 : 13)).boxed().toList()).toList();
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void invalid(Runnable operation) {
        assertions++;
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid source must fail before any recipient projection");
    }
    private static void immutable(Runnable operation) {
        assertions++;
        try { operation.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Projection must be immutable");
    }
}
