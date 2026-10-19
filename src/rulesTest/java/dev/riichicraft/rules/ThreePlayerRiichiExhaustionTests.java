package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static dev.riichicraft.rules.DiscardResponseWindow.Simple;
import static dev.riichicraft.rules.ThreePlayerRound.Status;

/** Real accepted-action trajectories for riichi whose north wait becomes self-exhausted. */
public final class ThreePlayerRiichiExhaustionTests {
    private static final List<UUID> PLAYERS = List.of(new UUID(801, 0), new UUID(801, 1), new UUID(801, 2));
    private static final UUID EAST = PLAYERS.getFirst();
    private static int assertions;
    private enum Fixture { SEQUENCES, PRESERVING_KAN, CHANGING_KAN }

    private ThreePlayerRiichiExhaustionTests() { }

    public static void main(String[] args) {
        for (Fixture fixture : Fixture.values()) {
            play(new Session(null, fixture), fixture);
            for (ThreePlayerMatchRules.Format format : ThreePlayerMatchRules.Format.values()) {
                play(new Session(format, fixture), fixture);
            }
        }
        System.out.printf("PASS three-player exhausted riichi: %d assertions; nine real-wall trajectories%n", assertions);
    }

    private static void play(Session session, Fixture fixture) {
        var east = session.view(EAST);
        int declarationTile = east.ownDraw().orElseThrow();
        check(east.turnOptions().riichiDiscards().contains(declarationTile), "north tanki genuinely permits riichi");
        session.discard(EAST, declarationTile, true);
        session.passAll();
        for (int turn = 0; turn < 2; turn++) {
            var publicView = session.view(null);
            UUID player = PLAYERS.get(publicView.actorSeat());
            session.discard(player, session.view(player).ownDraw().orElseThrow(), false);
            session.passAll();
        }
        for (int extraction = 0; extraction < 3; extraction++) {
            east = session.view(EAST);
            int north = east.ownDraw().orElseThrow();
            check(north / 4 == 30 && east.turnOptions().kitas().equals(Set.of(north)),
                    "established riichi offers only the exact newly drawn north");
            session.kita(EAST, north);
            session.passAll();
            session.state().wall().deadTiles().forEach(ThreePlayerTiles::typeOf);
            check(session.state().norths().getFirst().size() == extraction + 1, "each accepted kita owns one new north");
            session.roundTrip();
        }

        east = session.view(EAST);
        var state = session.state();
        check(state.riichi().getFirst().establishedRiichi() == WinContext.Riichi.DOUBLE_RIICHI,
                "self-exhaustion does not revoke established riichi");
        check(!state.riichi().getFirst().ippatsu(), "established kita has interrupted ippatsu");
        check(state.ledger().points().equals(List.of(34_000, 35_000, 35_000))
                && state.ledger().depositSticks() == 1, "one funded riichi stick after the entire north chain");
        check(HandAnalyzer.waits(waitingBeforeDraw(east)).equals(Set.of(30)), "original structural north tanki remains");
        check(ThreePlayerHands.waits(waitingBeforeDraw(east), state.norths().getFirst()).isEmpty(),
                "three extracted norths plus the held north cannot seek a fifth copy");
        check(east.turnOptions().discards().equals(Set.of(east.ownDraw().orElseThrow()))
                && east.turnOptions().riichiDiscards().isEmpty() && !east.turnOptions().tsumo(),
                "the already accepted state has normal riichi-locked discard options");

        if (fixture == Fixture.PRESERVING_KAN) {
            var kans = east.turnOptions().kans();
            check(kans.size() == 1 && kans.iterator().next().kind() == KanActions.Kind.CLOSED,
                    "empty effective waits do not impose a new blanket ban on an otherwise preserving ankan");
            var kan = kans.iterator().next();
            check(kan.handTiles().contains(east.ownDraw().orElseThrow()) && kan.handTiles().getFirst() / 4 == 25,
                    "the new fourth eight-sou is the exact eligible closed-kan draw");
            session.kan(EAST, kan);
            session.passAll();
            session.roundTrip();
            check(session.state().kans().establishedKans() == 1 && session.state().norths().getFirst().size() == 3,
                    "preserving closed kan establishes independently from the three kita");
            HandInput afterKan = waitingBeforeDraw(session.view(EAST));
            check(HandAnalyzer.waits(afterKan).equals(Set.of(30))
                    && ThreePlayerHands.waits(afterKan, session.state().norths().getFirst()).isEmpty(),
                    "the closed kan preserves the north shape and the same exhausted physical wait");
        } else if (fixture == Fixture.CHANGING_KAN) {
            int drawnTile = east.ownDraw().orElseThrow();
            List<Integer> triple = east.ownHand().orElseThrow().concealedTiles().stream().filter(tile -> tile / 4 == 11).toList();
            check(drawnTile / 4 == 11 && triple.size() == 4, "the new third-pin completes four exact copies");
            var concealed = new ArrayList<>(east.ownHand().orElseThrow().concealedTiles());
            concealed.removeAll(triple);
            var after = new HandInput(concealed, List.of(new Meld(Meld.Kind.CLOSED_KAN, triple)));
            check(HandAnalyzer.waits(after).isEmpty(), "removing the sequence-only triplet would destroy the north wait shape");
            check(east.turnOptions().kans().isEmpty(), "two empty inventory-filtered waits must not legalize a destructive ankan");
            byte[] before = session.archive();
            check(session.tryKan(EAST, new KanActions.Choice(KanActions.Kind.CLOSED, -1, triple)) == Status.NOT_OFFERED,
                    "the core rejects an unsolicited destructive kan, not only its UI option");
            check(Arrays.equals(before, session.archive()), "a rejected destructive kan changes neither accepted history nor archive");
        } else {
            check(east.turnOptions().kans().isEmpty(), "the sequence-only fixture has no closed-kan option");
        }

        int laterDiscards = 0;
        while (session.view(null).phase() != ThreePlayerRound.Phase.FINISHED) {
            check(laterDiscards < 60, "the real remaining wall reaches a finite exhaustive end");
            var publicView = session.view(null);
            UUID actor = PLAYERS.get(publicView.actorSeat());
            var own = session.view(actor);
            check(own.phase() == ThreePlayerRound.Phase.DRAWN_TURN, "passes resume ordinary drawn turns");
            int tile = own.ownDraw().orElseThrow();
            check(own.turnOptions().discards().contains(tile), "every remaining actor can legally tsumogiri");
            session.discard(actor, tile, false);
            session.passAll();
            if (++laterDiscards % 11 == 0) session.roundTrip();
        }
        check(laterDiscards > 3, "the exhausted north state survives further turns by all three players");
        var end = session.view(null).end().orElseThrow();
        check(end.kind() == ThreePlayerRound.EndKind.EXHAUSTIVE && !end.tenpai().contains(0),
                "self-exhausted north tanki is not formally tenpai at the actual exhaustive end");
        check(end.tenpai().equals(Set.of(1, 2)), "the two unchanged other hands remain formally tenpai");
        check(session.state().ledger().points().equals(List.of(32_000, 36_000, 36_000))
                && session.state().ledger().depositSticks() == 1, "two tenpai receive 1000 each; the old stick carries");
        check(end.transfers().stream().allMatch(t -> t.kind() == ThreePlayerLedger.Kind.NOTEN)
                && end.transfers().stream().mapToInt(ThreePlayerLedger.Transfer::points).sum() == 2_000,
                "the real draw settles only the independently expected noten bill");
        session.roundTrip();
        session.advanceMatchIfPresent();
    }

