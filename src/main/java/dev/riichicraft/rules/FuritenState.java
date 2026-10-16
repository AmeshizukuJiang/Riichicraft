package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One player's immutable furiten history for one hand. Discard types are historical facts,
 * not the visible river: a tile called away by another player must remain in this set.
 * The authoritative round engine, not a client, supplies the events represented here.
 */
public record FuritenState(Set<Integer> discardedTypes, boolean temporary, boolean riichiPass) {
    public FuritenState {
        discardedTypes = Set.copyOf(Objects.requireNonNull(discardedTypes, "discardedTypes"));
        for (int type : discardedTypes) {
            if (type < 0 || type >= TileCatalog.TYPE_COUNT) {
                throw new IllegalArgumentException("Discarded tile types must be in 0..33");
            }
        }
    }

    public static FuritenState initial() {
        return new FuritenState(Set.of(), false, false);
    }

    /** Record an actual discard only. A kan declaration is not a discard. */
    public FuritenState afterDiscard(int physicalTileId) {
        Set<Integer> updated = new LinkedHashSet<>(discardedTypes);
        updated.add(TileCatalog.typeOf(physicalTileId));
        return new FuritenState(updated, temporary, riichiPass);
    }

    /** Only this player's actual draw clears temporary furiten; calls and discards do not. */
    public FuritenState afterDraw() {
        return new FuritenState(discardedTypes, false, riichiPass);
    }

    /**
     * Record a missed ron event already established by the authoritative caller.
     * This primitive does not decide which offers (including no-yaku tiles) trigger an event,
     * or whether riichi has legally become established; those are separate round decisions.
     */
    public FuritenState afterPassedRon(WinContext.Riichi establishedRiichi) {
        Objects.requireNonNull(establishedRiichi, "establishedRiichi");
        return establishedRiichi == WinContext.Riichi.NONE
                ? new FuritenState(discardedTypes, true, riichiPass)
                : new FuritenState(discardedTypes, temporary, true);
    }
}
