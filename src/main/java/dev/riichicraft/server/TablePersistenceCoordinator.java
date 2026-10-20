package dev.riichicraft.server;

import dev.riichicraft.Riichicraft;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.persistence.TableArchiveStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;

/** Server-thread checkpoint policy. Runtime tables, players and core actions belong to LobbyService. */
final class TablePersistenceCoordinator {
    @FunctionalInterface
    interface Writer {
        void write(Path path, List<TableArchiveStore.Table> tables) throws IOException;
    }

    // SKIPPED must neither report success nor repeatedly notify players about an already blocked file.
    enum SaveOutcome { SUCCESS, SKIPPED, FAILED }

    private final Path path;
    private final Writer writer;
    /** Preserve invalid cores exactly while healthy tables continue saving. */
    private final Map<UUID, TableArchiveStore.Table> invalidCores = new HashMap<>();
    private boolean blocked;
    private boolean dirty;
    private boolean saveFailed;
    private boolean detached;

    TablePersistenceCoordinator(Path path, Writer writer) {
        this.path = path;
        this.writer = writer;
    }

    /** Validate the complete ownership index before the service publishes any restored seat. */
    List<TableArchiveStore.Table> loadValidated() {
        try {
            var archived = TableArchiveStore.read(path);
            var validation = new LobbyRegistry();
            for (var table : archived) {
                Identifier.parse(table.dimension());
                validation.restoreTable(table.tableId(), table.lobbyRevision(), table.mode(), restoredSeats(table));
            }
            dirty = !archived.isEmpty();
            return archived;
        } catch (IOException | IllegalArgumentException invalid) {
            blocked = true;
            Riichicraft.LOGGER.error("Riichicraft world archive could not be loaded; table access is blocked until administrator repair: {}", path);
            return List.of();
        }
    }

    static List<LobbyRegistry.Seat> restoredSeats(TableArchiveStore.Table table) {
        var seats = new ArrayList<LobbyRegistry.Seat>(table.seats().size());
        for (int index = 0; index < table.seats().size(); index++) {
            var seat = table.seats().get(index);
            seats.add(new LobbyRegistry.Seat(index, seat.playerId(), seat.name(), false));
        }
        return seats;
    }

    void retainInvalidCore(TableArchiveStore.Table table) {
        invalidCores.put(table.tableId(), table);
        Riichicraft.LOGGER.error("Riichicraft table {} has an invalid game archive; its original record and reserved seats are retained for administrator repair", table.tableId());
    }

    boolean blocked() { return blocked; }
    boolean hasInvalidCore(UUID table) { return invalidCores.containsKey(table); }
    void markDirty() { dirty = true; }

    SaveOutcome save(Supplier<List<TableArchiveStore.Table>> activeRecords) {
        if (blocked || detached) return SaveOutcome.SKIPPED;
        if (!dirty) return saveFailed ? SaveOutcome.SKIPPED : SaveOutcome.SUCCESS;
        try {
            var archived = new ArrayList<>(invalidCores.values());
            archived.addAll(activeRecords.get());
            archived.sort(Comparator.comparing(table -> table.tableId().toString()));
            writer.write(path, archived);
            dirty = false;
            saveFailed = false;
            return SaveOutcome.SUCCESS;
        } catch (IOException | RuntimeException failure) {
            if (!saveFailed) Riichicraft.LOGGER.error("Riichicraft world archive could not be saved; the previous record is retained: {}", path);
            saveFailed = true;
            // Damage introduced since startup must never turn into an empty replacement on a later save.
            try { TableArchiveStore.read(path); }
            catch (IOException invalidExisting) { blocked = true; }
            return SaveOutcome.FAILED;
        }
    }

    /** Native shutdown may save again after the service clears its runtime maps. */
    void detach() {
        detached = true;
        invalidCores.clear();
    }
}