    private static HandInput waitingBeforeDraw(ThreePlayerRound.View view) {
        var concealed = new ArrayList<>(view.ownHand().orElseThrow().concealedTiles());
        concealed.remove(view.ownDraw().orElseThrow());
        return new HandInput(concealed, view.ownHand().orElseThrow().melds());
    }

    /** Both owners use the actual core; restoration never creates a replacement current round. */
    private static final class Session {
        private final ThreePlayerMatch.RoundFactory factory;
        private ThreePlayerRound round;
        private ThreePlayerMatch match;

        private Session(ThreePlayerMatchRules.Format format, Fixture fixture) {
            AtomicLong roundIds = new AtomicLong(1);
            factory = setup -> ThreePlayerRound.ordered(new UUID(804, roundIds.getAndIncrement()), setup.players(),
                    setup.dealerSeat(), setup.roundWind(), setup.honba(), setup.points(), setup.deposits(), order(fixture));
            if (format == null) {
                round = ThreePlayerRound.ordered(new UUID(803, fixture.ordinal()), PLAYERS, 0, 27, 0,
                        List.of(35_000, 35_000, 35_000), 0, order(fixture));
            } else {
                match = new ThreePlayerMatch(new UUID(805, format.ordinal()), PLAYERS, format, 0, factory);
            }
        }

        private ThreePlayerRound.View view(UUID recipient) {
            return match == null ? round.view(recipient) : match.view(recipient).round();
        }

        private ThreePlayerRound.ServerState state() {
            return match == null ? round.serverState() : match.serverState();
        }

        private void discard(UUID player, int tile, boolean riichi) {
            accepted(match == null ? round.discard(player, round.view(player).command(), tile, riichi)
                    : match.discard(player, match.view(player).command(), tile, riichi));
        }

        private void kita(UUID player, int tile) {
            accepted(match == null ? round.kita(player, round.view(player).command(), tile)
                    : match.kita(player, match.view(player).command(), tile));
        }

        private void kan(UUID player, KanActions.Choice choice) {
            accepted(tryKan(player, choice));
        }

        private Status tryKan(UUID player, KanActions.Choice choice) {
            return match == null ? round.declareKan(player, round.view(player).command(), choice)
                    : match.declareKan(player, match.view(player).command(), choice);
        }

        private byte[] archive() {
            return match == null ? ThreePlayerArchiveCodec.encodeRound(round) : ThreePlayerArchiveCodec.encodeMatch(match);
        }

