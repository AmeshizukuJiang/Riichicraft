package dev.riichicraft.test;

import dev.riichicraft.Riichicraft;
import dev.riichicraft.client.TileGalleryScreen;
import dev.riichicraft.item.ModItems;
import dev.riichicraft.item.TileAppearance;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;

final class TileClientScenario {
    static void run(ClientGameTestContext context, TestServerContext server, TestServerConnection connection) {
        context.runOnClient(client -> {
            for (int id : TileAppearance.samples()) {
                String key = TileAppearance.key(id);
                for (String path : new String[] {"items/mahjong_tiles/" + key + ".json",
                        "models/item/mahjong_tiles/" + key + ".json", "textures/item/mahjong_tiles/" + key + ".png"}) {
                    if (client.getResourceManager().getResource(Riichicraft.id(path)).isEmpty()) {
                        throw new AssertionError("Missing tile resource: " + path);
                    }
                }
                ItemStackRenderState state = new ItemStackRenderState();
                // NONE omits display scaling: verify actual baked geometry rather than a missing cube.
                client.getItemModelResolver().updateForTopItem(state, ModItems.tileStack(id),
                        ItemDisplayContext.NONE, client.level, client.player, 0);
                var box = state.getModelBoundingBox();
                if (state.isEmpty() || Math.abs(box.getXsize() - 2.0 / 16) > 0.0001
                        || Math.abs(box.getYsize() - 2.75 / 16) > 0.0001
                        || Math.abs(box.getZsize() - 1.25 / 16) > 0.0001) {
                    throw new AssertionError("Every appearance must bake the actual tile body: " + key + " " + box);
                }
            }
            client.gui.toastManager().clear();
            client.gui.setScreen(new TileGalleryScreen(true));
        });
        context.waitTicks(2);
        context.takeScreenshot("riichicraft-tile-models");
        context.runOnClient(client -> client.gui.setScreen(new TileGalleryScreen(false)));
        context.waitTicks(2);
        context.takeScreenshot("riichicraft-tile-faces");
        context.runOnClient(client -> client.gui.setScreen(null));
        server.runCommand("item replace entity @a weapon.mainhand with riichicraft:mahjong_tile["
                + "minecraft:item_model=\"riichicraft:mahjong_tiles/0s\",minecraft:item_name={translate:\"tile.riichicraft.red_five\","
                + "with:[{translate:\"tile.riichicraft.sou\",with:[\"5\"]}]}]");
        server.runCommand("item replace entity @a weapon.offhand with riichicraft:mahjong_tile["
                + "minecraft:item_model=\"riichicraft:mahjong_tiles/1s\",minecraft:item_name={translate:\"tile.riichicraft.sou\",with:[\"1\"]}]");
        connection.waitForClientboundPackets();
        context.waitTicks(15);
        context.runOnClient(client -> {
            if (!net.minecraft.world.item.ItemStack.isSameItemSameComponents(client.player.getMainHandItem(), ModItems.tileStack(88))
                    || !net.minecraft.world.item.ItemStack.isSameItemSameComponents(client.player.getOffhandItem(), ModItems.tileStack(72))) {
                throw new AssertionError("Documented variant command and both held items must reach the actual TCP client");
            }
            client.gui.hud.getChat().clearMessages(true);
        });
        context.takeScreenshot("riichicraft-tile-held");
    }
}
