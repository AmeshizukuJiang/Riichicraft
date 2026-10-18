package dev.riichicraft.test;

import dev.riichicraft.Riichicraft;
import dev.riichicraft.block.LargeMahjongTableBlock;
import dev.riichicraft.block.ModBlocks;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.mixin.client.gametest.input.MouseHandlerAccessor;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Real survival TCP inventory, recipe-book placement packets, native result clicks and world placement. */
final class SurvivalCraftingClientScenario {
    private record PreviousPlayer(Vec3 position, float yaw, float pitch, GameType mode, int selected, List<ItemStack> inventory) { }

    static void run(ClientGameTestContext context, TestDedicatedServerContext server) {
        CameraType original = context.computeOnClient(client -> client.options.getCameraType());
        context.getInput().resizeWindow(960, 720);
        try (var connection = server.connect()) {
            var previous = server.computeOnServer(s -> {
                var player = connection.getServerPlayer();
                return new PreviousPlayer(player.position(), player.getYRot(), player.getXRot(), player.gameMode.getGameModeForPlayer(),
                        player.getInventory().getSelectedSlot(), player.getInventory().getNonEquipmentItems().stream().map(ItemStack::copy).toList());
            });
            try {
            BlockPos bench = server.computeOnServer(s -> {
                var player = connection.getServerPlayer(); var level = connection.getServerLevel();
                BlockPos pos = new BlockPos(-12, player.blockPosition().getY(), -8);
                for (int x = -3; x <= 7; x++) for (int z = -3; z <= 4; z++) {
                    level.setBlockAndUpdate(pos.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                    for (int y = 0; y <= 4; y++) level.setBlockAndUpdate(pos.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
                level.setBlockAndUpdate(pos, Blocks.CRAFTING_TABLE.defaultBlockState());
                player.stopRiding(); player.setGameMode(GameType.SURVIVAL);
                player.getInventory().clearContent();
                List<ItemStack> materials = List.of(new ItemStack(Items.OAK_PLANKS, 26), new ItemStack(Items.IRON_INGOT, 2),
                        new ItemStack(Items.WOOL.pick(DyeColor.GREEN), 5), new ItemStack(Items.STICK, 8),
                        new ItemStack(Items.DYE.pick(DyeColor.GRAY)), new ItemStack(Items.DYE.pick(DyeColor.GREEN)));
                for (int index = 0; index < materials.size(); index++) player.getInventory().setItem(9 + index, materials.get(index));
                player.getInventory().setSelectedSlot(0);
                player.inventoryMenu.broadcastChanges();
                player.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + 2.6);
                return pos;
            });
            connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(3);
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                client.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, false);
            });
            context.getInput().lookAt(bench); context.waitTick();
            context.runOnClient(client -> require(client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(bench),
                    "Actual survival crosshair hits the vanilla workbench"));
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            context.waitForScreen(CraftingScreen.class);
            arrange(context, server, connection, "large_mahjong_table");
            context.waitFor(client -> ((CraftingMenu) client.player.containerMenu).getResultSlot().getItem().is(ModBlocks.LARGE_MAHJONG_TABLE.asItem()), 200);
            shot(context, "riichicraft-survival-table-recipe");
            takeResult(context, connection, false);
            require(server.computeOnServer(s -> connection.getServerPlayer().containerMenu.getCarried().is(ModBlocks.LARGE_MAHJONG_TABLE.asItem())
                    && connection.getServerPlayer().containerMenu.getCarried().getCount() == 1), "One actual result click creates one table on the native cursor");
            putCarried(context, connection, 0);
            arrange(context, server, connection, "mahjong_chair");
            shot(context, "riichicraft-survival-chair-recipe");
            takeResult(context, connection, true);
            require(server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(ModBlocks.MAHJONG_CHAIR.asItem()) == 4),
                    "Actual Shift-click crafts four chairs from the exact remaining materials");
            arrange(context, server, connection, "empty_chair");
            shot(context, "riichicraft-survival-empty-chair-recipe");
            takeResult(context, connection, false); putCarried(context, connection, 1);
            arrange(context, server, connection, "mahjong_chair_from_empty");
            takeResult(context, connection, true);
            require(server.computeOnServer(s -> {
                var inventory = connection.getServerPlayer().getInventory();
                return inventory.countItem(ModBlocks.LARGE_MAHJONG_TABLE.asItem()) == 1
                        && inventory.countItem(ModBlocks.MAHJONG_CHAIR.asItem()) == 4
                        && inventory.countItem(ModBlocks.EMPTY_CHAIR.asItem()) == 0
                        && inventory.getNonEquipmentItems().stream().allMatch(stack -> stack.isEmpty()
                            || stack.is(ModBlocks.LARGE_MAHJONG_TABLE.asItem()) || stack.is(ModBlocks.MAHJONG_CHAIR.asItem()))
                        && ((CraftingMenu) connection.getServerPlayer().containerMenu).getInputGridSlots().stream().allMatch(slot -> slot.getItem().isEmpty());
            }), "All four recipes consume the supplied materials and dyes, preserve four chairs, and leave no extra furniture or ingredients");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(null); sync(connection); context.waitTicks(5);
            BlockPos center = bench.east(3);
            Vec3 target = Vec3.atCenterOf(center.below()).add(0, .5, 0);
            float[] angles = context.computeOnClient(client -> {
                Vec3 delta = target.subtract(client.player.getEyePosition());
                return new float[]{(float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90),
                        (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)))};
            });
            context.getInput().lookAt(angles[0], angles[1]); context.waitTicks(2);
            context.runOnClient(client -> require(client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(center.below()),
                    "Actual survival placement ray selects the intended ground block"));
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT); sync(connection);
            require(server.computeOnServer(s -> {
                var state = connection.getServerLevel().getBlockState(center);
                if (!state.is(ModBlocks.LARGE_MAHJONG_TABLE)) return false;
                for (int part = 0; part < 9; part++) {
                    var piece = connection.getServerLevel().getBlockState(LargeMahjongTableBlock.position(center, state.getValue(LargeMahjongTableBlock.FACING), part));
                    if (!piece.is(ModBlocks.LARGE_MAHJONG_TABLE) || piece.getValue(LargeMahjongTableBlock.PART) != part) return false;
                }
                return connection.getServerPlayer().getInventory().countItem(ModBlocks.LARGE_MAHJONG_TABLE.asItem()) == 0;
            }), "The actually crafted table places all nine owned parts and consumes its single survival item");
            shot(context, "riichicraft-survival-crafted-table");
            server.runOnServer(s -> connection.getServerLevel().destroyBlock(center, false));
            } finally {
                // Later reconnect regressions deliberately reuse the original player's world position.
                server.runOnServer(s -> {
                    var player = connection.getServerPlayer();
                    player.closeContainer(); player.getInventory().clearContent();
                    for (int index = 0; index < previous.inventory().size(); index++) player.getInventory().setItem(index, previous.inventory().get(index).copy());
                    player.getInventory().setSelectedSlot(previous.selected()); player.setGameMode(previous.mode());
                    player.teleportTo(previous.position().x, previous.position().y, previous.position().z);
                    player.setYRot(previous.yaw()); player.setXRot(previous.pitch()); player.inventoryMenu.broadcastChanges();
                });
                sync(connection);
            }
        } finally {
            context.runOnClient(client -> client.options.setCameraType(original));
            context.getInput().resizeWindow(640, 480);
        }
    }

    private static void arrange(ClientGameTestContext context, TestDedicatedServerContext server, TestServerConnection connection, String name) {
        var key = ResourceKey.create(Registries.RECIPE, Riichicraft.id(name));
        server.waitFor(s -> connection.getServerPlayer().getRecipeBook().contains(key));
        var display = server.computeOnServer(s -> {
            List<RecipeDisplayEntry> entries = new ArrayList<>();
            s.getRecipeManager().listDisplaysForRecipe(key, entries::add);
            require(entries.size() == 1, "The loaded recipe supplies exactly one real recipe-book display");
            return entries.getFirst().id();
        });
        context.runOnClient(client -> client.gameMode.handlePlaceRecipe(client.player.containerMenu.containerId, display, true));
        sync(connection); context.waitTick();
        require(server.computeOnServer(s -> !((CraftingMenu) connection.getServerPlayer().containerMenu).getResultSlot().getItem().isEmpty()),
                "The unlocked recipe-book placement fills the real crafting grid from survival inventory");
    }

    private static void takeResult(ClientGameTestContext context, TestServerConnection connection, boolean shift) {
        double[] cursor = context.computeOnClient(client -> {
            var screen = (CraftingScreen) client.gui.screen(); var slot = screen.getMenu().getResultSlot();
            var window = client.getWindow();
            require(!client.player.getRecipeBook().isOpen(RecipeBookType.CRAFTING), "Use the vanilla centered workbench layout with the recipe book closed");
            double x = (window.getGuiScaledWidth() - 176) / 2 + slot.x + 8;
            double y = (window.getGuiScaledHeight() - 166) / 2 + slot.y + 8;
            return new double[]{x * window.getScreenWidth() / window.getGuiScaledWidth(), y * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        if (shift) {
            // Fabric 6.0.2's pressMouse always passes modifiers=0, even while holdShift is active.
            // Use its existing test-only accessor to deliver real modified mouse callbacks instead.
            context.getInput().holdShift();
            try {
                context.runOnClient(client -> ((MouseHandlerAccessor) client.mouseHandler).invokeOnButton(
                        client.getWindow().handle(), new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_SHIFT), GLFW.GLFW_PRESS));
                context.waitTick();
                context.runOnClient(client -> ((MouseHandlerAccessor) client.mouseHandler).invokeOnButton(
                        client.getWindow().handle(), new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_SHIFT), GLFW.GLFW_RELEASE));
            } finally { context.getInput().releaseShift(); }
        } else context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        sync(connection);
    }

    private static void putCarried(ClientGameTestContext context, TestServerConnection connection, int inventorySlot) {
        context.runOnClient(client -> {
            var menu = client.player.containerMenu;
            for (int index = 0; index < menu.slots.size(); index++) {
                var slot = menu.slots.get(index);
                if (slot.container == client.player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                    require(slot.getItem().isEmpty(), "Store the crafted cursor item in a genuinely empty inventory slot");
                    client.gameMode.handleContainerInput(menu.containerId, index, 0, ContainerInput.PICKUP, client.player); return;
                }
            }
            throw new AssertionError("No matching native inventory slot");
        });
        sync(connection);
    }

    private static void shot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> client.gui.toastManager().clear()); context.waitTick(); context.takeScreenshot(name);
    }
    private static void sync(TestServerConnection connection) { connection.waitForServerboundPackets(); connection.waitForClientboundPackets(); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
