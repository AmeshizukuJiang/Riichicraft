package dev.riichicraft.lobby;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Temporary lobby state owned by one Minecraft server. Call mutations on that server's thread.
 * World access, network sessions, persistence and match rules belong to the adapting layer.
 */
public final class LobbyRegistry {
    public static final int SEAT_COUNT = 4;

    private final Map<UUID, Lobby> tables = new HashMap<>();
    private final Map<UUID, SeatReference> playerSeats = new HashMap<>();

    public Snapshot createTable(UUID tableId) {
        Objects.requireNonNull(tableId, "tableId");
        return snapshotOf(tables.computeIfAbsent(tableId, Lobby::new));
    }

    public Optional<Snapshot> snapshot(UUID tableId) {
        Lobby lobby = tables.get(Objects.requireNonNull(tableId, "tableId"));
        return lobby == null ? Optional.empty() : Optional.of(snapshotOf(lobby));
    }

    public Optional<UUID> tableForPlayer(UUID playerId) {
        SeatReference seat = playerSeats.get(Objects.requireNonNull(playerId, "playerId"));
        return seat == null ? Optional.empty() : Optional.of(seat.tableId());
    }

    public Result join(UUID tableId, UUID playerId, String name, long expectedRevision) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A seated player must have a non-blank name");
        }
        Lobby lobby = tables.get(tableId);
        if (lobby == null) {
            return new Result(Status.TABLE_NOT_FOUND, null);
        }
        if (lobby.revision != expectedRevision) {
            return result(Status.STALE_REVISION, lobby);
        }

        SeatReference existingSeat = playerSeats.get(playerId);
        if (existingSeat != null) {
            return result(existingSeat.tableId().equals(tableId)
                    ? Status.UNCHANGED : Status.ALREADY_SEATED_ELSEWHERE, lobby);
        }
        for (int index = 0; index < SEAT_COUNT; index++) {
            if (lobby.seats[index] == null) {
                lobby.seats[index] = new Occupant(playerId, name);
                playerSeats.put(playerId, new SeatReference(tableId, index));
                lobby.revision++;
                return result(Status.UPDATED, lobby);
            }
        }
        return result(Status.TABLE_FULL, lobby);
    }

    public Result setReady(UUID tableId, UUID playerId, boolean ready, long expectedRevision) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(playerId, "playerId");
        Lobby lobby = tables.get(tableId);
        if (lobby == null) {
            return new Result(Status.TABLE_NOT_FOUND, null);
        }
        if (lobby.revision != expectedRevision) {
            return result(Status.STALE_REVISION, lobby);
        }
        SeatReference seat = playerSeats.get(playerId);
        if (seat == null || !seat.tableId().equals(tableId)) {
            return result(Status.NOT_SEATED, lobby);
        }
        Occupant occupant = lobby.seats[seat.index()];
        if (occupant.ready == ready) {
            return result(Status.UNCHANGED, lobby);
        }
        occupant.ready = ready;
        lobby.revision++;
        return result(Status.UPDATED, lobby);
    }

    public Result leave(UUID tableId, UUID playerId, long expectedRevision) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(playerId, "playerId");
        Lobby lobby = tables.get(tableId);
        if (lobby == null) {
            return new Result(Status.TABLE_NOT_FOUND, null);
        }
        if (lobby.revision != expectedRevision) {
            return result(Status.STALE_REVISION, lobby);
        }
        SeatReference seat = playerSeats.get(playerId);
        if (seat == null || !seat.tableId().equals(tableId)) {
            return result(Status.NOT_SEATED, lobby);
        }
        lobby.seats[seat.index()] = null;
        playerSeats.remove(playerId);
        lobby.revision++;
        return result(Status.UPDATED, lobby);
    }

    /** Lifecycle cleanup ignores UI revisions and returns the changed table for broadcasting. */
    public Optional<Snapshot> disconnect(UUID playerId) {
        SeatReference seat = playerSeats.remove(Objects.requireNonNull(playerId, "playerId"));
        if (seat == null) {
            return Optional.empty();
        }
        Lobby lobby = tables.get(seat.tableId());
        lobby.seats[seat.index()] = null;
        lobby.revision++;
        return Optional.of(snapshotOf(lobby));
    }

    public boolean removeTable(UUID tableId) {
        Lobby lobby = tables.remove(Objects.requireNonNull(tableId, "tableId"));
        if (lobby == null) {
            return false;
        }
        for (int index = 0; index < SEAT_COUNT; index++) {
            Occupant occupant = lobby.seats[index];
            if (occupant != null) {
                playerSeats.remove(occupant.playerId, new SeatReference(tableId, index));
            }
        }
        return true;
    }

    public void clear() {
        tables.clear();
        playerSeats.clear();
    }

    private static Result result(Status status, Lobby lobby) {
        return new Result(status, snapshotOf(lobby));
    }

    private static Snapshot snapshotOf(Lobby lobby) {
        List<Seat> seats = new ArrayList<>(SEAT_COUNT);
        boolean allReady = true;
        for (int index = 0; index < SEAT_COUNT; index++) {
            Occupant occupant = lobby.seats[index];
            if (occupant == null) {
                seats.add(new Seat(index, null, "", false));
                allReady = false;
            } else {
                seats.add(new Seat(index, occupant.playerId, occupant.name, occupant.ready));
                allReady &= occupant.ready;
            }
        }
        return new Snapshot(lobby.tableId, lobby.revision, seats, allReady);
    }

    public enum Status {
        UPDATED,
        UNCHANGED,
        STALE_REVISION,
        TABLE_FULL,
        ALREADY_SEATED_ELSEWHERE,
        NOT_SEATED,
        TABLE_NOT_FOUND
    }

    /** snapshot is null only for TABLE_NOT_FOUND. */
    public record Result(Status status, Snapshot snapshot) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (status != Status.TABLE_NOT_FOUND) {
                Objects.requireNonNull(snapshot, "snapshot");
            }
        }

        public boolean changed() {
            return status == Status.UPDATED;
        }

        public boolean successful() {
            return status == Status.UPDATED || status == Status.UNCHANGED;
        }
    }

    public record Snapshot(UUID tableId, long revision, List<Seat> seats, boolean allReady) {
        public Snapshot {
            Objects.requireNonNull(tableId, "tableId");
            seats = List.copyOf(seats);
            if (seats.size() != SEAT_COUNT) {
                throw new IllegalArgumentException("A lobby snapshot must contain exactly four seats");
            }
        }
    }

    /** Indices 0..3 are the lobby's east, south, west and north labels, not match wind assignments. */
    public record Seat(int index, UUID playerId, String name, boolean ready) {
        public Seat {
            if (index < 0 || index >= SEAT_COUNT) {
                throw new IllegalArgumentException("Invalid lobby seat index: " + index);
            }
            Objects.requireNonNull(name, "name");
            if (playerId == null && (ready || !name.isEmpty())) {
                throw new IllegalArgumentException("An empty seat cannot have a name or be ready");
            }
        }

        public boolean occupied() {
            return playerId != null;
        }
    }

    private record SeatReference(UUID tableId, int index) {
    }

    private static final class Lobby {
        private final UUID tableId;
        private final Occupant[] seats = new Occupant[SEAT_COUNT];
        private long revision;

        private Lobby(UUID tableId) {
            this.tableId = tableId;
        }
    }

    private static final class Occupant {
        private final UUID playerId;
        private final String name;
        private boolean ready;

        private Occupant(UUID playerId, String name) {
            this.playerId = playerId;
            this.name = name;
        }
    }
}
