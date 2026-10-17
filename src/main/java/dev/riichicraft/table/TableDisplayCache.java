package dev.riichicraft.table;

import dev.riichicraft.network.TableDisplayPayload;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Ordered connection cache; a level transition clears tables without forgetting sequence. */
public final class TableDisplayCache {
    private final Map<UUID, TableDisplayPayload> entries = new HashMap<>();
    private long lastSequence = -1;
    public void reset() { entries.clear(); lastSequence = -1; }
    public void clearTables() { entries.clear(); }
    public List<TableDisplayPayload> snapshots() { return List.copyOf(entries.values()); }
    public boolean accept(TableDisplayPayload payload, ResourceKey<Level> dimension) {
        if (payload.sequence() <= lastSequence) return false;
        lastSequence = payload.sequence();
        if (!payload.dimension().equals(dimension)) return false;
        if (payload.snapshot() == null) { entries.remove(payload.tableId()); return true; }
        entries.values().removeIf(p -> p.pos().equals(payload.pos()) && !p.tableId().equals(payload.tableId()));
        if (!entries.containsKey(payload.tableId()) && entries.size() >= 64) return false;
        entries.put(payload.tableId(), payload); return true;
    }
}