        private void passAll() {
            for (UUID player : PLAYERS) {
                var own = view(player);
                if (!own.responses().isEmpty()) {
                    var id = own.opportunity().orElseThrow().id();
                    accepted(match == null ? round.respond(player, id, Simple.PASS) : match.respond(player, id, Simple.PASS));
                }
            }
        }

        private void roundTrip() {
            var before = PLAYERS.stream().map(this::view).toList();
            var publicBefore = view(null);
            byte[] encoded = match == null ? ThreePlayerArchiveCodec.encodeRound(round) : ThreePlayerArchiveCodec.encodeMatch(match);
            if (match == null) round = ThreePlayerArchiveCodec.decodeRound(encoded);
            else match = ThreePlayerArchiveCodec.decodeMatch(encoded, factory);
            check(before.equals(PLAYERS.stream().map(this::view).toList()) && publicBefore.equals(view(null)),
                    "replay preserves all recipient options and public state, including exhausted waits");
            byte[] after = match == null ? ThreePlayerArchiveCodec.encodeRound(round) : ThreePlayerArchiveCodec.encodeMatch(match);
            check(Arrays.equals(encoded, after), "accepted north/kan and final-account archives remain byte-stable");
        }

        private void advanceMatchIfPresent() {
            if (match == null) return;
            var result = match.view(null);
            check(result.phase() == ThreePlayerMatch.Phase.ROUND_RESULT && result.roundIndex() == 0,
                    "the actual completed first round remains in the match result phase");
            for (UUID player : PLAYERS) accepted(match.confirmNext(player, result.continueId()));
            var next = match.view(null);
            check(next.phase() == ThreePlayerMatch.Phase.PLAYING && next.roundIndex() == 1 && next.round().dealerSeat() == 1,
                    "three real confirmations rotate the non-tenpai dealer");
            check(next.round().honba() == 1 && next.round().deposits() == 1
                    && state().ledger().points().equals(List.of(32_000, 36_000, 36_000)),
                    "normal next-round preparation retains points, deposit and exhaustive honba");
            roundTrip();
        }
    }

    /** Every test starts from all 108 different physical tiles, before the first real draw. */
    private static List<Integer> order(Fixture fixture) {
        boolean[] used = new boolean[136];
        Integer[] order = new Integer[108];
        String[] hands = switch (fixture) {
            case PRESERVING_KAN -> new String[]{"111p234p567s888s4z", "999m222p123s456s5z", "111s999p555z666z1m"};
            case CHANGING_KAN -> new String[]{"123p234p345p789s4z", "999m111s222s345s5z", "999p666p555z666z1m"};
            case SEQUENCES -> new String[]{"123456p789s345s4z", "999m111p222s345s5z", "111s999p555z666z2p"};
        };
        for (int seat = 0; seat < 3; seat++) {
            var held = tiles(hands[seat], used);
            check(held.size() == 13, "every relative dealt hand has thirteen physical tiles");
            for (int index = 0; index < 13; index++) order[index * 3 + seat] = held.get(index);
        }
        var live = tiles(fixture == Fixture.PRESERVING_KAN ? "1m8p9s4z" : "1m8p8s4z", used);
        for (int index = 0; index < live.size(); index++) order[39 + index] = live.get(index);
        var replacements = tiles(switch (fixture) {
            case SEQUENCES -> "44z9s";
            case PRESERVING_KAN -> "44z8s";
            case CHANGING_KAN -> "44z3p";
        }, used);
        for (int index = 0; index < replacements.size(); index++) order[100 + index] = replacements.get(index);
        order[98] = tiles("1z", used).getFirst();
        var remaining = ThreePlayerTiles.allTiles().stream().filter(tile -> !used[tile]).iterator();
        for (int index = 0; index < order.length; index++) if (order[index] == null) order[index] = remaining.next();
        check(!remaining.hasNext(), "fixture consumes the complete 108-tile pool exactly once");
        return List.of(order);
    }

    private static List<Integer> tiles(String notation, boolean[] used) {
        var result = new ArrayList<Integer>();
        var digits = new StringBuilder();
        for (char symbol : notation.toCharArray()) {
            if (Character.isDigit(symbol)) {
                digits.append(symbol);
                continue;
            }
            int offset = switch (symbol) {
                case 'm' -> 0;
                case 'p' -> 9;
                case 's' -> 18;
                case 'z' -> 27;
                default -> throw new AssertionError("Invalid fixture suit");
            };
            for (char digit : digits.toString().toCharArray()) {
                int tile = (offset + digit - '1') * 4;
                int end = tile + 4;
                while (tile < end && used[tile]) tile++;
                check(tile < end, "fixture never requests a fifth physical copy");
                ThreePlayerTiles.typeOf(tile);
                used[tile] = true;
                result.add(tile);
            }
            digits.setLength(0);
        }
        return result;
    }

    private static void accepted(Status status) {
        check(status == Status.ACCEPTED, "the real authoritative action is accepted: " + status);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
