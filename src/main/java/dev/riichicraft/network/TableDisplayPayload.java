package dev.riichicraft.network;

import dev.riichicraft.Riichicraft;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** A single bounded update or removal. Sequence increases across all sends on one server. */
public record TableDisplayPayload(ResourceKey<Level> dimension, UUID tableId, BlockPos pos,
        long sequence, TableDisplaySnapshot snapshot) implements CustomPacketPayload {
    public static final Type<TableDisplayPayload> TYPE = new Type<>(Riichicraft.id("table_display"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TableDisplayPayload> CODEC = StreamCodec.of(TableDisplayPayload::encode, TableDisplayPayload::decode);
    public TableDisplayPayload {
        if (dimension == null || tableId == null || pos == null || sequence < 0) throw new IllegalArgumentException("table display metadata");
        pos = pos.immutable();
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void encode(RegistryFriendlyByteBuf b, TableDisplayPayload p) {
        b.writeResourceKey(p.dimension()); b.writeUUID(p.tableId()); b.writeBlockPos(p.pos()); b.writeVarLong(p.sequence());
        b.writeBoolean(p.snapshot() != null); if (p.snapshot() == null) return;
        var s = p.snapshot(); b.writeUUID(s.gameId()); b.writeBoolean(s.paused()); b.writeBoolean(s.finished()); b.writeVarInt(s.frontSide());
        writeTiles(b, s.indicators()); b.writeVarInt(s.seats().size());
        for (var seat : s.seats()) {
            b.writeVarInt(seat.side()); b.writeVarInt(seat.concealedCount()); b.writeVarInt(seat.river().size());
            for (var d : seat.river()) { b.writeVarInt(d.tileId()); b.writeBoolean(d.sideways()); }
            b.writeVarInt(seat.melds().size());
            for (var m : seat.melds()) {
                b.writeVarInt(m.kind()); writeTiles(b, m.tiles()); b.writeVarInt(m.providerSeat()); b.writeVarInt(m.claimedTileId()); b.writeVarInt(m.addedTileId());
            }
            writeTiles(b, seat.norths());
        }
    }
    private static TableDisplayPayload decode(RegistryFriendlyByteBuf b) {
        try {
            var dim = b.readResourceKey(Registries.DIMENSION); var id = b.readUUID(); var pos = b.readBlockPos(); long serial = b.readVarLong();
            if (!b.readBoolean()) return new TableDisplayPayload(dim, id, pos, serial, null);
            UUID game = b.readUUID(); boolean paused = b.readBoolean(), finished = b.readBoolean(); int front = range(b, 0, 3);
            var indicators = readTiles(b, 5); int count = range(b, 3, 4); List<TableDisplaySnapshot.Seat> seats = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int side = range(b, 0, 3), concealed = range(b, 0, 14);
                var river = readList(b, TableDisplaySnapshot.MAX_RIVER, in -> new TableDisplaySnapshot.Discard(range(in, 0, 135), in.readBoolean()));
                var melds = readList(b, 4, in -> new TableDisplaySnapshot.Meld(range(in, 0, 3), readTiles(in, 4), range(in, -1, count - 1), range(in, -1, 135), range(in, -1, 135)));
                seats.add(new TableDisplaySnapshot.Seat(side, concealed, river, melds, readTiles(b, 4)));
            }
            return new TableDisplayPayload(dim, id, pos, serial, new TableDisplaySnapshot(game, paused, finished, front, indicators, seats));
        } catch (IllegalArgumentException e) { throw new DecoderException("Invalid public tabletop", e); }
    }
    private static int range(RegistryFriendlyByteBuf b, int lo, int hi) {
        int n = b.readVarInt(); if (n < lo || n > hi) throw new DecoderException("Public tabletop bound"); return n;
    }
    private static <T> List<T> readList(RegistryFriendlyByteBuf b, int max, Function<RegistryFriendlyByteBuf, T> read) {
        int n = range(b, 0, max); List<T> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) result.add(read.apply(b)); return result;
    }
    private static List<Integer> readTiles(RegistryFriendlyByteBuf b, int max) { return readList(b, max, in -> range(in, 0, 135)); }
    private static void writeTiles(RegistryFriendlyByteBuf b, List<Integer> tiles) { b.writeVarInt(tiles.size()); tiles.forEach(b::writeVarInt); }
}
