package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One authoritative, single-threaded response window for an ordinary four-player discard.
 * It collects every responder before deciding. It does not generate legal calls, move tiles,
 * apply payments, or authenticate a network player. Seat indices follow draw order, 0..3.
 */
public final class DiscardResponseWindow {
    /** The round owner supplies a fresh serial for every discard; never reuse it within a round. */
    public record Id(UUID roundId, long serial) {
        public Id {
            Objects.requireNonNull(roundId, "roundId");
            if (serial < 0) throw new IllegalArgumentException("Window serial cannot be negative");
        }
    }

    public sealed interface Choice permits Simple, Call { }
    public enum Simple implements Choice { PASS, RON }

    /** Exact held copies selected for a call, excluding the opponent's discarded tile. */
    public record Call(Meld.Kind kind, List<Integer> handTiles) implements Choice {
        public Call {
            Objects.requireNonNull(kind, "kind");
            if (kind == Meld.Kind.CLOSED_KAN) throw new IllegalArgumentException("An opponent discard cannot form an ankan");
            handTiles = Objects.requireNonNull(handTiles, "handTiles").stream().sorted().toList();
            if (handTiles.size() != (kind == Meld.Kind.OPEN_KAN ? 3 : 2)
                    || new HashSet<>(handTiles).size() != handTiles.size()) {
                throw new IllegalArgumentException("A call needs distinct held physical tiles of the required count");
            }
            handTiles.forEach(TileCatalog::typeOf);
        }
    }

    /**
     * A server-only snapshot before any response changes the hand. calls must already satisfy
     * full action rules (wall/kan availability, kuikae, etc.); this layer checks their basic
     * structure, ownership, chi direction and established-riichi restrictions again.
     */
    public record Participant(int seat, HandInput hand, WinContext context,
                              FuritenState history, Set<Call> calls) {
        public Participant {
            requireSeat(seat);
            Objects.requireNonNull(hand, "hand");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(history, "history");
            calls = Set.copyOf(Objects.requireNonNull(calls, "calls"));
            if (!hand.isWaiting()) throw new IllegalArgumentException("A responder needs its preceding 13-tile structural hand");
        }
    }

    public enum Submission { ACCEPTED, WRONG_WINDOW, CLOSED, NOT_A_RESPONDER, ALREADY_RESPONDED, NOT_OFFERED }
    public enum Outcome { NO_CLAIM, RON, CALL }
    public record ChosenCall(int seat, Call choice) {
        public ChosenCall { requireSeat(seat); Objects.requireNonNull(choice, "choice"); }
    }

    /** Winners are in draw order after the discarder; all ron winners remain, including three. */
    public record Resolution(Id id, Outcome outcome, List<Integer> ronSeats,
                             Map<Integer, ScoreResult> ronValues, Optional<ChosenCall> call,
                             Map<Integer, FuritenState> historiesAfter) {
        public Resolution {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(outcome, "outcome");
            ronSeats = List.copyOf(ronSeats);
            ronValues = Map.copyOf(ronValues);
            Objects.requireNonNull(call, "call");
            historiesAfter = Map.copyOf(historiesAfter);
            if (new HashSet<>(ronSeats).size() != ronSeats.size()
                    || !ronValues.keySet().equals(Set.copyOf(ronSeats))
                    || (outcome == Outcome.RON) != !ronSeats.isEmpty()
                    || (outcome == Outcome.CALL) != call.isPresent()) {
                throw new IllegalArgumentException("Inconsistent discard resolution");
            }
        }
    }

    private final Id id;
    private final int discarderSeat;
    private final int discardedTileId;
    private final List<Participant> participants;
    private final Map<Integer, Set<Choice>> offers = new HashMap<>();
    private final Map<Integer, WinEvaluator.Decision> ronDecisions = new HashMap<>();
    private final Map<Integer, Choice> responses = new HashMap<>();
    private Resolution resolution;

    public DiscardResponseWindow(Id id, int discarderSeat, int discardedTileId, List<Participant> participants) {
        this.id = Objects.requireNonNull(id, "id");
        requireSeat(discarderSeat);
        this.discarderSeat = discarderSeat;
        TileCatalog.typeOf(discardedTileId);
        this.discardedTileId = discardedTileId;
        this.participants = List.copyOf(Objects.requireNonNull(participants, "participants")).stream()
                .sorted(Comparator.comparingInt(p -> distance(discarderSeat, p.seat()))).toList();
        validateSnapshot();
        for (Participant participant : this.participants) {
            var completedTiles = new ArrayList<>(participant.hand().concealedTiles());
            completedTiles.add(discardedTileId);
            HandInput completed = new HandInput(completedTiles, participant.hand().melds());
            var decision = WinEvaluator.evaluate(completed, participant.context(), participant.history(), WinEvaluator.Source.DISCARD);
            ronDecisions.put(participant.seat(), decision);
            Set<Choice> choices = new HashSet<>(participant.calls());
            choices.add(Simple.PASS);
            if (decision.allowed()) choices.add(Simple.RON);
            offers.put(participant.seat(), Set.copyOf(choices));
            if (choices.size() == 1) responses.put(participant.seat(), Simple.PASS);
        }
        resolveWhenComplete();
    }

