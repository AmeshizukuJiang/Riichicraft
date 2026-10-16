package dev.riichicraft.network;

import dev.riichicraft.Riichicraft;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** An intention bound to a server-issued view, never a client-selected table or player. */
public record LobbyActionPayload(UUID viewId, long revision, int action, boolean ready)
        implements CustomPacketPayload {
    public static final int JOIN = 0;
    public static final int SET_READY = 1;
    public static final int LEAVE = 2;
    public static final int CLOSE = 3;
    public static final Type<LobbyActionPayload> TYPE = new Type<>(Riichicraft.id("lobby_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LobbyActionPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUUID(value.viewId());
                buf.writeLong(value.revision());
                buf.writeVarInt(value.action());
                buf.writeBoolean(value.ready());
            }, buf -> new LobbyActionPayload(buf.readUUID(), buf.readLong(), buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
