package dev.riichicraft.test;

import dev.riichicraft.Riichicraft;
import dev.riichicraft.block.ModBlocks;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Exercise loaded datapack recipes and native consumption/reward paths, not a second recipe implementation. */
public final class SurvivalRecipeGameTests {
    @GameTest
    public void furnitureRecipesAcceptPlanksAndRejectWrongOrMissingMaterials(GameTestHelper helper) {
        for (Item plank : List.of(Items.OAK_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS, Items.WARPED_PLANKS)) {
            matches(helper, "large_mahjong_table", input(table(plank)), ModBlocks.LARGE_MAHJONG_TABLE.asItem());
            matches(helper, "mahjong_chair", input(chair(plank)), ModBlocks.MAHJONG_CHAIR.asItem());
        }
        Item[] mixed = table(Items.OAK_PLANKS);
        mixed[0] = Items.BAMBOO_PLANKS; mixed[8] = Items.WARPED_PLANKS;
        matches(helper, "large_mahjong_table", input(mixed), ModBlocks.LARGE_MAHJONG_TABLE.asItem());
        mixed[4] = Items.WOOL.pick(DyeColor.LIME);
        rejects(helper, "large_mahjong_table", input(mixed));
        mixed[4] = Items.WOOL.pick(DyeColor.GREEN); mixed[1] = Items.GOLD_INGOT;
        rejects(helper, "large_mahjong_table", input(mixed));
        Item[] missing = chair(Items.OAK_PLANKS); missing[6] = null;
        rejects(helper, "mahjong_chair", input(missing));
        missing[6] = Items.STICK; missing[7] = Items.DYE.pick(DyeColor.GRAY);
        rejects(helper, "mahjong_chair", input(missing));
        rejects(helper, "large_mahjong_table", CraftingInput.of(2, 2, List.of(new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.WOOL.pick(DyeColor.GREEN)), new ItemStack(Items.OAK_PLANKS))));
        helper.succeed();
    }

    @GameTest
    public void chairConversionsFitBothGridsButRequireExactDyeAndOneChair(GameTestHelper helper) {
        for (int side : List.of(2, 3)) for (int first = 0; first < side * side; first++) for (int second = 0; second < side * side; second++) {
            if (first == second) continue;
            Item[] cells = new Item[side * side];
            cells[first] = ModBlocks.MAHJONG_CHAIR.asItem(); cells[second] = Items.DYE.pick(DyeColor.GRAY);
            matches(helper, "empty_chair", input(side, cells), ModBlocks.EMPTY_CHAIR.asItem());
            cells[first] = ModBlocks.EMPTY_CHAIR.asItem(); cells[second] = Items.DYE.pick(DyeColor.GREEN);
            matches(helper, "mahjong_chair_from_empty", input(side, cells), ModBlocks.MAHJONG_CHAIR.asItem());
        }
        rejects(helper, "empty_chair", input(2, ModBlocks.MAHJONG_CHAIR.asItem(), Items.DYE.pick(DyeColor.LIGHT_GRAY), null, null));
        rejects(helper, "mahjong_chair_from_empty", input(2, ModBlocks.EMPTY_CHAIR.asItem(), Items.DYE.pick(DyeColor.LIME), null, null));
        rejects(helper, "empty_chair", input(2, ModBlocks.MAHJONG_CHAIR.asItem(), Items.DYE.pick(DyeColor.GRAY), ModBlocks.MAHJONG_CHAIR.asItem(), null));
        helper.succeed();
    }

    @GameTest
    public void nativePickupAndShiftCraftConsumeExactlyOneBatchPerOutput(GameTestHelper helper) {
        BlockPos bench = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.getLevel().setBlockAndUpdate(bench, Blocks.CRAFTING_TABLE.defaultBlockState());
        try (var fixture = new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(), bench, "CraftConsume")) {
            var player = fixture.player(); player.setGameMode(GameType.SURVIVAL);
            List<Item[]> grids = List.of(table(Items.OAK_PLANKS), chair(Items.CHERRY_PLANKS),
                    new Item[]{ModBlocks.MAHJONG_CHAIR.asItem(), Items.DYE.pick(DyeColor.GRAY), null, null, null, null, null, null, null},
                    new Item[]{null, null, null, null, ModBlocks.EMPTY_CHAIR.asItem(), null, null, null, Items.DYE.pick(DyeColor.GREEN)});
            List<Item> outputs = List.of(ModBlocks.LARGE_MAHJONG_TABLE.asItem(), ModBlocks.MAHJONG_CHAIR.asItem(),
                    ModBlocks.EMPTY_CHAIR.asItem(), ModBlocks.MAHJONG_CHAIR.asItem());
            for (int index = 0; index < grids.size(); index++) {
                var grid = grids.get(index); Item output = outputs.get(index);
                player.getInventory().clearContent();
                CraftingMenu menu = menu(helper, player, bench, grid, 3);
                helper.assertTrue(menu.getResultSlot().getItem().is(output), "Native crafting result is the registered furniture item");
                menu.clicked(0, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(menu.getCarried().is(output) && menu.getCarried().getCount() == 1,
                        "A normal result click crafts exactly one item");
                for (int slot = 0; slot < 9; slot++) helper.assertTrue(grid[slot] == null ? menu.getInputGridSlots().get(slot).getItem().isEmpty()
                        : menu.getInputGridSlots().get(slot).getItem().getCount() == 2, "One batch consumes one item from each occupied ingredient slot");
                menu.setCarried(ItemStack.EMPTY);
                menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
                helper.assertTrue(player.getInventory().countItem(output) == 2 && menu.getInputGridSlots().stream().allMatch(slot -> slot.getItem().isEmpty())
                        && menu.getResultSlot().getItem().isEmpty(), "Shift crafting consumes the two remaining batches with no extra output or residual ingredients");
            }
            player.containerMenu = player.inventoryMenu;
        }
        helper.succeed();
    }

    @GameTest
    public void inventoryCriteriaUnlockFurnitureAndConversionRecipes(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
        try (var fixture = new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(), position, "CraftUnlock")) {
            var player = fixture.player(); player.setGameMode(GameType.SURVIVAL);
            for (String name : List.of("large_mahjong_table", "mahjong_chair", "empty_chair", "mahjong_chair_from_empty"))
                helper.assertTrue(!player.getRecipeBook().contains(key(name)), "New player does not start with this recipe unlocked");
            obtain(player, Items.OAK_PLANKS);
            helper.assertTrue(player.getRecipeBook().contains(key("large_mahjong_table")) && player.getRecipeBook().contains(key("mahjong_chair")),
                    "Obtaining planks triggers the loaded recipe-book advancements");
            helper.assertTrue(!player.getRecipeBook().contains(key("empty_chair")), "Conversion does not unlock before obtaining a chair");
            obtain(player, ModBlocks.MAHJONG_CHAIR.asItem());
            helper.assertTrue(player.getRecipeBook().contains(key("empty_chair")), "Ordinary chair unlocks the empty-chair conversion");
            obtain(player, ModBlocks.EMPTY_CHAIR.asItem());
            helper.assertTrue(player.getRecipeBook().contains(key("mahjong_chair_from_empty")), "Empty chair unlocks restoring the ordinary chair");
        }
        helper.succeed();
    }

    private static void obtain(ServerPlayer player, Item item) {
        ItemStack stack = new ItemStack(item); player.getInventory().setItem(0, stack);
        CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), stack);
    }

    private static CraftingMenu menu(GameTestHelper helper, ServerPlayer player, BlockPos bench, Item[] grid, int count) {
        var menu = new CraftingMenu(1, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), bench));
        player.containerMenu = menu;
        for (int slot = 0; slot < 9; slot++) menu.getInputGridSlots().get(slot).set(grid[slot] == null ? ItemStack.EMPTY : new ItemStack(grid[slot], count));
        return menu;
    }

    private static void matches(GameTestHelper helper, String name, CraftingInput input, Item output) {
        var holder = helper.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).orElseThrow();
        helper.assertTrue(holder.id().equals(key(name)), "Native matching selects the exact loaded Riichicraft recipe");
        ItemStack result = holder.value().assemble(input);
        helper.assertTrue(result.is(output) && result.getCount() == 1, "Every batch yields one furniture item");
    }

    private static void rejects(GameTestHelper helper, String name, CraftingInput input) {
        var recipe = (CraftingRecipe) helper.getLevel().getServer().getRecipeManager().byKey(key(name)).orElseThrow().value();
        helper.assertTrue(!recipe.matches(input, helper.getLevel()), "Incorrect materials, quantity or grid cannot use this furniture recipe");
    }

    private static ResourceKey<Recipe<?>> key(String name) { return ResourceKey.create(Registries.RECIPE, Riichicraft.id(name)); }
    private static CraftingInput input(Item... cells) { return input(3, cells); }
    private static CraftingInput input(int side, Item... cells) {
        return CraftingInput.of(side, side, Arrays.stream(cells).map(item -> item == null ? ItemStack.EMPTY : new ItemStack(item)).toList());
    }
    private static Item[] table(Item plank) { return new Item[]{plank, Items.IRON_INGOT, plank, plank, Items.WOOL.pick(DyeColor.GREEN), plank, plank, Items.IRON_INGOT, plank}; }
    private static Item[] chair(Item plank) { return new Item[]{plank, plank, plank, plank, Items.WOOL.pick(DyeColor.GREEN), plank, Items.STICK, null, Items.STICK}; }
}
