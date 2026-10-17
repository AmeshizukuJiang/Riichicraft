package dev.riichicraft.network;

import dev.riichicraft.Riichicraft;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** An intention bound to a server-issued view, never a client-selected table or player. */
public record LobbyActionPayload(UUID viewId, long revision, int action, boolean ready, int mode)
        implements CustomPacketPayload {
    public static final int JOIN = 0;
    public static final int SET_READY = 1;
    public static final int LEAVE = 2;
    public static final int CLOSE = 3;
    public static final int START = 4;
    public static final int SET_MODE = 5;
    public static final Type<LobbyActionPayload> TYPE = new Type<>(Riichicraft.id("lobby_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LobbyActionPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUUID(value.viewId());
                buf.writeLong(value.revision());
                buf.writeVarInt(value.action());
                buf.writeBoolean(value.ready());
                buf.writeVarInt(value.mode());
            }, buf -> new LobbyActionPayload(buf.readUUID(), buf.readLong(), buf.readVarInt(), buf.readBoolean(),buf.readVarInt()));

    public LobbyActionPayload(UUID viewId,long revision,int action,boolean ready) {
        this(viewId,revision,action,ready,0);
    }

    public LobbyActionPayload {
        if(viewId==null || revision<0 || mode<0 || mode>2) throw new IllegalArgumentException("Invalid lobby action metadata");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
