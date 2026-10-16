package dev.riichicraft.network;

import dev.riichicraft.server.LobbyService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class LobbyNetworking {
    private LobbyNetworking() { }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(LobbyActionPayload.TYPE, LobbyActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LobbySnapshotPayload.TYPE, LobbySnapshotPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LobbyClosedPayload.TYPE, LobbyClosedPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(LobbyActionPayload.TYPE, (payload, context) ->
                LobbyService.get(context.server()).handleAction(context.player(), payload));
    }
}
