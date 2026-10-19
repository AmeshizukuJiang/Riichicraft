package dev.riichicraft.network;

import dev.riichicraft.Riichicraft;
import dev.riichicraft.table.TableHandVisibility;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** A recipient-scoped hand scene; it contains no wall, seed, action masks or rule archives. */
public record TableScenePayload(UUID viewId, UUID tableId, ResourceKey<Level> dimension, BlockPos pos,
        UUID gameId, long revision, int ownSeat, List<Integer> sides, boolean mounted,
        List<List<Integer>> hands, List<Integer> drawnTiles, boolean finished, Integer publicStateHash) implements CustomPacketPayload {
    public TableScenePayload(UUID viewId,UUID tableId,ResourceKey<Level> dimension,BlockPos pos,UUID gameId,long revision,int ownSeat,
            List<Integer> sides,boolean mounted,List<List<Integer>> hands,List<Integer> drawnTiles,boolean finished) {
        this(viewId,tableId,dimension,pos,gameId,revision,ownSeat,sides,mounted,hands,drawnTiles,finished,null);
    }
    public static final Type<TableScenePayload> TYPE = new Type<>(Riichicraft.id("table_scene_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TableScenePayload> CODEC =
            StreamCodec.of(TableScenePayload::encode, TableScenePayload::decode);

    public TableScenePayload {
        Objects.requireNonNull(viewId, "view");
        Objects.requireNonNull(tableId, "table");
        Objects.requireNonNull(dimension, "dimension");
        pos = Objects.requireNonNull(pos, "position").immutable();
        sides = List.copyOf(sides);
        if (revision < 0 || sides.size() < 3 || sides.size() > 4
                || sides.stream().anyMatch(side -> side < 0 || side > 3)
                || new HashSet<>(sides).size() != sides.size())
            throw new IllegalArgumentException("Table scene metadata");
        var projection = new TableHandVisibility.Projection(ownSeat, hands, drawnTiles);
        hands = projection.hands();
        drawnTiles = projection.drawnTiles();
        if (sides.size() != hands.size() || mounted && ownSeat < 0)
            throw new IllegalArgumentException("Table scene roster");
        for (int seat = 0; seat < hands.size(); seat++) {
            if (gameId == null && (!hands.get(seat).isEmpty() || drawnTiles.get(seat) != -1)
                    || gameId != null && !finished && ownSeat >= 0 && seat != ownSeat && !hands.get(seat).isEmpty())
                throw new IllegalArgumentException("Table scene visibility");
        }
        if (gameId == null && finished) throw new IllegalArgumentException("Lobby cannot be finished");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void encode(RegistryFriendlyByteBuf b, TableScenePayload p) {
        b.writeUUID(p.viewId()); b.writeUUID(p.tableId()); b.writeResourceKey(p.dimension()); b.writeBlockPos(p.pos());
        b.writeBoolean(p.gameId() != null); if (p.gameId() != null) b.writeUUID(p.gameId());
        b.writeVarLong(p.revision()); b.writeVarInt(p.ownSeat());
        b.writeVarInt(p.sides().size()); p.sides().forEach(b::writeVarInt);
        b.writeBoolean(p.mounted());
        b.writeVarInt(p.hands().size());
        for (var hand : p.hands()) { b.writeVarInt(hand.size()); hand.forEach(b::writeVarInt); }
        b.writeVarInt(p.drawnTiles().size()); p.drawnTiles().forEach(b::writeVarInt);
        b.writeBoolean(p.finished());
        b.writeBoolean(p.publicStateHash()!=null);if(p.publicStateHash()!=null) b.writeInt(p.publicStateHash());
    }

    private static TableScenePayload decode(RegistryFriendlyByteBuf b) {
        try {
            UUID view = b.readUUID(), table = b.readUUID();
            var dim = b.readResourceKey(Registries.DIMENSION); var pos = b.readBlockPos();
            UUID game = b.readBoolean() ? b.readUUID() : null;
            long revision = b.readVarLong(); int own = range(b, -1, 3);
            int count = range(b, 3, 4);
            var sides = new ArrayList<Integer>(count);
            for (int i = 0; i < count; i++) sides.add(range(b, 0, 3));
            boolean mounted = b.readBoolean();
            int handCount = range(b, count, count);
            var hands = new ArrayList<List<Integer>>(handCount);
            for (int i = 0; i < handCount; i++) {
                int length = range(b, 0, 14);
                var hand = new ArrayList<Integer>(length);
                for (int j = 0; j < length; j++) hand.add(range(b, 0, 135));
                hands.add(hand);
            }
            int drawCount = range(b, count, count);
            var draws = new ArrayList<Integer>(drawCount);
            for (int i = 0; i < drawCount; i++) draws.add(range(b, -1, 135));
            boolean finished=b.readBoolean();Integer publicHash=b.readBoolean()?b.readInt():null;
            return new TableScenePayload(view, table, dim, pos, game, revision, own, sides, mounted, hands, draws, finished,publicHash);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DecoderException("Invalid recipient table scene", e);
        }
    }

    private static int range(RegistryFriendlyByteBuf b, int min, int max) {
        int value = b.readVarInt();
        if (value < min || value > max) throw new DecoderException("Recipient table scene bound");
        return value;
    }
}
