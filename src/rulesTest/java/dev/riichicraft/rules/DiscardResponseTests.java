package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.WinContext.*;

/** Response order, multi-ron, physical choices and transactional furiten examples. */
public final class DiscardResponseTests {
    private static int assertions;
    private static final int[][] ORDERS = {{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
    private static final UUID ROUND = UUID.fromString("8f1fd29d-3d20-485c-948e-ec2542d76d10");

    private DiscardResponseTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        run("all ron subsets, seat rotations and response permutations", DiscardResponseTests::ronOrders);
        run("call priorities and exact physical choices", DiscardResponseTests::callPriorities);
        run("automatic passes and no-yaku furiten", DiscardResponseTests::automaticPasses);
        run("window identity, replay and immutable views", DiscardResponseTests::requests);
        run("snapshot, context and call validation", DiscardResponseTests::invalidSnapshots);
        System.out.printf("PASS discard responses: %d assertions; %.3f s%n", assertions,
                (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static void ronOrders() {
        // Every subset (including no winners), all four discarders, all six arrival orders.
        for (int discarder = 0; discarder < 4; discarder++) {
            for (int subset = 0; subset < 8; subset++) {
                for (int[] order : ORDERS) {
                    Fixture f = ronFixture(discarder);
                    DiscardResponseWindow w = f.window();
                    check(w.pendingSeats().size() == 3 && w.resolution().isEmpty(), "all three ron-capable players must respond");
                    for (int step = 0; step < 3; step++) {
                        int relative = order[step];
                        int seat = f.players().get(relative).seat();
                        check(w.offers(seat).equals(Set.of(Simple.PASS, Simple.RON)), "ron is derived from score and furiten");
                        Choice choice = (subset & (1 << relative)) != 0 ? Simple.RON : Simple.PASS;
                        check(w.submit(w.id(), seat, choice) == Submission.ACCEPTED, "a pending choice is accepted");
                        check(w.resolution().isPresent() == (step == 2), "no partial response can settle the discard");
                    }
                    Resolution result = w.resolution().orElseThrow();
                    List<Integer> expected = new ArrayList<>();
                    for (int relative = 0; relative < 3; relative++) {
                        int seat = f.players().get(relative).seat();
                        if ((subset & (1 << relative)) != 0) expected.add(seat);
                        FuritenState history = result.historiesAfter().get(seat);
                        check(history.riichiPass() == ((subset & (1 << relative)) == 0),
                                "every non-ron completion records established-riichi furiten");
                        check(!history.temporary(), "new riichi passes need no temporary flag");
                        check(f.players().get(relative).history().equals(FuritenState.initial()), "input histories remain untouched");
                    }
                    check(result.ronSeats().equals(expected), "all winners remain in draw order after the discarder");
                    check(result.ronValues().keySet().equals(Set.copyOf(expected)), "exactly the actual winners have ron values");
                    check(result.outcome() == (subset == 0 ? Outcome.NO_CLAIM : Outcome.RON), "no head bump or triple-ron abort");
                    check(result.call().isEmpty() && result.historiesAfter().size() == 3, "ron never leaves a selected call");
                    result.ronValues().values().forEach(value -> check(value.winningTileId() == f.tile(),
                            "multiple ron explanations refer to the same real discard"));
                    check(w.submit(w.id(), f.players().getFirst().seat(), Simple.PASS) == Submission.CLOSED,
                            "a settled window rejects a replay");
                    check(w.resolution().orElseThrow() == result, "a replay cannot recompute or replace resolution");
                }
            }
        }
    }

    private static void callPriorities() {
        for (boolean kan : List.of(false, true)) {
            for (boolean reverse : List.of(false, true)) {
                Fixture f = callFixture(kan, false);
                DiscardResponseWindow w = f.window();
                Call chi = f.players().getFirst().calls().iterator().next();
                Call stronger = f.players().get(1).calls().iterator().next();
                int first = reverse ? 2 : 1;
                int second = reverse ? 1 : 2;
                check(w.responses().get(3) == Simple.PASS, "a player without actions is automatically passed");
                check(w.submit(w.id(), first, first == 1 ? chi : stronger) == Submission.ACCEPTED, "first call is collected");
                check(w.resolution().isEmpty(), "an early chi/pon/kan cannot preempt another response");
                check(w.submit(w.id(), second, second == 1 ? chi : stronger) == Submission.ACCEPTED, "second call is collected");
                Resolution result = w.resolution().orElseThrow();
                check(result.outcome() == Outcome.CALL && result.call().orElseThrow().seat() == 2, "pon/open-kan outranks chi in either arrival order");
                check(result.call().orElseThrow().choice().equals(stronger), "the exact offered call wins");
                check(result.ronSeats().isEmpty(), "call outcome has no invented ron winner");
                if (!kan) check(result.historiesAfter().get(2).temporary(), "choosing pon on a no-yaku completion still records furiten");
            }
        }
        for (int[] order : ORDERS) {
            Fixture f = callFixture(false, true);
            DiscardResponseWindow w = f.window();
            for (int index : order) {
                int seat = index + 1;
                Choice choice = seat == 3 ? Simple.RON : f.players().get(index).calls().iterator().next();
                check(w.submit(w.id(), seat, choice) == Submission.ACCEPTED, "competing calls and ron are collected");
            }
            Resolution result = w.resolution().orElseThrow();
            check(result.outcome() == Outcome.RON && result.ronSeats().equals(List.of(3)) && result.call().isEmpty(),
                    "ron outranks both pon and chi for all six response orders");
            check(result.historiesAfter().get(2).temporary(), "an overridden pon choice does not erase that player's missed completion");
        }
        for (boolean chooseChi : List.of(false, true)) {
            Fixture f = callFixture(false, false);
            DiscardResponseWindow w = f.window();
            w.submit(w.id(), 2, Simple.PASS);
            w.submit(w.id(), 1, chooseChi ? f.players().getFirst().calls().iterator().next() : Simple.PASS);
            Resolution result = w.resolution().orElseThrow();
            check(result.outcome() == (chooseChi ? Outcome.CALL : Outcome.NO_CLAIM),
                    "a declined higher-priority offer cannot reserve its priority");
            check(result.call().map(ChosenCall::seat).equals(chooseChi ? java.util.Optional.of(1) : java.util.Optional.empty()),
                    "chi wins only when selected and no higher-priority response is selected");
        }

        // Three held fives allow choosing a red or non-red pair; preserve actual IDs.
        for (boolean useRed : List.of(false, true)) {
            Pool pool = new Pool();
            HandInput caller = pool.hand("555m123p456s1122z");
            int tile = pool.tiles("5m").getFirst();
            List<Integer> fives = caller.concealedTiles().stream().filter(id -> id / 4 == 4).toList();
            Call red = new Call(Meld.Kind.PON, List.of(fives.get(0), fives.get(1)));
            Call plain = new Call(Meld.Kind.PON, List.of(fives.get(1), fives.get(2)));
            List<Participant> ps = List.of(
                    participant(1, pool.fill(), tile, Riichi.NONE, Set.of()),
                    participant(2, caller, tile, Riichi.NONE, Set.of(red, plain)),
                    participant(3, pool.fill(), tile, Riichi.NONE, Set.of()));
            DiscardResponseWindow w = new DiscardResponseWindow(id(9), 0, tile, ps);
            Call selected = useRed ? red : plain;
            for (int seat : List.copyOf(w.pendingSeats())) {
                check(w.submit(w.id(), seat, seat == 2 ? selected : Simple.PASS) == Submission.ACCEPTED, "complete exact-copy choice");
            }
            Call result = w.resolution().orElseThrow().call().orElseThrow().choice();
            check(result.equals(selected) && result.handTiles().contains(TileCatalog.RED_MAN_FIVE) == useRed,
                    "red/ordinary selection is never silently replaced");
        }
    }

    private static void automaticPasses() {
        Fixture f = noYakuFixture();
        DiscardResponseWindow w = f.window();
        check(w.ronDecision(1).rejections().equals(Set.of(WinEvaluator.Rejection.NO_YAKU)), "west completes the open hand without yaku");
        check(w.offers(1).equals(Set.of(Simple.PASS)), "no ron button is offered for no-yaku completion");
        check(w.pendingSeats().isEmpty() && w.resolution().isPresent(), "no-action participants do not need artificial pass requests");
        Resolution result = w.resolution().orElseThrow();
        check(result.outcome() == Outcome.NO_CLAIM && result.historiesAfter().get(1).temporary(),
                "automatic pass of no-yaku west records temporary furiten");
        for (int seat : List.of(2,3)) check(result.historiesAfter().get(seat).equals(FuritenState.initial()),
                "non-completions do not invent furiten");

        Fixture ron = ronFixture(0);
        List<Participant> changed = new ArrayList<>(ron.players());
        Participant first = changed.getFirst();
        changed.set(0, new Participant(first.seat(), first.hand(), first.context(),
                FuritenState.initial().afterPassedRon(Riichi.NONE), Set.of()));
        DiscardResponseWindow blocked = new DiscardResponseWindow(id(3), 0, ron.tile(), changed);
        check(blocked.responses().get(1) == Simple.PASS && !blocked.offers(1).contains(Simple.RON), "already furiten cannot claim ron");
        check(blocked.resolution().isEmpty(), "no histories are published before all pending responses");
        check(first.history().equals(FuritenState.initial()), "building the window does not mutate its input history");
        for (int seat : List.copyOf(blocked.pendingSeats())) blocked.submit(blocked.id(), seat, Simple.PASS);
        FuritenState after = blocked.resolution().orElseThrow().historiesAfter().get(1);
        check(after.temporary() && after.riichiPass(), "already blocked completion still records a new riichi pass");

        // A supplied common last-discard event gives the formerly no-yaku west its own yaku.
        List<Participant> lastDiscard = f.players().stream().map(p -> withContext(p,
                new WinContext(WinMethod.RON, f.tile(), p.context().seatWind(), 27, Riichi.NONE, false,
                        WinEvent.HOUTEI, FirstTurn.NONE, List.of(), List.of()))).toList();
        DiscardResponseWindow last = new DiscardResponseWindow(id(4), 0, f.tile(), lastDiscard);
        check(last.offers(1).contains(Simple.RON) && last.pendingSeats().equals(Set.of(1)),
                "a real houtei context is used to derive the ron mask instead of a stale no-yaku mask");
        check(last.submit(last.id(), 1, Simple.RON) == Submission.ACCEPTED
                        && last.resolution().orElseThrow().ronSeats().equals(List.of(1)), "last-discard ron resolves normally");
        check(!last.resolution().orElseThrow().historiesAfter().get(1).temporary(), "a real winner is not marked as having missed its discard");
    }

    private static void requests() {
        Fixture f = ronFixture(0);
        DiscardResponseWindow w = f.window();
        Map<Integer, Choice> before = w.responses();
        check(w.submit(new Id(UUID.randomUUID(), w.id().serial()), 1, Simple.RON) == Submission.WRONG_WINDOW, "another round cannot respond");
        check(w.submit(id(w.id().serial() + 1), 1, Simple.RON) == Submission.WRONG_WINDOW, "another discard window cannot respond");
        check(w.submit(null, 1, Simple.RON) == Submission.WRONG_WINDOW, "missing window identity rejected");
        for (int seat : List.of(-1,0,4)) check(w.submit(w.id(), seat, Simple.PASS) == Submission.NOT_A_RESPONDER, "discarder and invalid seats cannot respond");
        check(w.submit(w.id(), 1, null) == Submission.NOT_OFFERED, "null choice rejected without mutation");
        Call forged = new Call(Meld.Kind.PON, List.of(0,1));
        check(w.submit(w.id(), 1, forged) == Submission.NOT_OFFERED, "forged call cannot bypass the offer mask");
        check(w.responses().equals(before) && w.resolution().isEmpty(), "all rejected requests preserve state");
        check(w.submit(w.id(), 2, Simple.RON) == Submission.ACCEPTED, "first seat reply accepted");
        check(before.isEmpty(), "a previously returned response snapshot does not change after later replies");
        Map<Integer, Choice> once = w.responses();
        check(w.submit(w.id(), 2, Simple.PASS) == Submission.ALREADY_RESPONDED, "changing an already accepted reply rejected");
        check(w.responses().equals(once), "a duplicate leaves the original ron choice intact");
        check(w.submit(w.id(), 3, Simple.PASS) == Submission.ACCEPTED && w.submit(w.id(), 1, Simple.RON) == Submission.ACCEPTED,
                "other players' replies do not invalidate the issued window id");
        Resolution r = w.resolution().orElseThrow();
        check(r.ronSeats().equals(List.of(1,2)), "a later second ron is retained in draw order");
        immutable(() -> w.responses().clear(), "responses view immutable");
        immutable(() -> w.pendingSeats().add(0), "pending view immutable");
        immutable(() -> w.offers(1).clear(), "offer mask immutable");
        immutable(() -> r.ronSeats().clear(), "winner list immutable");
        immutable(() -> r.ronValues().clear(), "hand values immutable");
        immutable(() -> r.historiesAfter().clear(), "histories immutable");
        illegal(() -> w.offers(0), "discarder has no responder view");
        illegal(() -> w.ronDecision(4), "unknown seat has no score view");
    }

    private static void invalidSnapshots() {
        Fixture f = ronFixture(0);
        illegal(() -> new Id(ROUND, -1), "negative serial");
        illegal(() -> new DiscardResponseWindow(id(0), 4, f.tile(), f.players()), "invalid discarder");
        illegal(() -> new DiscardResponseWindow(id(0), 0, 136, f.players()), "invalid physical discard");
        illegal(() -> new DiscardResponseWindow(id(0), 0, f.tile(), f.players().subList(0,2)), "missing responder");
        List<Participant> duplicateSeat = List.of(f.players().get(0), f.players().get(0), f.players().get(2));
        illegal(() -> new DiscardResponseWindow(id(0), 0, f.tile(), duplicateSeat), "duplicate seat");
        Participant p = f.players().getFirst();
        List<Participant> wrongSeat = replace(f.players(), 0, participant(0, p.hand(), f.tile(), Riichi.RIICHI, Set.of()));
        illegal(() -> new DiscardResponseWindow(id(0), 0, f.tile(), wrongSeat), "discarder included as responder");
        List<Participant> sameTiles = replace(f.players(), 1, participant(2, p.hand(), f.tile(), Riichi.RIICHI, Set.of()));
        illegal(() -> new DiscardResponseWindow(id(0), 0, f.tile(), sameTiles), "physical tiles shared across private hands");
        int held = p.hand().concealedTiles().getFirst();
        List<Participant> heldDiscard = f.players().stream().map(x -> participant(x.seat(), x.hand(), held, Riichi.RIICHI, Set.of())).toList();
        illegal(() -> new DiscardResponseWindow(id(0), 0, held, heldDiscard), "discard still owned in a hand");
        HandInput completed = append(p.hand(), f.tile());
        illegal(() -> participant(1, completed, f.tile(), Riichi.NONE, Set.of()), "completed hand used as pre-response snapshot");

        WinContext c = p.context();
        WinContext tsumo = new WinContext(WinMethod.TSUMO, c.winningTileId(), c.seatWind(), c.roundWind(), c.riichi(),
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
        illegalWindow(f, replace(f.players(), 0, withContext(p, tsumo)), "self-draw context cannot describe a discard");
        WinContext robbery = new WinContext(WinMethod.RON, c.winningTileId(), c.seatWind(), c.roundWind(), c.riichi(),
                false, WinEvent.CHANKAN, FirstTurn.NONE, List.of(), List.of());
        illegalWindow(f, replace(f.players(), 0, withContext(p, robbery)), "kan robbery is not an ordinary-discard window");
        WinContext wrongTile = new WinContext(WinMethod.RON, f.tile() + 1, c.seatWind(), c.roundWind(), c.riichi(),
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
        illegalWindow(f, replace(f.players(), 0, withContext(p, wrongTile)), "same tile type with a different physical discard id");
        WinContext wrongWind = new WinContext(WinMethod.RON, f.tile(), f.players().get(1).context().seatWind(), c.roundWind(), c.riichi(),
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
        illegalWindow(f, replace(f.players(), 0, withContext(p, wrongWind)), "two players cannot have the same seat wind");
        WinContext wrongRound = new WinContext(WinMethod.RON, f.tile(), c.seatWind(), 28, c.riichi(),
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(), List.of());
        illegalWindow(f, replace(f.players(), 0, withContext(p, wrongRound)), "round winds disagree");
        WinContext wrongEvent = new WinContext(WinMethod.RON, f.tile(), c.seatWind(), 27, c.riichi(),
                false, WinEvent.HOUTEI, FirstTurn.NONE, List.of(), List.of());
        illegalWindow(f, replace(f.players(), 0, withContext(p, wrongEvent)), "last-discard event disagrees");
        int freeIndicator = 31 * 4;
        WinContext indicatorOnlyOne = new WinContext(WinMethod.RON, f.tile(), c.seatWind(), 27, c.riichi(),
                false, WinEvent.NORMAL, FirstTurn.NONE, List.of(freeIndicator), List.of());
        illegalWindow(f, replace(f.players(), 0, withContext(p, indicatorOnlyOne)), "global indicator lists disagree");
        List<Participant> indicatorInOtherHand = f.players().stream().map(x -> withContext(x,
                new WinContext(WinMethod.RON, f.tile(), x.context().seatWind(), 27, x.context().riichi(),
                        false, WinEvent.NORMAL, FirstTurn.NONE, List.of(held), List.of()))).toList();
        illegalWindow(f, indicatorInOtherHand, "one player's hand cannot hold a common indicator");

        var supplied = new ArrayList<>(f.players());
        DiscardResponseWindow copied = new DiscardResponseWindow(id(8), 0, f.tile(), supplied);
        supplied.clear();
        check(copied.pendingSeats().equals(Set.of(1,2,3)), "clearing the input participant list cannot alter an open window");
        var ownTiles = new ArrayList<>(List.of(1,0));
        Call copiedCall = new Call(Meld.Kind.PON, ownTiles);
        ownTiles.clear();
        check(copiedCall.handTiles().equals(List.of(0,1)), "call snapshots copy and canonicalise the exact physical selection");
        immutable(() -> copiedCall.handTiles().clear(), "call tiles immutable");

        illegal(() -> new Call(Meld.Kind.CLOSED_KAN, List.of(0,1,2)), "ankan not a discard response");
        illegal(() -> new Call(Meld.Kind.PON, List.of(0,0)), "call repeats a physical id");
        illegal(() -> new Call(Meld.Kind.OPEN_KAN, List.of(0,1)), "open kan needs three held copies");
        illegal(() -> new Call(Meld.Kind.CHI, List.of(-1,2)), "call physical id range");
        Fixture calls = callFixture(false, false);
        Participant chi = calls.players().getFirst();
        Participant pon = calls.players().get(1);
        Call offeredChi = chi.calls().iterator().next();
        Participant riichiCall = new Participant(1, chi.hand(),
                context(1, calls.tile(), Riichi.RIICHI), chi.history(), chi.calls());
        illegalWindow(calls, replace(calls.players(), 0, riichiCall), "established riichi cannot offer calls");
        Participant wrongOwner = new Participant(2, pon.hand(), pon.context(), pon.history(), Set.of(offeredChi));
        illegalWindow(calls, replace(calls.players(), 1, wrongOwner), "a player's mask cannot use another hand's tiles");
        Call badShape = new Call(Meld.Kind.CHI, List.of(chi.hand().concealedTiles().getFirst(), chi.hand().concealedTiles().getLast()));
        illegalWindow(calls, replace(calls.players(), 0, new Participant(1, chi.hand(), chi.context(), chi.history(), Set.of(badShape))), "malformed chi structure rejected");

        Pool pool = new Pool();
        HandInput east = pool.hand("456p789s1122334z");
        HandInput nonNextChi = pool.hand("12m123p456s11223z");
        int three = pool.tiles("3m").getFirst();
        HandInput other = pool.fill();
        Call farChi = new Call(Meld.Kind.CHI, nonNextChi.concealedTiles().subList(0,2));
        illegal(() -> new DiscardResponseWindow(id(1), 0, three, List.of(participant(1,east,three,Riichi.NONE,Set.of()),
                participant(2,nonNextChi,three,Riichi.NONE,Set.of(farChi)),participant(3,other,three,Riichi.NONE,Set.of()))), "chi only from the preceding player's discard");
    }

    private record Fixture(int discarder, int tile, List<Participant> players) {
        DiscardResponseWindow window() { return new DiscardResponseWindow(id(1), discarder, tile, players); }
    }
    private static Fixture ronFixture(int discarder) {
        Pool pool = new Pool();
        HandInput[] hands = {pool.hand("34m123456p789s11z"), pool.hand("46m123456s789p22z"), pool.hand("67m123789m456p33z")};
        int tile = pool.tiles("5m").getFirst();
        List<Participant> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) players.add(participant((discarder + i + 1) % 4, hands[i], tile, Riichi.RIICHI, Set.of()));
        return new Fixture(discarder, tile, players);
    }
    private static Fixture callFixture(boolean kan, boolean ron) {
        Pool pool = new Pool();
        HandInput chi = pool.hand("12m456p789s11223z");
        HandInput pon = pool.hand(kan ? "333m123p456s1223z" : "33m123678p456s11z");
        HandInput other = pool.hand(ron ? "45m123456s789p33z" : "456789m234p123s4z");
        int tile = pool.tiles("3m").getFirst();
        Call chiCall = new Call(Meld.Kind.CHI, chi.concealedTiles().subList(0,2));
        Call ponCall = new Call(kan ? Meld.Kind.OPEN_KAN : Meld.Kind.PON,
                pon.concealedTiles().stream().filter(id -> id / 4 == tile / 4).toList());
        return new Fixture(0, tile, List.of(participant(1,chi,tile,Riichi.NONE,Set.of(chiCall)),
                participant(2,pon,tile,Riichi.NONE,Set.of(ponCall)), participant(3,other,tile,ron ? Riichi.RIICHI : Riichi.NONE,Set.of())));
    }
    private static Fixture noYakuFixture() {
        Pool pool = new Pool();
        Meld chi = new Meld(Meld.Kind.CHI, pool.tiles("123m"));
        HandInput first = new HandInput(pool.tiles("456p789s1133z"), List.of(chi));
        HandInput second = pool.hand("234m123789p456s1z");
        HandInput third = pool.hand("567m234678s999p2z");
        int tile = pool.tiles("3z").getFirst();
        return new Fixture(0, tile, List.of(participant(1,first,tile,Riichi.NONE,Set.of()),
                participant(2,second,tile,Riichi.NONE,Set.of()),participant(3,third,tile,Riichi.NONE,Set.of())));
    }
    private static Participant participant(int seat, HandInput hand, int tile, Riichi riichi, Set<Call> calls) {
        return new Participant(seat, hand, context(seat,tile,riichi), FuritenState.initial(), calls);
    }
    private static WinContext context(int seat, int tile, Riichi riichi) {
        return new WinContext(WinMethod.RON, tile, 27+seat, 27, riichi, false, WinEvent.NORMAL, FirstTurn.NONE,List.of(),List.of());
    }
    private static Participant withContext(Participant p, WinContext c) { return new Participant(p.seat(),p.hand(),c,p.history(),p.calls()); }
    private static List<Participant> replace(List<Participant> list, int index, Participant p) {
        var copy = new ArrayList<>(list); copy.set(index,p); return copy;
    }
    private static HandInput append(HandInput hand, int tile) {
        var copy = new ArrayList<>(hand.concealedTiles()); copy.add(tile); return new HandInput(copy,hand.melds());
    }
    private static Id id(long serial) { return new Id(ROUND,serial); }
    private static void illegalWindow(Fixture f, List<Participant> players, String message) {
        illegal(() -> new DiscardResponseWindow(id(1), f.discarder(), f.tile(), players),message);
    }
    private static final class Pool {
        private final boolean[] used = new boolean[136];
        HandInput hand(String text) { return new HandInput(tiles(text), List.of()); }
        List<Integer> tiles(String text) {
            var out = new ArrayList<Integer>(); var digits = new StringBuilder();
            for (char ch : text.toCharArray()) {
                if (Character.isDigit(ch)) { digits.append(ch); continue; }
                int base = switch(ch) {case 'm' -> 0; case 'p' -> 9; case 's' -> 18; case 'z' -> 27; default -> throw new AssertionError("fixture suit");};
                for (char digit : digits.toString().toCharArray()) {
                    int type = base+digit-'1'; int tile = type*4;
                    while (tile < type*4+4 && used[tile]) tile++;
                    if (tile == type*4+4) throw new AssertionError("fixture fifth tile: " + text);
                    used[tile]=true; out.add(tile);
                }
                digits.setLength(0);
            }
            if (!digits.isEmpty()) throw new AssertionError("fixture missing suit");
            return out;
        }
        HandInput fill() {
            List<Integer> ids = new ArrayList<>();
            for (int tile=0; tile<136 && ids.size()<13; tile++) if (!used[tile]) { used[tile]=true; ids.add(tile); }
            return new HandInput(ids,List.of());
        }
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void illegal(Runnable action, String message) {
        assertions++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid input: " + message);
    }
    private static void immutable(Runnable action, String message) {
        assertions++; try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected immutable result: " + message);
    }
    private static void run(String name, Runnable test) {
        try { test.run(); } catch (Throwable failure) { throw new AssertionError("Response group failed: " + name, failure); }
    }
}
