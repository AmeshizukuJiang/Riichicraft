package dev.riichicraft;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.network.LobbyNetworking;
import dev.riichicraft.server.LobbyService;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Riichicraft implements ModInitializer {
    public static final String MOD_ID = "riichicraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModBlocks.initialize();
        LobbyNetworking.initialize();
        LobbyService.initialize();
        LOGGER.info("Riichicraft initialized: mahjong table lobby prototype");
    }
}
