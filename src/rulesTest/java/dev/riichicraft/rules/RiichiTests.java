package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.WinContext.*;

/** Declaration boundaries, exact discard-window binding, multi-ron cancellation and ippatsu lifetime. */
public final class RiichiTests {
    private static int assertions;
    private static final UUID ROUND = UUID.fromString("6b49331f-99fc-4e90-a117-aaeabf020037");
    private static final int[] ORPHANS = {0,8,9,17,18,26,27,28,29,30,31,32,33};
    private RiichiTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        run("declaration discards, structural waits and thresholds", RiichiTests::options);
        run("pending state and exactly bound response windows", RiichiTests::pending);
        run("single/double/triple ron cancel declaration without payment", RiichiTests::ronCancellation);
        run("called declaration establishes riichi and cancels ippatsu", RiichiTests::calledDeclaration);
        run("double riichi and ippatsu lifetime", RiichiTests::lifetime);
        run("negative facts and immutable snapshots", RiichiTests::validation);
        System.out.printf("PASS riichi: %d assertions; %.3f s%n", assertions, (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static void options() {
        Pool p = new Pool(); HandInput waiting = p.hand("123m456p789s22m34p"); int drawn = p.take(27);
        HandInput hand = plus(waiting, drawn); var initial = RiichiState.initial();
        var options = initial.declarationOptions(hand, drawn, context(0, 1_000, 4, 0, false));
        check(options.keySet().equals(Set.of(drawn)), "this isolated extra east is the only declaration discard");
        check(options.get(drawn).equals(Set.of(10,13)), "declaration preserves the two-sided 2p/5p wait");
        for (int points : List.of(-100, 0, 999)) check(initial.declarationOptions(hand, drawn, context(0, points, 4, 0, false)).isEmpty(), "need at least exactly 1000 points");
        for (int live = 0; live < 4; live++) check(initial.declarationOptions(hand, drawn, context(0, 25_000, live, 0, false)).isEmpty(), "need four future normal draw opportunities before own turn");
        check(!initial.declarationOptions(hand, drawn, context(0, 25_000, 4, 0, false)).isEmpty(), "four live tiles are sufficient");
        var history = FuritenState.initial().afterDiscard(40).afterPassedRon(Riichi.NONE);
        var declared = initial.declare(hand, drawn, context(0, 1_000, 4, 0, false), history, drawn, new Id(ROUND, 0));
        check(declared.discard().history().temporary() && declared.discard().history().discardedTypes().contains(10), "furiten is not a riichi eligibility veto or silently cleared");
        check(FuritenAnalyzer.evaluate(declared.discard().hand(), declared.discard().history()).discardFuriten(), "declaration remains legal despite real discard furiten");

        p = new Pool(); hand = p.hand("123m456p789s22555m"); int five = hand.concealedTiles().stream().filter(t -> t / 4 == 4).findFirst().orElseThrow();
        var twins = initial.declarationOptions(hand, five, context(0, 25_000, 40, 3, true));
        for (int physical : hand.concealedTiles()) if (physical / 4 == 4) check(twins.get(physical).equals(Set.of(1,4)), "red/normal fives are distinct discard choices with identical structural waits");

        p = new Pool(); waiting = p.hand("112233m445566p7s"); drawn = p.take(27); hand = plus(waiting, drawn);
        check(initial.declarationOptions(hand, drawn, context(1, 25_000, 20, 1, false)).get(drawn).equals(Set.of(24)), "seven-pairs declaration wait");
        p = new Pool(); List<Integer> terminals = new ArrayList<>(); for (int type : ORPHANS) terminals.add(p.take(type));
        waiting = new HandInput(terminals, List.of()); drawn = p.take(1); hand = plus(waiting, drawn);
        check(initial.declarationOptions(hand, drawn, context(2, 25_000, 20, 1, false)).get(drawn).size() == 13, "kokushi thirteen-sided declaration");

        p = new Pool(); waiting = p.hand("1111m456p789sEEE"); drawn = p.take(30); hand = plus(waiting, drawn);
        check(!initial.declarationOptions(hand, drawn, context(0, 25_000, 20, 1, false)).containsKey(drawn), "self-held-four fifth-copy tanki cannot declare by discarding the extra north");

        p = new Pool(); List<Integer> closed = p.tiles("123m456p789sE"); List<Integer> quad = List.of(p.take(31),p.take(31),p.take(31),p.take(31));
        HandInput withKan = new HandInput(closed, List.of(new Meld(Meld.Kind.CLOSED_KAN, quad)));
        drawn = p.take(30); hand = plus(withKan, drawn);
        check(initial.declarationOptions(hand, drawn, context(0, 25_000, 20, 0, true)).get(drawn).equals(Set.of(27)), "closed kan preserves menzen and riichi eligibility");
        HandInput open = new HandInput(closed, List.of(new Meld(Meld.Kind.PON, quad.subList(0,3))));
        hand = plus(open, drawn);
        check(initial.declarationOptions(hand, drawn, context(0, 25_000, 20, 0, true)).isEmpty(), "open pon forbids riichi");
    }

    private static void pending() {
        Fixture f = fixture(0, new Id(ROUND, 10), false, 0, false);
        RiichiState state = f.declaration().state();
        check(state.phase() == RiichiState.Phase.PENDING && state.establishedRiichi() == Riichi.NONE && !state.ippatsu(), "pending declaration grants neither riichi nor ippatsu");
        check(state.pending().orElseThrow().kind() == Riichi.DOUBLE_RIICHI, "planned double riichi is stored separately from established yaku");
        check(f.declaration().discard().discardedTileId() == f.window().discardedTileId() && f.declaration().discard().hand().isWaiting(), "declaration executes its exact ordinary discard");
        expectState(() -> state.resolve(f.window()));
        expectState(state::afterOwnDraw); expectState(state::afterOwnDiscard); expectState(state::afterCallEstablished);
        Fixture wrongId = fixture(0, new Id(ROUND, 11), false, 0, false);
        finishPass(wrongId.window()); expectInvalid(() -> state.resolve(wrongId.window()));
        Fixture wrongRound = fixture(0, new Id(new UUID(0, 42), 10), false, 0, false);
        finishPass(wrongRound.window()); expectInvalid(() -> state.resolve(wrongRound.window()));
        Fixture wrongSeat = fixture(3, new Id(ROUND, 10), false, 0, false);
        finishPass(wrongSeat.window()); expectInvalid(() -> state.resolve(wrongSeat.window()));
        List<Participant> altered = f.windowPlayers().stream().map(player -> new Participant(player.seat(), player.hand(),
                ronContext(4, player.context().seatWind()), player.history(), Set.of())).toList();
        var wrongTile = new DiscardResponseWindow(f.window().id(), 0, 4, altered);
        finishPass(wrongTile); expectInvalid(() -> state.resolve(wrongTile));
        check(state.phase() == RiichiState.Phase.PENDING && f.window().resolution().isEmpty(), "rejected resolutions never mutate pending state or original window");
        finishPass(f.window());
        var established = state.resolve(f.window());
        check(established.pointsDelta() == -1_000 && established.depositSticksAdded() == 1, "only completed non-ron window requests the debit and one deposit");
        check(established.state().establishedRiichi() == Riichi.DOUBLE_RIICHI && established.state().ippatsu(), "no-claim declaration establishes double riichi with ippatsu");
        check(state.phase() == RiichiState.Phase.PENDING && established.state().pending().isEmpty(), "immutable old pending snapshot and cleared new pending state");
        expectState(() -> established.state().resolve(f.window()));
        expectInvalid(() -> established.state().declare(f.hand(), f.drawn(), context(0,25_000,40,1,false),
                FuritenState.initial(), f.drawn(), new Id(ROUND, 12)));
    }

    private static void ronCancellation() {
        for (int discarder = 0; discarder < 4; discarder++) {
            for (int mask = 1; mask < 8; mask++) {
                Fixture f = fixture(discarder, new Id(ROUND, discarder * 8L + mask + 20), false, 1, false);
                for (int relative = 3; relative >= 1; relative--) {
                    int seat = (discarder + relative) % 4;
                    check(f.window().submit(f.window().id(), seat, (mask & 1 << (relative - 1)) != 0 ? Simple.RON : Simple.PASS) == Submission.ACCEPTED, "all ron subsets resolve independent of arrival order");
                }
                var resolution = f.window().resolution().orElseThrow();
                check(resolution.ronSeats().size() == Integer.bitCount(mask), "all one/two/three ron winners retained");
                var cancelled = f.declaration().state().resolve(f.window());
                check(cancelled.pointsDelta() == 0 && cancelled.depositSticksAdded() == 0, "declaration tile ron adds no new debit or deposit, including triple ron");
                check(cancelled.state().phase() == RiichiState.Phase.UNDECLARED
                        && cancelled.state().establishedRiichi() == Riichi.NONE && !cancelled.state().ippatsu(), "ron cancels pending declaration");
            }
        }
    }

    private static void calledDeclaration() {
        for (int discarder = 0; discarder < 4; discarder++) {
            Fixture f = fixture(discarder, new Id(ROUND, 100 + discarder), true, 0, false);
            int caller = (discarder + 1) % 4;
            var chosen = f.windowPlayers().getFirst().calls().stream().filter(c -> c.kind() == Meld.Kind.PON).findFirst().orElseThrow();
            for (int relative = 3; relative >= 1; relative--) {
                int seat = (discarder + relative) % 4;
                if (f.window().pendingSeats().contains(seat)) {
                    check(f.window().submit(f.window().id(), seat, seat == caller ? chosen : Simple.PASS) == Submission.ACCEPTED, "declaration-tile pon collected");
                }
            }
            check(f.window().resolution().orElseThrow().outcome() == Outcome.CALL, "declaration tile is called rather than ronned");
            var result = f.declaration().state().resolve(f.window());
            check(result.pointsDelta() == -1_000 && result.depositSticksAdded() == 1, "called declaration still pays its stick");
            check(result.state().establishedRiichi() == Riichi.DOUBLE_RIICHI && !result.state().ippatsu(), "double-riichi remains while its ippatsu is immediately cancelled");
        }
    }

    private static void lifetime() {
        for (int discards : List.of(0, 1, 12)) for (boolean called : List.of(false, true)) {
            Fixture f = fixture(1, new Id(ROUND, 150 + discards * 2 + (called ? 1 : 0)), false, discards, called);
            finishPass(f.window()); var state = f.declaration().state().resolve(f.window()).state();
            Riichi expected = discards == 0 && !called ? Riichi.DOUBLE_RIICHI : Riichi.RIICHI;
            check(state.establishedRiichi() == expected && state.ippatsu(), "only unbroken first discard yields double riichi");
            check(state.afterOwnDraw().ippatsu(), "next own draw may still win by ippatsu");
            check(!state.afterOwnDraw().afterOwnDiscard().ippatsu(), "discard after next draw ends ippatsu");
            check(!state.afterCallEstablished().ippatsu(), "any established chi/pon/kan ends ippatsu");
            check(!state.afterCallEstablished().afterOwnDraw().ippatsu(), "later draw never reactivates ippatsu");
            check(state.afterOwnDiscard().establishedRiichi() == expected && state.afterCallEstablished().waitTypes().equals(state.waitTypes()), "expiry preserves riichi and original waits");
            check(state.ippatsu(), "event transitions do not mutate original state");
            check(DiscardActions.afterDrawOptions(f.hand(), f.drawn(), state.establishedRiichi()).equals(Set.of(f.drawn())), "established state drives exact tsumogiri lock");
            var missed = FuritenState.initial().afterPassedRon(state.establishedRiichi());
            check(missed.riichiPass() && !missed.temporary(), "established state drives permanent missed-ron furiten");
        }
        check(RiichiState.initial().afterOwnDraw().phase() == RiichiState.Phase.UNDECLARED
                && !RiichiState.initial().afterCallEstablished().ippatsu(), "ordinary events do not create a declaration");
    }

    private static void validation() {
        expectInvalid(() -> context(-1,25_000,4,0,false));
        expectInvalid(() -> context(4,25_000,4,0,false));
        expectInvalid(() -> context(0,25_000,-1,0,false));
        expectInvalid(() -> context(0,25_000,71,0,false));
        expectInvalid(() -> context(0,25_000,4,-1,false));
        Fixture f = fixture(0, new Id(ROUND, 200), false, 1, false);
        var initial = RiichiState.initial();
        expectState(() -> initial.resolve(f.window()));
        expectInvalid(() -> initial.declare(f.hand(), f.drawn(), context(0,999,40,1,false), FuritenState.initial(), f.drawn(), new Id(ROUND,201)));
        expectInvalid(() -> initial.declare(f.hand(), f.drawn(), context(0,25_000,3,1,false), FuritenState.initial(), f.drawn(), new Id(ROUND,201)));
        expectInvalid(() -> initial.declare(f.hand(), f.drawn(), context(0,25_000,40,1,false), FuritenState.initial(), 0, new Id(ROUND,201)));
        expectInvalid(() -> f.declaration().state().declare(f.hand(), f.drawn(), context(0,25_000,40,1,false), FuritenState.initial(), f.drawn(), new Id(ROUND,201)));
        var options = initial.declarationOptions(f.hand(), f.drawn(), context(0,25_000,40,1,false));
        expectUnsupported(options::clear); expectUnsupported(() -> options.get(f.drawn()).clear());
        expectUnsupported(() -> f.declaration().state().waitTypes().clear());
        expectUnsupported(() -> f.declaration().state().pending().orElseThrow().waitTypes().clear());
        Pool p=new Pool(); var held=p.tiles("123m456p789sE");
        var quad=new Meld(Meld.Kind.CLOSED_KAN,List.of(p.take(31),p.take(31),p.take(31),p.take(31)));
        int drawn=p.take(30); held.add(drawn);
        HandInput withKan=new HandInput(held,List.of(quad));
        expectInvalid(() -> initial.declarationOptions(withKan,drawn,context(0,25_000,40,0,false)));
    }

    record Fixture(HandInput hand, int drawn, RiichiState.Declaration declaration,
                           DiscardResponseWindow window, List<Participant> windowPlayers) { }

    static Fixture fixture(int discarder, Id id, boolean pon, int discardedBefore, boolean calledBefore) {
        Pool p = new Pool(); HandInput waiting = p.hand("123m456p789s22m34p"); int drawn = p.take(27);
        HandInput hand = plus(waiting, drawn);
        var declaration = RiichiState.initial().declare(hand, drawn, context(discarder,25_000,40,discardedBefore,calledBefore),
                FuritenState.initial(), drawn, id);
        List<Participant> others = new ArrayList<>();
        for (int relative = 1; relative < 4; relative++) {
            int seat = (discarder + relative) % 4;
            List<Integer> held = new ArrayList<>();
            if (pon) {
                if (relative == 1) { held.add(p.take(27)); held.add(p.take(27)); }
                for (int tile = 135; held.size() < 13; tile--) if (p.reserve(tile)) held.add(tile);
            } else for (int type : ORPHANS) held.add(p.take(type));
            HandInput other = new HandInput(held, List.of());
            var calls = CallActions.options(other, drawn, new CallActions.Context(discarder,seat,40,0,Riichi.NONE));
            others.add(new Participant(seat,other,ronContext(drawn,27 + relative),FuritenState.initial(),calls));
        }
        var window = new DiscardResponseWindow(id, discarder, drawn, others);
        return new Fixture(hand, drawn, declaration, window, List.copyOf(others));
    }
    private static WinContext ronContext(int tile, int wind) {
        return new WinContext(WinMethod.RON,tile,wind,27,Riichi.NONE,false,WinEvent.NORMAL,FirstTurn.NONE,List.of(),List.of());
    }
    private static void finishPass(DiscardResponseWindow window) {
        for (int seat : window.pendingSeats()) check(window.submit(window.id(),seat,Simple.PASS) == Submission.ACCEPTED,"pass accepted");
    }
    private static RiichiState.Context context(int seat,int points,int live,int discards,boolean calls) { return new RiichiState.Context(seat,points,live,discards,calls); }
    private static HandInput plus(HandInput hand, int tile) { var held=new ArrayList<>(hand.concealedTiles()); held.add(tile); return new HandInput(held,hand.melds()); }
    private static final class Pool {
        private final Set<Integer> used = new HashSet<>();
        int take(int type) { for(int tile=type*4;tile<type*4+4;tile++) if(used.add(tile)) return tile; throw new AssertionError("Fixture exhausted type "+type); }
        boolean reserve(int tile) { return used.add(tile); }
        HandInput hand(String notation) { return new HandInput(tiles(notation),List.of()); }
        List<Integer> tiles(String notation) {
            List<Integer> held=new ArrayList<>(); String digits="";
            for(char ch:notation.toCharArray()) {
                if(Character.isDigit(ch)) { digits+=ch; continue; }
                int base=switch(ch) { case 'm'->0;case 'p'->9;case 's'->18;default->-1; };
                if(base>=0) { for(char digit:digits.toCharArray()) held.add(take(base+digit-'1')); digits=""; }
                else held.add(take(switch(ch) { case 'E'->27;case 'S'->28;case 'W'->29;case 'N'->30;case 'P'->31;case 'F'->32;case 'C'->33;default->throw new AssertionError("notation"); }));
            }
            return held;
        }
    }
    private static void check(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
    private static void expectInvalid(Runnable action) { assertions++; try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("Expected invalid declaration/window"); }
    private static void expectState(Runnable action) { assertions++; try { action.run(); } catch(IllegalStateException expected) { return; } throw new AssertionError("Expected wrong declaration phase"); }
    private static void expectUnsupported(Runnable action) { assertions++; try { action.run(); } catch(UnsupportedOperationException expected) { return; } throw new AssertionError("Expected immutable state"); }
    private static void run(String label,Runnable test) { try { test.run(); } catch(Throwable failure) { throw new AssertionError(label,failure); } }
}
