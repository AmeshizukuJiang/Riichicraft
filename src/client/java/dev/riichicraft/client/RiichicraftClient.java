package dev.riichicraft.client;

import net.fabricmc.api.ClientModInitializer;

public final class RiichicraftClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LobbyClient.initialize();
    }
}
