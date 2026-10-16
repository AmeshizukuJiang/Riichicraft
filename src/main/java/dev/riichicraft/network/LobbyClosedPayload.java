package dev.riichicraft.network;

import dev.riichicraft.Riichicraft;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record LobbyClosedPayload(UUID viewId, String reasonKey) implements CustomPacketPayload {
    public static final Type<LobbyClosedPayload> TYPE = new Type<>(Riichicraft.id("lobby_closed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LobbyClosedPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUUID(value.viewId());
                buf.writeUtf(value.reasonKey(), 128);
            }, buf -> new LobbyClosedPayload(buf.readUUID(), buf.readUtf(128)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
