package dev.riichicraft.table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Recipient-specific hand projection. Being off the chair never makes a participant a spectator. */
public final class TableHandVisibility {
    private TableHandVisibility() { }

    public record Projection(int ownSeat, List<List<Integer>> hands, List<Integer> drawnTiles) {
        public Projection {
            Objects.requireNonNull(hands, "hands");
            Objects.requireNonNull(drawnTiles, "drawn tiles");
            int count = hands.size();
            if (count < 3 || count > 4 || drawnTiles.size() != count || ownSeat < -1 || ownSeat >= count)
                throw new IllegalArgumentException("Hand projection seats");
            var copied = new ArrayList<List<Integer>>(count);
            var identities = new HashSet<Integer>();
            for (int seat = 0; seat < count; seat++) {
                var hand = List.copyOf(hands.get(seat));
                if (hand.size() > 14) throw new IllegalArgumentException("Hand projection length");
                for (int tile : hand)
                    if (tile < 0 || tile > 135 || !identities.add(tile))
                        throw new IllegalArgumentException("Hand projection tile identity");
                int drawn = drawnTiles.get(seat);
                if (drawn < -1 || drawn > 135 || drawn >= 0 && !hand.contains(drawn))
                    throw new IllegalArgumentException("Hand projection draw");
                copied.add(hand);
            }
            hands = List.copyOf(copied);
            drawnTiles = List.copyOf(drawnTiles);
        }
    }

    /**
     * Participants come from the stored round roster, not current mounts or nearby players.
     * All hands are available to a non-participant observer, and are laid down at settlement.
     */
    public static Projection project(UUID recipient, List<UUID> participants, List<List<Integer>> hands,
            List<Integer> drawnTiles, boolean finished) {
        Objects.requireNonNull(recipient, "recipient");
        participants = List.copyOf(participants);
        if (participants.size() != hands.size() || new HashSet<>(participants).size() != participants.size())
            throw new IllegalArgumentException("Stored participants");
        int own = participants.indexOf(recipient);
        var source = new Projection(own, hands, drawnTiles);
        if (own == -1 || finished) return source;
        var visible = new ArrayList<List<Integer>>(participants.size());
        var draws = new ArrayList<Integer>(participants.size());
        for (int seat = 0; seat < participants.size(); seat++) {
            visible.add(seat == own ? source.hands().get(seat) : List.of());
            draws.add(seat == own ? source.drawnTiles().get(seat) : -1);
        }
        return new Projection(own, visible, draws);
    }
}
