package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static dev.riichicraft.rules.DiscardResponseWindow.Call;

/** Ordinary-discard calls only. The round owner supplies wall, seat and established-riichi facts. */
public final class CallActions {
    public record Context(int discarderSeat, int callerSeat, int liveTilesRemaining,
                          int establishedKans, WinContext.Riichi riichi) {
        public Context {
            if (discarderSeat < 0 || discarderSeat > 3 || callerSeat < 0 || callerSeat > 3
                    || discarderSeat == callerSeat) {
                throw new IllegalArgumentException("A call needs two different seats in 0..3");
            }
            if (liveTilesRemaining < 0 || liveTilesRemaining > 70 || establishedKans < 0 || establishedKans > 4) {
                throw new IllegalArgumentException("Invalid live-wall or established-kan count");
            }
            Objects.requireNonNull(riichi, "riichi");
        }
    }

    /** A chi/pon requires an immediate permitted discard; an open kan requires a replacement draw first. */
    public record Application(HandInput hand, Meld meld, int callerSeat, int providerSeat,
                              int claimedTileId, Set<Integer> forbiddenDiscardTypes,
                              Set<Integer> legalDiscardTiles) {
        public Application {
            Objects.requireNonNull(hand, "hand");
            Objects.requireNonNull(meld, "meld");
            forbiddenDiscardTypes = Set.copyOf(forbiddenDiscardTypes);
            legalDiscardTiles = Set.copyOf(legalDiscardTiles);
        }

        public boolean needsReplacementDraw() { return meld.kind() == Meld.Kind.OPEN_KAN; }
    }

    private CallActions() { }

    /** All distinct physical selections, including red/non-red alternatives. No yaku requirement for calls. */
    public static Set<Call> options(HandInput hand, int discardedTileId, Context context) {
        validate(hand, discardedTileId, context);
        if (context.riichi() != WinContext.Riichi.NONE || context.liveTilesRemaining() == 0
                || hand.melds().size() == 4) return Set.of();

        int type = TileCatalog.typeOf(discardedTileId);
        List<Integer> matching = tilesOfType(hand, type);
        Set<Call> result = new HashSet<>();
        for (int a = 0; a < matching.size(); a++) {
            for (int b = a + 1; b < matching.size(); b++) {
                addIfDiscardPossible(result, hand, discardedTileId,
                        new Call(Meld.Kind.PON, List.of(matching.get(a), matching.get(b))), context);
            }
        }
        if (matching.size() == 3 && context.establishedKans() < 4) {
            result.add(new Call(Meld.Kind.OPEN_KAN, matching));
        }
        if (type < 27 && context.callerSeat() == (context.discarderSeat() + 1) % 4) {
            int suitStart = type / 9 * 9;
            for (int start = Math.max(suitStart, type - 2); start <= Math.min(type, suitStart + 6); start++) {
                List<Integer> otherTypes = new ArrayList<>();
                for (int candidate = start; candidate < start + 3; candidate++) {
                    if (candidate != type) otherTypes.add(candidate);
                }
                for (int first : tilesOfType(hand, otherTypes.get(0))) {
                    for (int second : tilesOfType(hand, otherTypes.get(1))) {
                        addIfDiscardPossible(result, hand, discardedTileId,
                                new Call(Meld.Kind.CHI, List.of(first, second)), context);
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    /** Recheck against current facts; rejected calls never alter the immutable input hand. */
    public static Application apply(HandInput hand, int discardedTileId, Context context, Call call) {
        Objects.requireNonNull(call, "call");
        if (!options(hand, discardedTileId, context).contains(call)) {
            throw new IllegalArgumentException("The selected call is not legal for this discard snapshot");
        }
        return execute(hand, discardedTileId, context, call);
    }

    private static void validate(HandInput hand, int tile, Context context) {
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(context, "context");
        if (!hand.isWaiting()) throw new IllegalArgumentException("A caller needs its preceding 13-tile structural hand");
        var completed = new ArrayList<>(hand.concealedTiles());
        completed.add(tile);
        new HandInput(completed, hand.melds()); // Includes exact-ID ownership and fifth-copy validation.
        if (context.riichi() != WinContext.Riichi.NONE && !hand.isClosed()) {
            throw new IllegalArgumentException("An open hand cannot already be in riichi");
        }
        long ownKans = hand.melds().stream().filter(m -> m.tiles().size() == 4).count();
        if (ownKans > context.establishedKans()) {
            throw new IllegalArgumentException("Global established-kan count cannot omit the caller's kans");
        }
    }

    private static List<Integer> tilesOfType(HandInput hand, int type) {
        return hand.concealedTiles().stream().filter(tile -> TileCatalog.typeOf(tile) == type).sorted().toList();
    }

    private static void addIfDiscardPossible(Set<Call> result, HandInput hand, int tile, Call call, Context context) {
        if (!execute(hand, tile, context, call).legalDiscardTiles().isEmpty()) result.add(call);
    }

    private static Application execute(HandInput hand, int tile, Context context, Call call) {
        var concealed = new ArrayList<>(hand.concealedTiles());
        concealed.removeAll(call.handTiles());
        var groupTiles = new ArrayList<>(call.handTiles());
        groupTiles.add(tile);
        Meld meld = new Meld(call.kind(), groupTiles.stream().sorted().toList());
        var groups = new ArrayList<>(hand.melds());
        groups.add(meld);
        Set<Integer> forbidden = new HashSet<>();
        Set<Integer> legal = new HashSet<>();
        if (call.kind() != Meld.Kind.OPEN_KAN) {
            forbidden.add(TileCatalog.typeOf(tile));
            if (call.kind() == Meld.Kind.CHI) {
                int low = TileCatalog.typeOf(call.handTiles().get(0));
                int high = TileCatalog.typeOf(call.handTiles().get(1));
                if (high == low + 1) {
                    if (low % 9 > 0) forbidden.add(low - 1);
                    if (high % 9 < 8) forbidden.add(high + 1);
                }
            }
            concealed.stream().filter(held -> !forbidden.contains(TileCatalog.typeOf(held))).forEach(legal::add);
        }
        return new Application(new HandInput(concealed, groups), meld, context.callerSeat(),
                context.discarderSeat(), tile, forbidden, legal);
    }
}