    public Id id() { return id; }
    public int discarderSeat() { return discarderSeat; }
    public int discardedTileId() { return discardedTileId; }
    public Set<Choice> offers(int seat) { requireResponder(seat); return offers.get(seat); }
    public WinEvaluator.Decision ronDecision(int seat) { requireResponder(seat); return ronDecisions.get(seat); }
    public Map<Integer, Choice> responses() { return Map.copyOf(responses); }
    public Optional<Resolution> resolution() { return Optional.ofNullable(resolution); }
    public Set<Integer> pendingSeats() {
        Set<Integer> pending = new HashSet<>(offers.keySet());
        pending.removeAll(responses.keySet());
        return Set.copyOf(pending);
    }

    /** One immutable choice per seat; another seat's response never invalidates this window ID. */
    public Submission submit(Id expectedWindow, int seat, Choice choice) {
        if (!id.equals(expectedWindow)) return Submission.WRONG_WINDOW;
        if (resolution != null) return Submission.CLOSED;
        if (!offers.containsKey(seat)) return Submission.NOT_A_RESPONDER;
        if (responses.containsKey(seat)) return Submission.ALREADY_RESPONDED;
        if (choice == null || !offers.get(seat).contains(choice)) return Submission.NOT_OFFERED;
        responses.put(seat, choice);
        resolveWhenComplete();
        return Submission.ACCEPTED;
    }

    private void validateSnapshot() {
        if (participants.size() != 3) throw new IllegalArgumentException("A discard needs the other three seat snapshots");
        Set<Integer> seats = new HashSet<>();
        Set<Integer> winds = new HashSet<>();
        boolean[] held = new boolean[TileCatalog.TILE_COUNT];
        addPhysical(held, discardedTileId);
        WinContext common = participants.getFirst().context();
        common.doraIndicators().forEach(tile -> addPhysical(held, tile));
        common.uraIndicators().forEach(tile -> addPhysical(held, tile));
        for (Participant participant : participants) {
            if (participant.seat() == discarderSeat || !seats.add(participant.seat())) {
                throw new IllegalArgumentException("Responders must be the three different non-discarding seats");
            }
            WinContext context = participant.context();
            if (context.tsumo() || context.winningTileId() != discardedTileId
                    || context.event() != WinContext.WinEvent.NORMAL && context.event() != WinContext.WinEvent.HOUTEI
                    || context.roundWind() != common.roundWind() || context.event() != common.event()
                    || !context.doraIndicators().equals(common.doraIndicators())
                    || !context.uraIndicators().equals(common.uraIndicators())
                    || !winds.add(context.seatWind())) {
                throw new IllegalArgumentException("Ron contexts must describe the same discard with distinct seat winds");
            }
            participant.hand().concealedTiles().forEach(tile -> addPhysical(held, tile));
            participant.hand().melds().forEach(meld -> meld.tiles().forEach(tile -> addPhysical(held, tile)));
            if (!participant.calls().isEmpty() && context.riichi() != WinContext.Riichi.NONE) {
                throw new IllegalArgumentException("Established riichi cannot offer chi, pon or open kan");
            }
            for (Call call : participant.calls()) validateCall(participant, call);
        }
    }

    private void validateCall(Participant participant, Call call) {
        if (participant.hand().melds().size() == 4 || !participant.hand().concealedTiles().containsAll(call.handTiles())) {
            throw new IllegalArgumentException("Call tiles must belong to this concealed hand with a free meld slot");
        }
        if (call.kind() == Meld.Kind.CHI && participant.seat() != (discarderSeat + 1) % 4) {
            throw new IllegalArgumentException("Chi is restricted to the player after the discarder");
        }
        var meldTiles = new ArrayList<>(call.handTiles());
        meldTiles.add(discardedTileId);
        new Meld(call.kind(), meldTiles);
    }

    private void resolveWhenComplete() {
        if (responses.size() != 3) return;
        List<Integer> winners = new ArrayList<>();
        Map<Integer, ScoreResult> values = new LinkedHashMap<>();
        Map<Integer, FuritenState> histories = new LinkedHashMap<>();
        ChosenCall chosen = null;
        for (Participant participant : participants) {
            Choice choice = responses.get(participant.seat());
            if (choice == Simple.RON) {
                winners.add(participant.seat());
                values.put(participant.seat(), ronDecisions.get(participant.seat()).handValue().orElseThrow());
                histories.put(participant.seat(), participant.history());
            } else {
                histories.put(participant.seat(), FuritenTracker.afterDiscardWithoutRon(participant.hand(),
                        discardedTileId, participant.history(), participant.context().riichi()));
                if (choice instanceof Call call && (chosen == null || priority(call) > priority(chosen.choice()))) {
                    chosen = new ChosenCall(participant.seat(), call);
                }
            }
        }
        Outcome outcome = !winners.isEmpty() ? Outcome.RON : chosen != null ? Outcome.CALL : Outcome.NO_CLAIM;
        resolution = new Resolution(id, outcome, winners, values,
                outcome == Outcome.CALL ? Optional.of(chosen) : Optional.empty(), histories);
    }

    private static int priority(Call call) { return call.kind() == Meld.Kind.CHI ? 1 : 2; }
    private void requireResponder(int seat) {
        if (!offers.containsKey(seat)) throw new IllegalArgumentException("Not a responder seat");
    }
    private static int distance(int from, int to) { return (to - from + 4) % 4; }
    private static void requireSeat(int seat) {
        if (seat < 0 || seat > 3) throw new IllegalArgumentException("Seat must be in 0..3");
    }
    private static void addPhysical(boolean[] held, int tile) {
        TileCatalog.typeOf(tile);
        if (held[tile]) throw new IllegalArgumentException("A physical tile has conflicting ownership in the discard snapshot");
        held[tile] = true;
    }
}
