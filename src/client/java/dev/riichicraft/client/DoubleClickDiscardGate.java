package dev.riichicraft.client;

import java.util.Objects;
import java.util.UUID;

/** Physical-copy double click, invalidated by every authoritative turn revision. */
public final class DoubleClickDiscardGate {
    public static final long WINDOW_NANOS = 300_000_000L;
    private UUID gameId;
    private long revision = -1, firstClick;
    private int tileId = -1;
    private boolean riichi;

    public boolean click(UUID game, long revision, int tile, boolean riichi, long now, boolean legal) {
        if (!legal || game == null || revision < 0 || tile < 0 || tile > 135) {
            reset();
            return false;
        }
        long elapsed = now - firstClick;
        boolean confirmed = Objects.equals(gameId, game) && this.revision == revision
                && tileId == tile && this.riichi == riichi && elapsed >= 0 && elapsed <= WINDOW_NANOS;
        reset();
        if (confirmed) return true;
        gameId = game;
        this.revision = revision;
        tileId = tile;
        this.riichi = riichi;
        firstClick = now;
        return false;
    }

    public void reset() {
        gameId = null;
        revision = -1;
        tileId = -1;
        firstClick = 0;
        riichi = false;
    }
}
