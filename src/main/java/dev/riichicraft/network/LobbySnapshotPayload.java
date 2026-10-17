package dev.riichicraft.network;

import dev.riichicraft.Riichicraft;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record LobbySnapshotPayload(UUID viewId, UUID tableId, BlockPos pos, long revision,
        List<Seat> seats, int ownSeat, boolean allReady, String feedbackKey, GameSnapshot game, int mode)
        implements CustomPacketPayload {
    public static final Type<LobbySnapshotPayload> TYPE = new Type<>(Riichicraft.id("lobby_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LobbySnapshotPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUUID(value.viewId());
                buf.writeUUID(value.tableId());
                buf.writeBlockPos(value.pos());
                buf.writeLong(value.revision());
                for (Seat seat : value.seats()) {
                    buf.writeBoolean(seat.playerId() != null);
                    if (seat.playerId() != null) {
                        buf.writeUUID(seat.playerId());
                        buf.writeUtf(seat.name(), 64);
                        buf.writeBoolean(seat.ready());
                    }
                }
                buf.writeByte(value.ownSeat());
                buf.writeBoolean(value.allReady());
                buf.writeUtf(value.feedbackKey(), 128);
                buf.writeBoolean(value.game() != null);
                if (value.game() != null) {
                    GameSnapshot.CODEC.encode(buf, value.game());
                }
                buf.writeVarInt(value.mode());
            }, buf -> {
                UUID view = buf.readUUID();
                UUID table = buf.readUUID();
                BlockPos pos = buf.readBlockPos();
                long revision = buf.readLong();
                List<Seat> seats = new ArrayList<>(4);
                for (int index = 0; index < 4; index++) {
                    seats.add(buf.readBoolean() ? new Seat(buf.readUUID(), buf.readUtf(64), buf.readBoolean())
                            : new Seat(null, "", false));
                }
                int ownSeat = buf.readByte();
                boolean allReady = buf.readBoolean();
                String feedbackKey = buf.readUtf(128);
                GameSnapshot game = buf.readBoolean() ? GameSnapshot.CODEC.decode(buf) : null;
                return new LobbySnapshotPayload(view, table, pos, revision, seats, ownSeat,
                        allReady, feedbackKey, game, buf.readVarInt());
            });

    public LobbySnapshotPayload(UUID viewId, UUID tableId, BlockPos pos, long revision,
            List<Seat> seats, int ownSeat, boolean allReady, String feedbackKey) {
        this(viewId, tableId, pos, revision, seats, ownSeat, allReady, feedbackKey, null,0);
    }

    public LobbySnapshotPayload(UUID viewId, UUID tableId, BlockPos pos, long revision,
            List<Seat> seats, int ownSeat, boolean allReady, String feedbackKey, GameSnapshot game) {
        this(viewId,tableId,pos,revision,seats,ownSeat,allReady,feedbackKey,game,0);
    }

    public LobbySnapshotPayload {
        seats = List.copyOf(seats);
        pos = pos.immutable();
        if (seats.size() != 4 || ownSeat < -1 || ownSeat > 3 || mode<0 || mode>2) {
            throw new IllegalArgumentException("A lobby snapshot must contain four seats and a valid own seat");
        }
        if (ownSeat == -1 && game != null && (!game.ownHand().isEmpty() || game.drawnTile() != -1)) {
            throw new IllegalArgumentException("A spectator snapshot cannot contain a private hand or drawn tile");
        }
        if (ownSeat == -1 && game != null && game.rules() != null) {
            var rules=game.rules();
            if (!rules.legalDiscards().isEmpty() || !rules.riichiDiscards().isEmpty() || !rules.kans().isEmpty()
                    || rules.canTsumo() || rules.canNineTerminals() || !rules.responses().isEmpty()) {
                throw new IllegalArgumentException("A spectator snapshot cannot contain private legal-action information");
            }
        }
    }

    public record Seat(UUID playerId, String name, boolean ready) { }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
