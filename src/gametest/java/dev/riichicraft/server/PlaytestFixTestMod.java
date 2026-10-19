package dev.riichicraft.server;

import net.fabricmc.api.ModInitializer;

/** Existing rule/interaction regressions use zero cover time; pacing is exercised separately. */
public final class PlaytestFixTestMod implements ModInitializer {
    @Override public void onInitialize() { LobbyService.responseDelayDefaults=()->0; }
    public static void coverTicks(net.minecraft.server.MinecraftServer server,int ticks) {
        LobbyService.get(server).responseDelaySource=()->ticks;
    }
}
