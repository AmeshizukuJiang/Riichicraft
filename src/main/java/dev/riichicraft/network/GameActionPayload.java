package dev.riichicraft.network;

import dev.riichicraft.Riichicraft;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A game intention still requires server-side view, owner, turn and revision authorization. */
public record GameActionPayload(UUID viewId, UUID gameId, long revision, int action, int tileId)
        implements CustomPacketPayload {
    public static final int DISCARD = 0;
    public static final int RESUME = 1;
    public static final int ABORT = 2;
    public static final Type<GameActionPayload> TYPE = new Type<>(Riichicraft.id("game_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GameActionPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUUID(value.viewId());
                buf.writeUUID(value.gameId());
                buf.writeLong(value.revision());
                buf.writeVarInt(value.action());
                buf.writeVarInt(value.tileId());
            }, buf -> new GameActionPayload(buf.readUUID(), buf.readUUID(), buf.readLong(),
                    buf.readVarInt(), buf.readVarInt()));

    public GameActionPayload {
        Objects.requireNonNull(viewId, "viewId");
        Objects.requireNonNull(gameId, "gameId");
        if (revision < 0 || action < DISCARD || action > ABORT
                || (action == DISCARD ? tileId < 0 || tileId > 135 : tileId != -1)) {
            throw new IllegalArgumentException("Invalid game action bounds");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
