package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Applies furiten to the full structural wait, regardless of which side has yaku. */
public final class FuritenAnalyzer {
    public record Status(Set<Integer> waitTypes, Set<Integer> discardedWaitTypes,
                         boolean temporary, boolean riichiPass) {
        public Status {
            waitTypes = Set.copyOf(Objects.requireNonNull(waitTypes, "waitTypes"));
            discardedWaitTypes = Set.copyOf(Objects.requireNonNull(discardedWaitTypes, "discardedWaitTypes"));
            for (int type : waitTypes) {
                if (type < 0 || type >= TileCatalog.TYPE_COUNT) {
                    throw new IllegalArgumentException("Wait tile types must be in 0..33");
                }
            }
            if (!waitTypes.containsAll(discardedWaitTypes)) {
                throw new IllegalArgumentException("Discard furiten must refer to a current wait");
            }
        }

        public boolean discardFuriten() { return !discardedWaitTypes.isEmpty(); }
        public boolean blocksRon() { return discardFuriten() || temporary || riichiPass; }
    }

    private FuritenAnalyzer() { }

    /** Requires a 13-tile structural state; this is not an exhaustive-draw tenpai ruling. */
    public static Status evaluate(HandInput waitingHand, FuritenState history) {
        Objects.requireNonNull(history, "history");
        Set<Integer> waits = HandAnalyzer.waits(waitingHand);
        Set<Integer> discardedWaits = new LinkedHashSet<>(waits);
        discardedWaits.retainAll(history.discardedTypes());
        return new Status(waits, discardedWaits, history.temporary(), history.riichiPass());
    }
}
