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
    public static final int SINGLE=0, EAST=1, SOUTH=2, THREE_SINGLE=3, THREE_EAST=4, THREE_SOUTH=5;
    public static int seatCount(int mode) { return mode>=3?3:4; }

    private final Map<UUID, Lobby> tables = new HashMap<>();
    private final Map<UUID, SeatReference> playerSeats = new HashMap<>();

    public Snapshot createTable(UUID tableId) {
        Objects.requireNonNull(tableId, "tableId");
        return snapshotOf(tables.computeIfAbsent(tableId, Lobby::new));
    }

    /** Restore one archived, fully occupied table before accepting any new joins. Fails atomically. */
    public Snapshot restoreTable(UUID tableId, long revision, int mode, List<Seat> seats) {
        Objects.requireNonNull(tableId, "tableId");
        seats = List.copyOf(seats);
        if (revision < 0 || mode < SINGLE || mode > THREE_SOUTH || seats.size() != seatCount(mode) || tables.containsKey(tableId)) {
            throw new IllegalArgumentException("Invalid or duplicate restored lobby");
        }
        var owned = new java.util.HashSet<UUID>();
        for (int index = 0; index < seatCount(mode); index++) {
            Seat seat = seats.get(index);
            if (seat.index() != index || !seat.occupied() || seat.name().isBlank() || seat.name().length() > 64
                    || !owned.add(seat.playerId()) || playerSeats.containsKey(seat.playerId())) {
                throw new IllegalArgumentException("Invalid or conflicting restored occupant");
            }
        }
        Lobby lobby = new Lobby(tableId);
        lobby.revision = revision;
        lobby.mode = mode;
        for (int index = 0; index < seatCount(mode); index++) {
            Seat seat = seats.get(index);
            lobby.seats[index] = new Occupant(seat.playerId(), seat.name());
        }
        tables.put(tableId, lobby);
        for (int index = 0; index < seatCount(mode); index++) {
            playerSeats.put(seats.get(index).playerId(), new SeatReference(tableId, index));
        }
        return snapshotOf(lobby);
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
        return joinAt(tableId, playerId, name, expectedRevision, -1);
    }

    /** A physical chair specifies its slot; -1 retains the legacy first-available entry. */
    public Result joinAt(UUID tableId, UUID playerId, String name, long expectedRevision, int requestedSeat) {
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
        if (requestedSeat < -1 || requestedSeat >= seatCount(lobby.mode)) return result(Status.INVALID_MODE, lobby);

        SeatReference existingSeat = playerSeats.get(playerId);
        if (existingSeat != null) {
            if (existingSeat.tableId().equals(tableId) && requestedSeat >= 0 && existingSeat.index() != requestedSeat)
                return result(Status.NOT_SEATED, lobby);
            return result(existingSeat.tableId().equals(tableId)
                    ? Status.UNCHANGED : Status.ALREADY_SEATED_ELSEWHERE, lobby);
        }
        for (int index = 0; index < seatCount(lobby.mode); index++) {
            if (requestedSeat >= 0 && index != requestedSeat) continue;
            if (lobby.seats[index] == null) {
                lobby.seats[index] = new Occupant(playerId, name);
                playerSeats.put(playerId, new SeatReference(tableId, index));
                lobby.revision++;
                return result(Status.UPDATED, lobby);
            }
        }
        return result(Status.TABLE_FULL, lobby);
    }

    /** Reindex an unstarted physical layout atomically without changing who is seated. */
    public Snapshot arrange(UUID tableId, Map<UUID,Integer> indices) {
        Lobby lobby = tables.get(Objects.requireNonNull(tableId));
        if (lobby == null) throw new IllegalArgumentException("Unknown table");
        Occupant[] ordered = new Occupant[SEAT_COUNT];
        boolean changed = false;
        for (int old = 0; old < SEAT_COUNT; old++) if (lobby.seats[old] != null) {
            Occupant occupant = lobby.seats[old];
            Integer target = indices.get(occupant.playerId);
            if (target == null || target < 0 || target >= seatCount(lobby.mode) || ordered[target] != null)
                throw new IllegalArgumentException("Invalid physical occupant mapping");
            ordered[target] = occupant;
            changed |= target != old;
        }
        if (changed) {
            System.arraycopy(ordered, 0, lobby.seats, 0, SEAT_COUNT);
            for (int index = 0; index < SEAT_COUNT; index++) if (ordered[index] != null) {
                ordered[index].ready = false;
                playerSeats.put(ordered[index].playerId, new SeatReference(tableId, index));
            }
            lobby.revision++;
        }
        return snapshotOf(lobby);
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

    /** The lobby's initial east controls format; a real change atomically clears every preparation flag. */
    public Result setMode(UUID tableId,UUID playerId,int mode,long expectedRevision) {
        Objects.requireNonNull(tableId,"tableId"); Objects.requireNonNull(playerId,"playerId");
        Lobby lobby=tables.get(tableId);
        if(lobby==null) return new Result(Status.TABLE_NOT_FOUND,null);
        if(lobby.revision!=expectedRevision) return result(Status.STALE_REVISION,lobby);
        if(lobby.seats[0]==null || !lobby.seats[0].playerId.equals(playerId)) return result(Status.NOT_EAST,lobby);
        if(mode<SINGLE || mode>THREE_SOUTH) return result(Status.INVALID_MODE,lobby);
        return applyMode(lobby,mode);
    }

    /** Server-owned furniture adapts capacity without inventing an east player or ejecting occupants. */
    public Result setPlayerCount(UUID tableId,int players) {
        Lobby lobby=tables.get(Objects.requireNonNull(tableId,"tableId"));
        if(lobby==null) return new Result(Status.TABLE_NOT_FOUND,null);
        if(players!=3 && players!=4) return result(Status.INVALID_MODE,lobby);
        return applyMode(lobby,lobby.mode%3+(players==3?3:0));
    }

    private Result applyMode(Lobby lobby,int mode) {
        if(lobby.mode==mode) return result(Status.UNCHANGED,lobby);
        if(mode>=3 && java.util.Arrays.stream(lobby.seats).filter(Objects::nonNull).count()>3) return result(Status.TOO_MANY_PLAYERS,lobby);
        var occupants=java.util.Arrays.stream(lobby.seats).filter(Objects::nonNull).toList();
        if(seatCount(mode)!=seatCount(lobby.mode)) {
            java.util.Arrays.fill(lobby.seats,null);
            for(int index=0;index<occupants.size();index++) { lobby.seats[index]=occupants.get(index); playerSeats.put(occupants.get(index).playerId,new SeatReference(lobby.tableId,index)); }
        }
        lobby.mode=mode;
        for(Occupant occupant:lobby.seats) if(occupant!=null) occupant.ready=false;
        lobby.revision++;
        return result(Status.UPDATED,lobby);
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
        for (int index = 0; index < seatCount(lobby.mode); index++) {
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

    /** Return participants to an unprepared lobby without changing seats or the global index. */
    public Snapshot invalidatePreparation(UUID tableId) {
        Lobby lobby=tables.get(Objects.requireNonNull(tableId,"tableId"));
        if(lobby==null) throw new IllegalArgumentException("Unknown table");
        long before=lobby.revision;
        resetReadiness(tableId);
        if(lobby.revision==before) lobby.revision++;
        return snapshotOf(lobby);
    }

    public Snapshot resetReadiness(UUID tableId) {
        Lobby lobby = tables.get(Objects.requireNonNull(tableId, "tableId"));
        if (lobby == null) throw new IllegalArgumentException("Unknown table");
        boolean changed = false;
        for (Occupant occupant : lobby.seats) {
            if (occupant != null && occupant.ready) {
                occupant.ready = false;
                changed = true;
            }
        }
        if (changed) lobby.revision++;
        return snapshotOf(lobby);
    }

    private static Result result(Status status, Lobby lobby) {
        return new Result(status, snapshotOf(lobby));
    }

    private static Snapshot snapshotOf(Lobby lobby) {
        List<Seat> seats = new ArrayList<>(SEAT_COUNT);
        boolean allReady = true;
        for (int index = 0; index < seatCount(lobby.mode); index++) {
            Occupant occupant = lobby.seats[index];
            if (occupant == null) {
                seats.add(new Seat(index, null, "", false));
                allReady = false;
            } else {
                seats.add(new Seat(index, occupant.playerId, occupant.name, occupant.ready));
                allReady &= occupant.ready;
            }
        }
        return new Snapshot(lobby.tableId, lobby.revision, seats, allReady, lobby.mode);
    }

    public enum Status {
        UPDATED,
        UNCHANGED,
        STALE_REVISION,
        TABLE_FULL,
        ALREADY_SEATED_ELSEWHERE,
        NOT_SEATED,
        TABLE_NOT_FOUND,
        NOT_EAST,
        INVALID_MODE,
        TOO_MANY_PLAYERS
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

    public record Snapshot(UUID tableId, long revision, List<Seat> seats, boolean allReady, int mode) {
        public Snapshot(UUID tableId,long revision,List<Seat> seats,boolean allReady) {
            this(tableId,revision,seats,allReady,SINGLE);
        }
        public Snapshot {
            Objects.requireNonNull(tableId, "tableId");
            seats = List.copyOf(seats);
            if (seats.size() != seatCount(mode) || mode<SINGLE || mode>THREE_SOUTH) {
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
        private int mode=SINGLE;

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
