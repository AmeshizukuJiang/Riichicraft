package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One player's immutable riichi declaration/establishment/ippatsu state for a single hand. */
public final class RiichiState {
    public enum Phase { UNDECLARED, PENDING, ESTABLISHED }
    public record Context(int seat, int points, int liveTilesRemaining, int discardsMade, boolean anyCallOccurred) {
        public Context {
            if (seat < 0 || seat > 3 || liveTilesRemaining < 0 || liveTilesRemaining > 70 || discardsMade < 0) {
                throw new IllegalArgumentException("Invalid seat, live-wall count or preceding discard count");
            }
        }
    }
    public record Pending(DiscardResponseWindow.Id windowId, int seat, int discardedTileId,
                          WinContext.Riichi kind, Set<Integer> waitTypes) {
        public Pending { waitTypes = Set.copyOf(waitTypes); }
    }
    public record Declaration(RiichiState state, DiscardActions.Result discard) { }
    /** The owner applies this debit and deposit once, atomically with replacing its current state. */
    public record Establishment(RiichiState state, int pointsDelta, int depositSticksAdded) { }

    private final Phase phase;
    private final WinContext.Riichi kind;
    private final boolean ippatsu;
    private final Set<Integer> waitTypes;
    private final Pending pending;

    private RiichiState(Phase phase, WinContext.Riichi kind, boolean ippatsu, Set<Integer> waitTypes, Pending pending) {
        this.phase = phase;
        this.kind = kind;
        this.ippatsu = ippatsu;
        this.waitTypes = Set.copyOf(waitTypes);
        this.pending = pending;
    }

    public static RiichiState initial() {
        return new RiichiState(Phase.UNDECLARED, WinContext.Riichi.NONE, false, Set.of(), null);
    }

    public Phase phase() { return phase; }
    /** Pending declarations do not grant a riichi yaku or permanent missed-ron classification. */
    public WinContext.Riichi establishedRiichi() { return phase == Phase.ESTABLISHED ? kind : WinContext.Riichi.NONE; }
    public boolean ippatsu() { return ippatsu; }
    public Set<Integer> waitTypes() { return waitTypes; }
    public Optional<Pending> pending() { return Optional.ofNullable(pending); }

    /** Map each exact discard to its resulting structural waits, without consulting external tile inventory or furiten. */
    public Map<Integer, Set<Integer>> declarationOptions(HandInput hand, int drawnTileId, Context context) {
        Objects.requireNonNull(context, "context");
        Set<Integer> discards = DiscardActions.afterDrawOptions(hand, drawnTileId, establishedRiichi());
        if (!context.anyCallOccurred() && !hand.melds().isEmpty()) {
            throw new IllegalArgumentException("A declared meld proves that a chi/pon/kan has occurred");
        }
        if (phase != Phase.UNDECLARED || !hand.isClosed() || context.points() < 1_000
                || context.liveTilesRemaining() < 4) return Map.of();
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        for (int tile : discards.stream().sorted().toList()) {
            var concealed = new ArrayList<>(hand.concealedTiles());
            concealed.remove(Integer.valueOf(tile));
            Set<Integer> waits = HandAnalyzer.waits(new HandInput(concealed, hand.melds()));
            if (!waits.isEmpty()) result.put(tile, waits);
        }
        return Map.copyOf(result);
    }

    /** Execute the declaration discard, but defer the 1,000-point debit until this exact response window resolves. */
    public Declaration declare(HandInput hand, int drawnTileId, Context context, FuritenState history,
                               int discardedTileId, DiscardResponseWindow.Id windowId) {
        Objects.requireNonNull(windowId, "windowId");
        Set<Integer> waits = declarationOptions(hand, drawnTileId, context).get(discardedTileId);
        if (waits == null) throw new IllegalArgumentException("The selected discard cannot declare riichi");
        WinContext.Riichi declared = context.discardsMade() == 0 && !context.anyCallOccurred()
                ? WinContext.Riichi.DOUBLE_RIICHI : WinContext.Riichi.RIICHI;
        var pendingDeclaration = new Pending(windowId, context.seat(), discardedTileId, declared, waits);
        var state = new RiichiState(Phase.PENDING, declared, false, waits, pendingDeclaration);
        return new Declaration(state, DiscardActions.afterDraw(hand, drawnTileId, WinContext.Riichi.NONE, history, discardedTileId));
    }

    /** Check the actual window identity, discarder, physical declaration tile and complete resolution. */
    public Establishment resolve(DiscardResponseWindow window) {
        Objects.requireNonNull(window, "window");
        if (phase != Phase.PENDING) throw new IllegalStateException("No pending declaration to resolve");
        if (!pending.windowId().equals(window.id()) || pending.seat() != window.discarderSeat()
                || pending.discardedTileId() != window.discardedTileId()) {
            throw new IllegalArgumentException("The window does not belong to this exact declaration discard");
        }
        var resolution = window.resolution().orElseThrow(() -> new IllegalStateException("Declaration responses are incomplete"));
        if (resolution.outcome() == DiscardResponseWindow.Outcome.RON) {
            return new Establishment(initial(), 0, 0);
        }
        // Calling the declaration tile still establishes riichi, but cancels ippatsu immediately.
        var state = new RiichiState(Phase.ESTABLISHED, kind,
                resolution.outcome() != DiscardResponseWindow.Outcome.CALL, waitTypes, null);
        return new Establishment(state, -1_000, 1);
    }

    /** The next real draw preserves ippatsu, including a possible winning draw; its later discard expires it. */
    public RiichiState afterOwnDraw() {
        requireNotPending();
        return this;
    }

    public RiichiState afterOwnDiscard() {
        requireNotPending();
        return withoutIppatsu();
    }

    /** Only an established chi/pon/kan event; robbery/declaration timing is a separate round decision. */
    public RiichiState afterCallEstablished() {
        requireNotPending();
        return withoutIppatsu();
    }

    private RiichiState withoutIppatsu() {
        return ippatsu ? new RiichiState(phase, kind, false, waitTypes, null) : this;
    }

    private void requireNotPending() {
        if (phase == Phase.PENDING) throw new IllegalStateException("Resolve the declaration discard before further round events");
    }
}
