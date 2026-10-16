package dev.riichicraft.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Four-player draw/discard prototype, confined to the adapting server's game thread.
 * This class owns no world, network, lifecycle, win, call or scoring behavior.
 */
public final class DrawDiscardGame {
    public static final int SEAT_COUNT = 4;
    public static final int CLOSED_HAND_SIZE = 13;
    public static final int DEAD_WALL_SIZE = 14;
    private static final int LIVE_TILE_COUNT = TileCatalog.TILE_COUNT - DEAD_WALL_SIZE;

    private final UUID gameId;
    private final List<UUID> players;
    private final List<Integer> shuffledTiles;
    private final List<List<Integer>> closedHands = new ArrayList<>(SEAT_COUNT);
    private final List<List<Integer>> rivers = new ArrayList<>(SEAT_COUNT);
    private final Integer[] drawnTiles = new Integer[SEAT_COUNT];
    private int drawIndex;
    private int currentSeat;
    private long revision;
    private boolean finished;

    public DrawDiscardGame(UUID gameId, List<UUID> players, long seed) {
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.players = List.copyOf(players);
        if (this.players.size() != SEAT_COUNT || new HashSet<>(this.players).size() != SEAT_COUNT) {
            throw new IllegalArgumentException("A draw/discard game needs four distinct players");
        }

        List<Integer> tiles = new ArrayList<>(TileCatalog.allTiles());
        Random random = new Random(seed);
        // An explicit Fisher-Yates shuffle keeps the seed-to-wall mapping independent of list implementations.
        for (int remaining = tiles.size(); remaining > 1; remaining--) {
            Collections.swap(tiles, remaining - 1, random.nextInt(remaining));
        }
        shuffledTiles = List.copyOf(tiles);
        for (int seat = 0; seat < SEAT_COUNT; seat++) {
            closedHands.add(new ArrayList<>(CLOSED_HAND_SIZE));
            rivers.add(new ArrayList<>());
        }
        for (int tile = 0; tile < CLOSED_HAND_SIZE; tile++) {
            for (int seat = 0; seat < SEAT_COUNT; seat++) {
                closedHands.get(seat).add(shuffledTiles.get(drawIndex++));
            }
        }
        closedHands.forEach(Collections::sort);
        drawnTiles[0] = shuffledTiles.get(drawIndex++);
    }

    public UUID gameId() {
        return gameId;
    }

    public List<UUID> players() {
        return players;
    }

    public long revision() {
        return revision;
    }

    public boolean isFinished() {
        return finished;
    }

    /** The active east/south/west/north index, or -1 after the final discard. */
    public int currentSeat() {
        return currentSeat;
    }

    public int liveWallCount() {
        return LIVE_TILE_COUNT - drawIndex;
    }

    /** One successful intention discards once, draws for the next player and advances one revision. */
    public Result discard(UUID playerId, int tileId, long expectedRevision) {
        int seat = players.indexOf(Objects.requireNonNull(playerId, "playerId"));
        if (seat < 0) {
            return result(Status.NOT_A_PLAYER);
        }
        if (expectedRevision != revision) {
            return result(Status.STALE_REVISION);
        }
        if (finished) {
            return result(Status.GAME_FINISHED);
        }
        if (seat != currentSeat) {
            return result(Status.NOT_YOUR_TURN);
        }

        List<Integer> hand = closedHands.get(seat);
        Integer drawnTile = drawnTiles[seat];
        if (!Objects.equals(drawnTile, tileId)) {
            if (!hand.remove(Integer.valueOf(tileId))) {
                return result(Status.TILE_NOT_IN_HAND);
            }
            // A hand discard folds the drawn tile into the next closed hand; a tsumogiri leaves it intact.
            hand.add(drawnTile);
            Collections.sort(hand);
        }
        drawnTiles[seat] = null;
        rivers.get(seat).add(tileId);
        revision++;

        if (liveWallCount() == 0) {
            finished = true;
            currentSeat = -1;
        } else {
            currentSeat = (seat + 1) % SEAT_COUNT;
            drawnTiles[currentSeat] = shuffledTiles.get(drawIndex++);
        }
        return result(Status.UPDATED);
    }

    /**
     * Recipient-specific data suitable for adapting into a network payload. An outsider receives public data only.
     * No opponent hand IDs, future live-wall tiles or dead-wall tiles are included.
     */
    public View view(UUID recipient) {
        int ownSeat = players.indexOf(Objects.requireNonNull(recipient, "recipient"));
        List<SeatView> seats = new ArrayList<>(SEAT_COUNT);
        for (int seat = 0; seat < SEAT_COUNT; seat++) {
            seats.add(new SeatView(seat, players.get(seat), handCount(seat), rivers.get(seat)));
        }
        return new View(gameId, revision, finished, currentSeat, liveWallCount(), seats, ownSeat,
                ownSeat < 0 ? List.of() : closedHands.get(ownSeat), ownSeat < 0 ? null : drawnTiles[ownSeat]);
    }

    /**
     * Complete immutable server-only diagnostic state. Never serialize or broadcast this object to clients.
     * Prefer {@link #view(UUID)} for all network-facing code.
     */
    public ServerState serverState() {
        List<PrivateSeat> seats = new ArrayList<>(SEAT_COUNT);
        for (int seat = 0; seat < SEAT_COUNT; seat++) {
            seats.add(new PrivateSeat(seat, players.get(seat), closedHands.get(seat), drawnTiles[seat], rivers.get(seat)));
        }
        return new ServerState(gameId, revision, finished, currentSeat, seats,
                shuffledTiles.subList(drawIndex, LIVE_TILE_COUNT),
                shuffledTiles.subList(LIVE_TILE_COUNT, TileCatalog.TILE_COUNT));
    }

    private int handCount(int seat) {
        return closedHands.get(seat).size() + (drawnTiles[seat] == null ? 0 : 1);
    }

    private Result result(Status status) {
        return new Result(status, revision);
    }

    public enum Status {
        UPDATED,
        STALE_REVISION,
        NOT_A_PLAYER,
        NOT_YOUR_TURN,
        TILE_NOT_IN_HAND,
        GAME_FINISHED
    }

    public record Result(Status status, long revision) {
        public Result {
            Objects.requireNonNull(status, "status");
        }

        public boolean changed() {
            return status == Status.UPDATED;
        }
    }

    public record View(UUID gameId, long revision, boolean finished, int currentSeat, int liveWallCount,
            List<SeatView> seats, int ownSeat, List<Integer> closedHand, Integer drawnTile) {
        public View {
            seats = List.copyOf(seats);
            closedHand = List.copyOf(closedHand);
        }
    }

    /** Opponents expose only their identity, concealed-tile count and public discard sequence. */
    public record SeatView(int index, UUID playerId, int handCount, List<Integer> discards) {
        public SeatView {
            discards = List.copyOf(discards);
        }
    }

    public record ServerState(UUID gameId, long revision, boolean finished, int currentSeat,
            List<PrivateSeat> seats, List<Integer> liveWall, List<Integer> deadWall) {
        public ServerState {
            seats = List.copyOf(seats);
            liveWall = List.copyOf(liveWall);
            deadWall = List.copyOf(deadWall);
        }
    }

    public record PrivateSeat(int index, UUID playerId, List<Integer> closedHand, Integer drawnTile,
            List<Integer> discards) {
        public PrivateSeat {
            closedHand = List.copyOf(closedHand);
            discards = List.copyOf(discards);
        }
    }
}
