package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Whole-deck conservation and live/dead boundaries, independently of Minecraft. */
public final class RoundWallTests {
    private static int assertions;
    private RoundWallTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        run("deal, exact logical order and repeatable shuffle", RoundWallTests::dealing);
        run("four replacements, transferred tails and indicators", RoundWallTests::replacements);
        run("last-live versus last replacement sources", RoundWallTests::lastTile);
        run("mixed draw schedules conserve all 136 identities", RoundWallTests::mixedSchedules);
        run("open-kan execution and replacement draw compose", RoundWallTests::kanFlow);
        run("invalid permutations, counts and immutable snapshots", RoundWallTests::invalidInputs);
        System.out.printf("PASS round wall: %d assertions; %.3f s%n", assertions,
                (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static void dealing() {
        var deal = RoundWall.deal(TileCatalog.allTiles());
        check(deal.wall().liveCount() == 70 && deal.wall().deadTiles().size() == 14, "52 dealt + 70 live + 14 dead");
        for (int seat = 0; seat < 4; seat++) {
            List<Integer> expected = new ArrayList<>();
            for (int round = 0; round < 13; round++) expected.add(round * 4 + seat);
            check(deal.hands().get(seat).concealedTiles().equals(expected), "round-robin dealer-relative deal");
            check(deal.hands().get(seat).isWaiting() && deal.hands().get(seat).isClosed(), "each player starts with structural 13");
        }
        check(deal.wall().doraIndicators(5).equals(List.of(126, 128, 130, 132, 134)), "five dora slots follow logical mapping");
        check(deal.wall().uraIndicators(5).equals(List.of(127, 129, 131, 133, 135)), "five matching ura slots stay distinct");
        List<Integer> drawn = new ArrayList<>();
        RoundWall wall = deal.wall();
        for (int tile = 52; tile < 122; tile++) {
            var draw = wall.drawLive();
            check(draw.tileId() == tile, "live draws follow supplied permutation exactly");
            check(draw.source() == (tile == 121 ? RoundWall.Source.LAST_LIVE : RoundWall.Source.LIVE), "only final normal draw is last live");
            drawn.add(draw.tileId()); wall = draw.wall();
            audit(deal.hands(), drawn, wall);
        }
        check(wall.liveCount() == 0 && !wall.canDrawReplacement(), "exhausted wall cannot draw a replacement");
        expectState(wall::drawLive); expectState(wall::drawReplacement);
        check(deal.wall().liveCount() == 70 && deal.wall().liveTiles().getFirst() == 52, "old immutable wall remains unchanged");
        for (long seed : List.of(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 20261016L)) {
            var first = RoundWall.shuffled(seed); var again = RoundWall.shuffled(seed);
            check(first.hands().equals(again.hands()) && first.wall().liveTiles().equals(again.wall().liveTiles())
                    && first.wall().deadTiles().equals(again.wall().deadTiles()), "same seed reproduces entire deal/wall");
            audit(first.hands(), List.of(), first.wall());
        }
        check(!RoundWall.shuffled(0).wall().liveTiles().equals(RoundWall.shuffled(1).wall().liveTiles()), "different seeds change wall");
    }

    private static void replacements() {
        var deal = RoundWall.deal(TileCatalog.allTiles());
        RoundWall wall = deal.wall();
        List<Integer> drawn = new ArrayList<>();
        for (int kan = 0; kan < 4; kan++) {
            RoundWall before = wall;
            List<Integer> dora = before.doraIndicators(5), ura = before.uraIndicators(5);
            int tail = before.liveTiles().getLast();
            var draw = before.drawReplacement(); wall = draw.wall(); drawn.add(draw.tileId());
            check(draw.tileId() == 122 + kan && draw.source() == RoundWall.Source.RINSHAN, "replacement takes each reserved tile once");
            check(wall.liveCount() == before.liveCount() - 1, "each replacement consumes one live-tail tile");
            check(wall.deadTiles().size() == 14 && wall.deadTiles().get(kan) == tail, "tail replaces the consumed rinshan slot");
            check(!wall.liveTiles().contains(tail) && !wall.deadTiles().contains(draw.tileId()), "no cloned tail or replacement");
            check(wall.replacementDraws() == kan + 1 && wall.canDrawReplacement() == (kan < 3), "exactly four replacements available");
            check(wall.doraIndicators(5).equals(dora) && wall.uraIndicators(5).equals(ura), "indicators are never consumed or moved");
            check(before.replacementDraws() == kan && before.deadTiles().get(kan) == 122 + kan, "previous state not mutated");
            audit(deal.hands(), drawn, wall);
        }
        check(wall.liveCount() == 66, "four kans leave 66 ordinary draws after dealing");
        check(wall.deadTiles().subList(0, 4).equals(List.of(121, 120, 119, 118)), "all four live-tail transfers exact");
        expectState(wall::drawReplacement);
        var normal = wall.drawLive();
        check(normal.tileId() == 52 && normal.source() == RoundWall.Source.LIVE, "four replacements do not themselves end the wall");
    }

    private static void lastTile() {
        var deal = RoundWall.deal(TileCatalog.allTiles());
        RoundWall wall = deal.wall();
        List<Integer> drawn = new ArrayList<>();
        while (wall.liveCount() > 1) {
            var d = wall.drawLive(); drawn.add(d.tileId()); wall = d.wall();
        }
        check(wall.canDrawReplacement(), "one remaining live tile can back a legal replacement");
        var replacement = wall.drawReplacement(); drawn.add(replacement.tileId());
        check(replacement.source() == RoundWall.Source.RINSHAN && replacement.wall().liveCount() == 0,
                "last replacement is rinshan, never a normal last-live draw");
        check(replacement.wall().deadTiles().getFirst() == 121, "last live tile enters dead wall, never hand");
        audit(deal.hands(), drawn, replacement.wall());
        expectState(replacement.wall()::drawReplacement);
        var lastNormal = wall.drawLive();
        check(lastNormal.tileId() == 121 && lastNormal.source() == RoundWall.Source.LAST_LIVE,
                "normal draw of same pre-draw wall is separately marked last live");
        check(!lastNormal.wall().canDrawReplacement(), "after haitei there is no backing tile for kan");
    }

    private static void mixedSchedules() {
        for (int seed = 0; seed < 240; seed++) {
            var deal = RoundWall.shuffled(seed);
            RoundWall wall = deal.wall();
            List<Integer> drawn = new ArrayList<>();
            Random schedule = new Random(seed ^ 0x4d41484aL);
            int replacements = 0;
            while (wall.liveCount() > 0) {
                boolean rinshan = wall.canDrawReplacement() && schedule.nextInt(11) == 0;
                int before = wall.liveCount();
                var draw = rinshan ? wall.drawReplacement() : wall.drawLive();
                wall = draw.wall(); drawn.add(draw.tileId());
                if (rinshan) replacements++;
                check(wall.liveCount() == before - 1, "every draw consumes exactly one unit of live capacity");
                check(draw.source() == (rinshan ? RoundWall.Source.RINSHAN
                        : before == 1 ? RoundWall.Source.LAST_LIVE : RoundWall.Source.LIVE), "mixed schedule source exact");
                check(wall.replacementDraws() == replacements, "only real replacements increment count");
                audit(deal.hands(), drawn, wall);
            }
            check(drawn.size() == 70 && replacements <= 4, "all schedules retain total 70 draws after 52-tile deal");
            expectState(wall::drawLive); expectState(wall::drawReplacement);
        }
    }

    private static void kanFlow() {
        // Place all three held fives in seat 2 and the fourth in the first live slot.
        var order = new ArrayList<Integer>(java.util.Collections.nCopies(136, -1));
        order.set(2, 16); order.set(6, 17); order.set(10, 18); order.set(52, 19);
        Set<Integer> reserved = Set.of(16, 17, 18, 19); int next = 0;
        for (int index = 0; index < order.size(); index++) {
            if (order.get(index) >= 0) continue;
            while (reserved.contains(next)) next++;
            order.set(index, next++);
        }
        var deal = RoundWall.deal(order);
        var normal = deal.wall().drawLive();
        check(normal.tileId() == 19 && normal.wall().liveCount() == 69, "dealer first draw exposes scripted fourth five");
        var context = new CallActions.Context(0, 2, normal.wall().liveCount(), 0, WinContext.Riichi.NONE);
        var call = new DiscardResponseWindow.Call(Meld.Kind.OPEN_KAN, List.of(16, 17, 18));
        var application = CallActions.apply(deal.hands().get(2), normal.tileId(), context, call);
        check(application.needsReplacementDraw() && application.hand().concealedTiles().size() == 10,
                "open kan executes before replacement");
        var replacement = normal.wall().drawReplacement();
        var concealed = new ArrayList<>(application.hand().concealedTiles()); concealed.add(replacement.tileId());
        HandInput ready = new HandInput(concealed, application.hand().melds());
        check(ready.isComplete() && ready.concealedTiles().size() == 11, "replacement restores fourteen structural tiles");
        var hands = new ArrayList<>(deal.hands()); hands.set(2, ready);
        audit(hands, List.of(), replacement.wall());
        check(replacement.wall().liveCount() == 68, "normal plus replacement consume two live capacity");
        check(ready.tileCounts()[4] == 4 && ready.melds().getFirst().tiles().contains(16), "red five remains exactly once inside open kan");
    }

    private static void invalidInputs() {
        var duplicate = new ArrayList<>(TileCatalog.allTiles()); duplicate.set(135, 0);
        expectInvalid(() -> RoundWall.deal(duplicate));
        expectInvalid(() -> RoundWall.deal(TileCatalog.allTiles().subList(0, 135)));
        var bad = new ArrayList<>(TileCatalog.allTiles()); bad.set(135, 136);
        expectInvalid(() -> RoundWall.deal(bad));
        var deal = RoundWall.deal(TileCatalog.allTiles());
        expectInvalid(() -> deal.wall().doraIndicators(0));
        expectInvalid(() -> deal.wall().doraIndicators(6));
        expectInvalid(() -> deal.wall().uraIndicators(-1));
        expectUnsupported(() -> deal.hands().clear());
        expectUnsupported(() -> deal.wall().liveTiles().clear());
        expectUnsupported(() -> deal.wall().deadTiles().clear());
        expectUnsupported(() -> deal.wall().doraIndicators(1).clear());
        expectUnsupported(() -> deal.wall().uraIndicators(5).clear());
        var source = new ArrayList<>(TileCatalog.allTiles());
        var isolated = RoundWall.deal(source); source.clear();
        check(isolated.wall().liveCount() == 70 && isolated.hands().getFirst().concealedTiles().size() == 13,
                "mutating caller's original deck cannot mutate a deal");
    }

    private static void audit(List<HandInput> hands, List<Integer> external, RoundWall wall) {
        List<Integer> all = new ArrayList<>(external);
        hands.forEach(h -> { all.addAll(h.concealedTiles()); h.melds().forEach(m -> all.addAll(m.tiles())); });
        all.addAll(wall.liveTiles()); all.addAll(wall.deadTiles());
        check(all.size() == 136 && new HashSet<>(all).size() == 136, "all 136 exact identities conserved with no duplicate owner");
        check(new HashSet<>(all).equals(new HashSet<>(TileCatalog.allTiles())), "no omitted or invented tile");
        check(wall.deadTiles().size() == 14, "dead wall always fourteen");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void expectInvalid(Runnable action) {
        assertions++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid wall/count");
    }
    private static void expectState(Runnable action) {
        assertions++; try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected unavailable draw");
    }
    private static void expectUnsupported(Runnable action) {
        assertions++; try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected immutable snapshot");
    }
    private static void run(String label, Runnable test) {
        try { test.run(); } catch (Throwable failure) { throw new AssertionError(label, failure); }
    }
}
