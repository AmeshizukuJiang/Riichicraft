package dev.riichicraft.item;

import dev.riichicraft.Riichicraft;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ModItems {
    private static final ResourceKey<Item> TILE_KEY = ResourceKey.create(Registries.ITEM, Riichicraft.id("mahjong_tile"));
    public static final Item MAHJONG_TILE = Registry.register(BuiltInRegistries.ITEM, TILE_KEY,
            new Item(new Item.Properties().setId(TILE_KEY)));

    private ModItems() {
    }

    public static ItemStack tileStack(int tileId) {
        ItemStack stack = new ItemStack(MAHJONG_TILE);
        stack.set(DataComponents.ITEM_MODEL, TileAppearance.itemModel(tileId));
        stack.set(DataComponents.ITEM_NAME, TileAppearance.name(tileId));
        return stack;
    }

    public static void initialize() {
        // 26.2 assigns the name/model after Properties components. Apply our defaults
        // through Fabric's final component event so /give and creative one-man stack.
        DefaultItemComponentEvents.MODIFY.register(context -> context.modify(MAHJONG_TILE, builder -> {
            builder.set(DataComponents.ITEM_MODEL, TileAppearance.itemModel(0));
            builder.set(DataComponents.ITEM_NAME, TileAppearance.name(0));
        }));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(output -> TileAppearance.samples().forEach(id -> output.accept(tileStack(id))));
    }
}
