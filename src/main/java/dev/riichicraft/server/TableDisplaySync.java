package dev.riichicraft.server;

import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.network.TableDisplaySnapshot;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Nearby chunk-tracking observers are independent of lobby views and player seats. */
final class TableDisplaySync {
    record Table(ResourceKey<Level> dimension, UUID id, BlockPos pos, TableDisplaySnapshot snapshot) { }
    private record Observer(ServerPlayer player, ResourceKey<Level> dimension, Map<UUID, Table> sent) { }
    private final Map<UUID, Observer> observers = new HashMap<>();
    private long sequence;
    void forget(UUID player) { observers.remove(player); }

    void tick(MinecraftServer server, List<Table> tables) {
        observers.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!ServerPlayNetworking.canSend(player, TableDisplayPayload.TYPE)) { observers.remove(player.getUUID()); continue; }
            Observer old = observers.get(player.getUUID());
            if (old != null && (old.player() != player || !old.dimension().equals(player.level().dimension()))) {
                for (Table table : old.sent().values()) send(player, table, null);
                old = null;
            }
            Observer observer = old == null ? new Observer(player, player.level().dimension(), new HashMap<>()) : old;
            observers.put(player.getUUID(), observer);
            Map<UUID, Table> visible = new LinkedHashMap<>();
            tables.stream().filter(t -> t.dimension().equals(player.level().dimension())
                    && Vec3.atCenterOf(t.pos()).distanceToSqr(player.position()) <= 32 * 32
                    && PlayerLookup.tracking(player.level(), t.pos()).contains(player))
                    .sorted(Comparator.comparingDouble((Table t) -> Vec3.atCenterOf(t.pos()).distanceToSqr(player.position()))
                            .thenComparing(Table::id)).limit(64).forEach(t -> visible.put(t.id(), t));
            for (var entry : List.copyOf(observer.sent().entrySet())) {
                if (!visible.containsKey(entry.getKey())) { send(player, entry.getValue(), null); observer.sent().remove(entry.getKey()); }
            }
            for (Table table : visible.values()) {
                if (!table.equals(observer.sent().get(table.id()))) {
                    send(player, table, table.snapshot()); observer.sent().put(table.id(), table);
                }
            }
        }
    }
    private void send(ServerPlayer player, Table table, TableDisplaySnapshot snapshot) {
        ServerPlayNetworking.send(player, new TableDisplayPayload(table.dimension(), table.id(), table.pos(),
                sequence = Math.incrementExact(sequence), snapshot));
    }
}
